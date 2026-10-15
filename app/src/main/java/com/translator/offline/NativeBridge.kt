package com.translator.offline

import java.nio.ByteBuffer

object NativeBridge {

    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-c-api")
        System.loadLibrary("native-pipeline")
    }

    fun interface TokenCallback {
        fun onToken(token: String, isEndOfGeneration: Boolean)
    }

    external fun initVad(vadModelPath: String): Boolean
    external fun processVadChunk(directBuffer: ByteBuffer, sampleCount: Int): Boolean
    external fun resetVad()

    external fun initAsr(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String
    ): Boolean
    external fun feedAudioToAsr(directBuffer: ByteBuffer, sampleCount: Int)
    external fun getAsrResult(): String
    external fun isAsrEndpoint(): Boolean
    external fun resetAsrStream()

    external fun initLlm(modelPath: String): Boolean
    external fun translateStreaming(prompt: String, callback: TokenCallback)

    external fun initTtsDual(
        ruModelPath: String,
        ruTokensPath: String,
        deModelPath: String,
        deTokensPath: String,
        dataDir: String
    ): Boolean

    external fun synthesizeSpeechChunkByLang(
        textChunk: String,
        isGerman: Boolean,
        speakerId: Int
    ): FloatArray

    external fun releaseAll()
}
