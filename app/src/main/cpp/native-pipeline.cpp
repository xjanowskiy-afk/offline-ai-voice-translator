#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <cstring>

#include "llama.h"
#include "sherpa-onnx/c-api/c-api.h"

#define TAG "NativePipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// LLM context size and generation budget. initLlm() uses LLM_N_CTX; translateStreaming()
// clamps the prompt so that prompt_tokens + LLM_MAX_NEW_TOKENS <= LLM_N_CTX.
static constexpr int LLM_N_CTX = 1024;
static constexpr int LLM_MAX_NEW_TOKENS = 256;

struct PipelineEngine {
    SherpaOnnxVoiceActivityDetector* vad = nullptr;
    const SherpaOnnxOnlineRecognizer* asr = nullptr;
    const SherpaOnnxOnlineStream* asrStream = nullptr;

    const SherpaOnnxOfflineTts* ttsRu = nullptr;
    const SherpaOnnxOfflineTts* ttsDe = nullptr;

    llama_model* llmModel = nullptr;
    llama_context* llmCtx = nullptr;

    // Per-engine mutexes with a STRICT lock order: mtx_vad < mtx_asr < mtx_llm < mtx_tts.
    // Rules:
    //  - Per-call functions lock ONLY the engine they touch, so the mic capture path
    //    (processVadChunk / feedAudioToAsr) NEVER blocks on LLM generation, and TTS
    //    synthesis never blocks on generation either (full-duplex stays possible).
    //  - Functions that touch everything (releaseAll) acquire all four in ascending
    //    order. No other function holds more than one engine mutex at a time.
    //  - The onToken upcall inside translateStreaming runs with mtx_llm held; the Java
    //    callback must NEVER call back into NativeBridge (would deadlock on mtx_llm).
    std::mutex mtx_vad;
    std::mutex mtx_asr;
    std::mutex mtx_llm;
    std::mutex mtx_tts;
};

static PipelineEngine g_pipeline;

