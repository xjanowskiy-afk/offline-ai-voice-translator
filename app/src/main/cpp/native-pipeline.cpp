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

struct PipelineEngine {
    const SherpaOnnxVoiceActivityDetector* vad = nullptr;
    const SherpaOnnxOnlineRecognizer* asr = nullptr;
    SherpaOnnxOnlineStream* asrStream = nullptr;

    const SherpaOnnxOfflineTts* ttsRu = nullptr;
    const SherpaOnnxOfflineTts* ttsDe = nullptr;

    llama_model* llmModel = nullptr;
    llama_context* llmCtx = nullptr;
    const llama_vocab* llmVocab = nullptr;

    std::mutex mtx;
};

static PipelineEngine g_pipeline;

static jstring toJString(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_initVad(
    JNIEnv* env,
    jobject,
    jstring vadModelPath
) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
    if (!g_pipeline.vad) return JNI_FALSE;

    auto* samples = static_cast<const float*>(env->GetDirectBufferAddress(directBuffer));
    if (!samples) return JNI_FALSE;

    SherpaOnnxVoiceActivityDetectorAcceptWaveform(g_pipeline.vad, samples, sampleCount);
    return SherpaOnnxVoiceActivityDetectorIsSpeechDetected(g_pipeline.vad) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_resetVad(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);

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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return;

    auto* samples = static_cast<const float*>(env->GetDirectBufferAddress(directBuffer));
    if (!samples) return;

    SherpaOnnxOnlineStreamAcceptWaveform(g_pipeline.asrStream, 16000, samples, sampleCount);
    while (SherpaOnnxIsOnlineStreamReady(g_pipeline.asr, g_pipeline.asrStream)) {
        SherpaOnnxDecodeOnlineStream(g_pipeline.asr, g_pipeline.asrStream);
    }
}

JNIEXPORT jstring JNICALL
Java_com_translator_offline_NativeBridge_getAsrResult(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return env->NewStringUTF("");

    const auto* result = SherpaOnnxGetOnlineStreamResult(g_pipeline.asr, g_pipeline.asrStream);
    if (!result || !result->text) return env->NewStringUTF("");

    jstring res = env->NewStringUTF(result->text);
    SherpaOnnxDestroyOnlineRecognizerResult(result);
    return res;
}

JNIEXPORT jboolean JNICALL
Java_com_translator_offline_NativeBridge_isAsrEndpoint(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
    if (!g_pipeline.asr || !g_pipeline.asrStream) return JNI_FALSE;
    return SherpaOnnxOnlineRecognizerIsEndpoint(g_pipeline.asr, g_pipeline.asrStream) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_resetAsrStream(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
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

    g_pipeline.llmVocab = llama_model_get_vocab(g_pipeline.llmModel);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024;
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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
    if (!g_pipeline.llmCtx || !g_pipeline.llmModel || !g_pipeline.llmVocab) return;

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");

    const char* pCStr = env->GetStringUTFChars(promptStr, nullptr);
    std::string prompt(pCStr);
    env->ReleaseStringUTFChars(promptStr, pCStr);

    std::vector<llama_token> tokens_list(prompt.length() + 128);
    int n_tokens = llama_tokenize(
        g_pipeline.llmVocab, prompt.c_str(), prompt.length(),
        tokens_list.data(), tokens_list.size(), true, false
    );
    if (n_tokens < 0) {
        tokens_list.resize(-n_tokens);
        n_tokens = llama_tokenize(
            g_pipeline.llmVocab, prompt.c_str(), prompt.length(),
            tokens_list.data(), tokens_list.size(), true, false
        );
    }
    tokens_list.resize(n_tokens);

    llama_kv_cache_clear(g_pipeline.llmCtx);

    llama_batch batch = llama_batch_init(2048, 0, 1);
    for (size_t i = 0; i < tokens_list.size(); i++) {
        llama_batch_add(batch, tokens_list[i], i, { 0 }, false);
    }
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(g_pipeline.llmCtx, batch) != 0) {
        llama_batch_free(batch);
        return;
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    const int max_tokens = 256;
    int n_cur = batch.n_tokens;
    int n_decode = 0;

    while (n_decode < max_tokens) {
        llama_token new_token_id = llama_sampler_sample(smpl, g_pipeline.llmCtx, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(g_pipeline.llmVocab, new_token_id)) {
            jstring emptyStr = env->NewStringUTF("");
            env->CallVoidMethod(callback, onTokenMethod, emptyStr, JNI_TRUE);
            env->DeleteLocalRef(emptyStr);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(g_pipeline.llmVocab, new_token_id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            std::string piece(buf, n);
            jstring jPiece = toJString(env, piece);
            env->CallVoidMethod(callback, onTokenMethod, jPiece, JNI_FALSE);
            env->DeleteLocalRef(jPiece);
        }

        llama_batch_clear(batch);
        llama_batch_add(batch, new_token_id, n_cur, { 0 }, true);

        n_decode++;
        n_cur++;

        if (llama_decode(g_pipeline.llmCtx, batch) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    llama_kv_cache_clear(g_pipeline.llmCtx);
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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);

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
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);
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
    env->SetFloatArrayRegion(result, 0, totalSamples, pcmWithSilence.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_translator_offline_NativeBridge_releaseAll(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_pipeline.mtx);

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
