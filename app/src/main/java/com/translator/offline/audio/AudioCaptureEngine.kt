package com.translator.offline.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.translator.offline.NativeBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureEngine(
    private val onSpeechSegmentReady: () -> Unit
) {
    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_FRAME = 480
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val directFloatBuffer: ByteBuffer = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME * 4)
        .order(ByteOrder.nativeOrder())
    private val pcmShortArray = ShortArray(SAMPLES_PER_FRAME)

    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    private val _audioAmplitude = kotlinx.coroutines.flow.MutableStateFlow(0.0f)
    val audioAmplitude = _audioAmplitude.asStateFlow()

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var isHalfDuplexBlocked: Boolean = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isCapturing) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, FRAME_BYTES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Ошибка инициализации AudioRecord!")
            record.release()
            return
        }

        val audioSessionId = record.audioSessionId

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                enabled = true
            }
        }

        audioRecord = record
        record.startRecording()
        isCapturing = true

        captureJob = captureScope.launch {
            runCaptureLoop()
        }
    }

    private suspend fun runCaptureLoop() {
        var consecutiveSilenceFrames = 0
        var hasActiveSpeech = false

        while (currentCoroutineContext().isActive && isCapturing) {
            val record = audioRecord ?: break

            val readSamples = record.read(pcmShortArray, 0, SAMPLES_PER_FRAME)
            if (readSamples <= 0) continue

            if (isHalfDuplexBlocked) {
                consecutiveSilenceFrames = 0
                NativeBridge.resetVad()
                continue
            }

            var sumSq = 0.0
            directFloatBuffer.clear()
            for (i in 0 until readSamples) {
                val floatSample = pcmShortArray[i] / 32768.0f
                sumSq += floatSample * floatSample
                directFloatBuffer.putFloat(floatSample)
            }
            directFloatBuffer.flip()

            val rms = Math.sqrt(sumSq / readSamples).toFloat()
            _audioAmplitude.value = (rms * 5.0f).coerceIn(0.0f, 1.0f)

            val isSpeech = NativeBridge.processVadChunk(directFloatBuffer, readSamples)

            if (isSpeech) {
                hasActiveSpeech = true
                consecutiveSilenceFrames = 0
                NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
            } else {
                if (hasActiveSpeech) {
                    consecutiveSilenceFrames++
                    if (consecutiveSilenceFrames <= 5) {
                        NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
                    }

                    if (consecutiveSilenceFrames >= 18 || NativeBridge.isAsrEndpoint()) {
                        hasActiveSpeech = false
                        consecutiveSilenceFrames = 0
                        onSpeechSegmentReady()
                    }
                }
            }
        }
    }

    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        audioRecord?.apply {
            try {
                stop()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки AudioRecord", e)
            }
            release()
        }
        audioRecord = null

        echoCanceler?.release()
        echoCanceler = null

        noiseSuppressor?.release()
        noiseSuppressor = null
    }
}
