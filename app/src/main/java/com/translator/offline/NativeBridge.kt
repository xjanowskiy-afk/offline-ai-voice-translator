package com.translator.offline

import java.nio.ByteBuffer

object NativeBridge {

    private var isLoaded = false
    private var loadError: String? = null

    @Synchronized
    fun loadLibrariesSafely(): Boolean {
        if (isLoaded) return true
        if (loadError != null) return false

        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("sherpa-onnx-jni")
            System.loadLibrary("native-pipeline")
            isLoaded = true
            return true
        } catch (t: Throwable) {
            loadError = "Native library load failed: ${t.javaClass.simpleName} - ${t.message}"
            com.translator.offline.ui.AppLogger.log("ERROR: $loadError")
            return false
        }
    }

    fun getLoadError(): String? = loadError
    fun isNativeReady(): Boolean = isLoaded

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