static jstring toJString(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Verifies that the direct buffer really holds at least `sampleCount` floats.
// Protects against buffer over-read if the Java side ever passes a stale buffer.
static bool checkDirectBufferCapacity(JNIEnv* env, jobject directBuffer, jint sampleCount) {
    if (sampleCount < 0) return false;
    const jlong capacityBytes = env->GetDirectBufferCapacity(directBuffer);
    if (capacityBytes < 0) return false;
    return capacityBytes >= static_cast<jlong>(sampleCount) * static_cast<jlong>(sizeof(float));
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_initVad(
    JNIEnv* env,
    jobject,
    jstring vadModelPath
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_vad);
    const char* path = env->GetStringUTFChars(vadModelPath, nullptr);

    SherpaOnnxVadModelConfig config;
    std::memset(&config, 0, sizeof(config));
    config.silero_vad.model = path;
    config.silero_vad.threshold = 0.5f;
    config.silero_vad.min_silence_duration = 0.5f;
    config.silero_vad.min_speech_duration = 0.25f;
    config.silero_vad.window_size = 512;
    config.silero_vad.max_speech_duration = 30.0f;
    config.sample_rate = 16000;
    config.num_threads = 2;
    config.provider = "cpu";
    config.debug = 0;

    if (g_pipeline.vad) {
        SherpaOnnxDestroyVoiceActivityDetector(g_pipeline.vad);
        g_pipeline.vad = nullptr;
    }

    g_pipeline.vad = SherpaOnnxCreateVoiceActivityDetector(&config, 0.3f);
    env->ReleaseStringUTFChars(vadModelPath, path);

    return g_pipeline.vad != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_processVadChunk(
    JNIEnv* env,
    jobject,
    jobject directBuffer,
    jint sampleCount
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_vad);
    if (!g_pipeline.vad) return JNI_FALSE;

    auto* samples = static_cast<const float*>(env->GetDirectBufferAddress(directBuffer));
    if (!samples) return JNI_FALSE;
    if (!checkDirectBufferCapacity(env, directBuffer, sampleCount)) return JNI_FALSE;

    SherpaOnnxVoiceActivityDetectorAcceptWaveform(g_pipeline.vad, samples, sampleCount);
    return SherpaOnnxVoiceActivityDetectorDetected(g_pipeline.vad) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_resetVad(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_vad);
    if (g_pipeline.vad) {
        SherpaOnnxVoiceActivityDetectorClear(g_pipeline.vad);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_initAsr(
    JNIEnv* env,
    jobject,
    jstring encoderPath,
    jstring decoderPath,
    jstring joinerPath,
    jstring tokensPath
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_asr);

    const char* enc = env->GetStringUTFChars(encoderPath, nullptr);
    const char* dec = env->GetStringUTFChars(decoderPath, nullptr);
    const char* join = env->GetStringUTFChars(joinerPath, nullptr);
    const char* tok = env->GetStringUTFChars(tokensPath, nullptr);

    SherpaOnnxOnlineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;

    config.model_config.transducer.encoder = enc;
    config.model_config.transducer.decoder = dec;
    config.model_config.transducer.joiner = join;
    config.model_config.tokens = tok;
    config.model_config.num_threads = 4;
    config.model_config.provider = "cpu";
    config.model_config.model_type = "zipformer";

    config.decoding_method = "greedy_search";
    config.max_active_paths = 4;
    config.enable_endpoint = 1;
    config.rule1_min_trailing_silence = 2.4f;
    config.rule2_min_trailing_silence = 1.2f;
    config.rule3_min_utterance_length = 20.0f;

    if (g_pipeline.asrStream) {
        SherpaOnnxDestroyOnlineStream(g_pipeline.asrStream);
        g_pipeline.asrStream = nullptr;
    }
    if (g_pipeline.asr) {
        SherpaOnnxDestroyOnlineRecognizer(g_pipeline.asr);
        g_pipeline.asr = nullptr;
    }

    g_pipeline.asr = SherpaOnnxCreateOnlineRecognizer(&config);

    env->ReleaseStringUTFChars(encoderPath, enc);
    env->ReleaseStringUTFChars(decoderPath, dec);
    env->ReleaseStringUTFChars(joinerPath, join);
    env->ReleaseStringUTFChars(tokensPath, tok);

    if (!g_pipeline.asr) return JNI_FALSE;

    g_pipeline.asrStream = SherpaOnnxCreateOnlineStream(g_pipeline.asr);
    return g_pipeline.asrStream != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_feedAudioToAsr(
    JNIEnv* env,
    jobject,
    jobject directBuffer,
    jint sampleCount
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_asr);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return;

    auto* samples = static_cast<const float*>(env->GetDirectBufferAddress(directBuffer));
    if (!samples) return;
    if (!checkDirectBufferCapacity(env, directBuffer, sampleCount)) return;

    SherpaOnnxOnlineStreamAcceptWaveform(g_pipeline.asrStream, 16000, samples, sampleCount);
    while (SherpaOnnxIsOnlineStreamReady(g_pipeline.asr, g_pipeline.asrStream)) {
        SherpaOnnxDecodeOnlineStream(g_pipeline.asr, g_pipeline.asrStream);
    }
}

JNIEXPORT jstring JNICALL
Java_com_translator_offline_NativeBridge_getAsrResult(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_asr);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return env->NewStringUTF("");

    const auto* result = SherpaOnnxGetOnlineStreamResult(g_pipeline.asr, g_pipeline.asrStream);
    if (!result || !result->text) return env->NewStringUTF("");

    jstring res = env->NewStringUTF(result->text);
    SherpaOnnxDestroyOnlineRecognizerResult(result);
    // res is null only under OOM; the caller then receives null instead of crashing.
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_isAsrEndpoint(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_asr);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return JNI_FALSE;
    return SherpaOnnxOnlineStreamIsEndpoint(g_pipeline.asr, g_pipeline.asrStream) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_resetAsrStream(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_asr);
    if (g_pipeline.asr) {
        if (g_pipeline.asrStream) {
            SherpaOnnxDestroyOnlineStream(g_pipeline.asrStream);
        }
        g_pipeline.asrStream = SherpaOnnxCreateOnlineStream(g_pipeline.asr);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_initLlm(
    JNIEnv* env,
    jobject,
    jstring modelPath
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_llm);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    if (g_pipeline.llmCtx) {
        llama_free(g_pipeline.llmCtx);
        g_pipeline.llmCtx = nullptr;
    }
    if (g_pipeline.llmModel) {
        llama_free_model(g_pipeline.llmModel);
        g_pipeline.llmModel = nullptr;
    }

    g_pipeline.llmModel = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_pipeline.llmModel) return JNI_FALSE;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = LLM_N_CTX;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    ctx_params.logits_all = false;

    g_pipeline.llmCtx = llama_new_context_with_model(g_pipeline.llmModel, ctx_params);
    return g_pipeline.llmCtx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_translateStreaming(
    JNIEnv* env,
    jobject,
    jstring promptStr,
    jobject callback
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_llm);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = nullptr;
    if (callbackClass) {
        onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(callbackClass);
    }

    // Guarded terminal sender: the Kotlin side must ALWAYS receive one final
    // onToken(..., isEndOfGeneration=true), whatever goes wrong, so it never
    // hangs in the "translating" state. No-op when the callback is unusable.
    bool terminalSent = false;
    auto sendTerminal = [&]() {
        if (terminalSent || !onTokenMethod) return;
        terminalSent = true;
        jstring emptyStr = env->NewStringUTF("");
        if (emptyStr) {
            env->CallVoidMethod(callback, onTokenMethod, emptyStr, JNI_TRUE);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(emptyStr);
        }
    };

    if (!g_pipeline.llmCtx || !g_pipeline.llmModel) {
        sendTerminal();  // never initialized — still release the UI from "translating"
        return;
    }
    if (!onTokenMethod) return;

    const char* pCStr = env->GetStringUTFChars(promptStr, nullptr);
    std::string prompt(pCStr);
    env->ReleaseStringUTFChars(promptStr, pCStr);

    std::vector<llama_token> tokens_list(prompt.length() + 128);
    int n_tokens = llama_tokenize(
        g_pipeline.llmModel, prompt.c_str(), prompt.length(),
        tokens_list.data(), static_cast<int32_t>(tokens_list.size()), true, false
    );
    if (n_tokens < 0) {
        tokens_list.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(
            g_pipeline.llmModel, prompt.c_str(), prompt.length(),
            tokens_list.data(), static_cast<int32_t>(tokens_list.size()), true, false
        );
    }
    if (n_tokens <= 0) {
        sendTerminal();  // empty/failed tokenization — nothing to generate
        return;
    }
    tokens_list.resize(static_cast<size_t>(n_tokens));

    // Clamp the prompt from the FRONT so prompt_tokens + max_new_tokens <= n_ctx.
    // (Front truncation keeps the most recent instructions, which matter most.)
    if (n_tokens + LLM_MAX_NEW_TOKENS > LLM_N_CTX) {
        const int keep = LLM_N_CTX - LLM_MAX_NEW_TOKENS;
        if (keep <= 0) {
            sendTerminal();
            return;
        }
        tokens_list.erase(tokens_list.begin(),
                          tokens_list.begin() + (n_tokens - keep));
        n_tokens = keep;
    }

    llama_kv_cache_clear(g_pipeline.llmCtx);

    // Batch capacity is n_ctx — total tokens can never exceed the context.
    llama_batch batch = llama_batch_init(LLM_N_CTX, 0, 1);
    if (!batch.token || !batch.pos || !batch.logits) {
        llama_batch_free(batch);
        sendTerminal();
        return;
    }
    for (size_t i = 0; i < tokens_list.size(); i++) {
        batch.token[i] = tokens_list[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.n_tokens = n_tokens;
    if (batch.n_tokens > 0) {
        batch.logits[batch.n_tokens - 1] = true;
    }

    if (llama_decode(g_pipeline.llmCtx, batch) != 0) {
        llama_batch_free(batch);
        sendTerminal();  // decode error — terminal so the UI does not hang
        return;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    const int max_tokens = LLM_MAX_NEW_TOKENS;
    int n_cur = batch.n_tokens;
    int n_decode = 0;

    while (n_decode < max_tokens) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_pipeline.llmCtx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_token_is_eog(g_pipeline.llmModel, new_token_id)) {
            sendTerminal();
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(g_pipeline.llmModel, new_token_id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jPiece = toJString(env, piece);
            if (!jPiece) break;  // OOM — stop generation, terminal below
            env->CallVoidMethod(callback, onTokenMethod, jPiece, JNI_FALSE);
            env->DeleteLocalRef(jPiece);
            if (env->ExceptionCheck()) {
                // The Java callback threw: clear it and stop generation cleanly
                // instead of continuing with UB on subsequent JNI calls.
                env->ExceptionClear();
                break;
            }
        }

        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        n_decode++;
        n_cur++;

        if (llama_decode(g_pipeline.llmCtx, batch) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    llama_kv_cache_clear(g_pipeline.llmCtx);
    sendTerminal();  // guaranteed exactly once: normal end, EOG, decode error, OOM, callback exception
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_initTtsDual(
    JNIEnv* env,
    jobject,
    jstring ruModelPath,
    jstring ruTokensPath,
    jstring deModelPath,
    jstring deTokensPath,
    jstring dataDir
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_tts);

    const char* ruModel = env->GetStringUTFChars(ruModelPath, nullptr);
    const char* ruTok = env->GetStringUTFChars(ruTokensPath, nullptr);
    const char* deModel = env->GetStringUTFChars(deModelPath, nullptr);
    const char* deTok = env->GetStringUTFChars(deTokensPath, nullptr);
    const char* data = env->GetStringUTFChars(dataDir, nullptr);

    SherpaOnnxOfflineTtsConfig cfgRu;
    std::memset(&cfgRu, 0, sizeof(cfgRu));
    cfgRu.model.vits.model = ruModel;
    cfgRu.model.vits.tokens = ruTok;
    cfgRu.model.vits.data_dir = data;
    cfgRu.model.vits.noise_scale = 0.667f;
    cfgRu.model.vits.noise_scale_w = 0.8f;
    cfgRu.model.vits.length_scale = 1.05f;
    cfgRu.model.num_threads = 4;
    cfgRu.model.provider = "cpu";

    SherpaOnnxOfflineTtsConfig cfgDe;
    std::memset(&cfgDe, 0, sizeof(cfgDe));
    cfgDe.model.vits.model = deModel;
    cfgDe.model.vits.tokens = deTok;
    cfgDe.model.vits.data_dir = data;
    cfgDe.model.vits.noise_scale = 0.667f;
    cfgDe.model.vits.noise_scale_w = 0.8f;
    cfgDe.model.vits.length_scale = 1.05f;
    cfgDe.model.num_threads = 4;
    cfgDe.model.provider = "cpu";

    if (g_pipeline.ttsRu) SherpaOnnxDestroyOfflineTts(g_pipeline.ttsRu);
    if (g_pipeline.ttsDe) SherpaOnnxDestroyOfflineTts(g_pipeline.ttsDe);

    g_pipeline.ttsRu = SherpaOnnxCreateOfflineTts(&cfgRu);
    g_pipeline.ttsDe = SherpaOnnxCreateOfflineTts(&cfgDe);

    env->ReleaseStringUTFChars(ruModelPath, ruModel);
    env->ReleaseStringUTFChars(ruTokensPath, ruTok);
    env->ReleaseStringUTFChars(deModelPath, deModel);
    env->ReleaseStringUTFChars(deTokensPath, deTok);
    env->ReleaseStringUTFChars(dataDir, data);

    return (g_pipeline.ttsRu && g_pipeline.ttsDe) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_translator_offline_NativeBridge_synthesizeSpeechChunkByLang(
    JNIEnv* env,
    jobject,
    jstring textChunk,
    jboolean isGerman,
    jint speakerId
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx_tts);
    const SherpaOnnxOfflineTts* activeTts = isGerman ? g_pipeline.ttsDe : g_pipeline.ttsRu;
    if (!activeTts) return env->NewFloatArray(0);

    const char* text = env->GetStringUTFChars(textChunk, nullptr);
    const auto* audio = SherpaOnnxOfflineTtsGenerate(activeTts, text, speakerId, 1.0f);
    env->ReleaseStringUTFChars(textChunk, text);

    if (!audio || audio->n <= 0) {
        if (audio) SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
        return env->NewFloatArray(0);
    }

    const int silenceSamples = 3300;
    int totalSamples = audio->n + silenceSamples;

    std::vector<float> pcmWithSilence(totalSamples, 0.0f);
    std::memcpy(pcmWithSilence.data(), audio->samples, audio->n * sizeof(float));
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);

    jfloatArray result = env->NewFloatArray(totalSamples);
    if (!result) return nullptr;  // OOM — report null instead of SetFloatArrayRegion(NULL)
    env->SetFloatArrayRegion(result, 0, totalSamples, pcmWithSilence.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_releaseAll(JNIEnv*, jobject) {
    // Acquire ALL engine mutexes in the strict ascending lock order
    // (vad < asr < llm < tts) so no deadlock is possible with any caller.
    // NOTE: this blocks until in-flight generation/capture/TTS finish. Callers
    // should invoke releaseAll off the main thread to avoid ANR (Kotlin side).
    std::unique_lock<std::mutex> lockVad(g_pipeline.mtx_vad);
    std::unique_lock<std::mutex> lockAsr(g_pipeline.mtx_asr);
    std::unique_lock<std::mutex> lockLlm(g_pipeline.mtx_llm);
    std::unique_lock<std::mutex> lockTts(g_pipeline.mtx_tts);

    if (g_pipeline.vad) {
        SherpaOnnxDestroyVoiceActivityDetector(g_pipeline.vad);
        g_pipeline.vad = nullptr;
    }
    if (g_pipeline.asrStream) {
        SherpaOnnxDestroyOnlineStream(g_pipeline.asrStream);
        g_pipeline.asrStream = nullptr;
    }
    if (g_pipeline.asr) {
        SherpaOnnxDestroyOnlineRecognizer(g_pipeline.asr);
        g_pipeline.asr = nullptr;
    }
    if (g_pipeline.ttsRu) {
        SherpaOnnxDestroyOfflineTts(g_pipeline.ttsRu);
        g_pipeline.ttsRu = nullptr;
    }
    if (g_pipeline.ttsDe) {
        SherpaOnnxDestroyOfflineTts(g_pipeline.ttsDe);
        g_pipeline.ttsDe = nullptr;
    }
    if (g_pipeline.llmCtx) {
        llama_free(g_pipeline.llmCtx);
        g_pipeline.llmCtx = nullptr;
    }
    if (g_pipeline.llmModel) {
        llama_free_model(g_pipeline.llmModel);
        g_pipeline.llmModel = nullptr;
    }
    llama_backend_free();
}

} // extern "C"
