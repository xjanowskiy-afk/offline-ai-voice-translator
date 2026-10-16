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

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            com.translator.offline.ui.AppLogger.log("Ошибка создания AudioRecord VOICE_COMMUNICATION: ${e.message}")
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            com.translator.offline.ui.AppLogger.log("Пробуем резервный AudioSource.MIC...")
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("Ошибка создания AudioRecord MIC: ${e.message}")
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            com.translator.offline.ui.AppLogger.log("КРИТИЧЕСКАЯ ОШИБКА: AudioRecord не инициализирован!")
            record?.release()
            return
        }

        val audioSessionId = record.audioSessionId

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("AcousticEchoCanceler недоступен: ${e.message}")
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("NoiseSuppressor недоступен: ${e.message}")
            }
        }

        audioRecord = record
        try {
            record.startRecording()
            isCapturing = true
            com.translator.offline.ui.AppLogger.log("AudioRecord успешно запущен: 16kHz MONO (AudioSource=${record.audioSource})")
        } catch (e: Exception) {
            com.translator.offline.ui.AppLogger.log("Ошибка startRecording: ${e.message}")
            return
        }

        captureJob = captureScope.launch {
            runCaptureLoop()
        }
    }

    private suspend fun runCaptureLoop() {
        var consecutiveSilenceFrames = 0
        var hasActiveSpeech = false
        var loggedFirstSamples = false

        while (currentCoroutineContext().isActive && isCapturing) {
            val record = audioRecord ?: break

            val readSamples = record.read(pcmShortArray, 0, SAMPLES_PER_FRAME)
            if (readSamples <= 0) {
                if (readSamples < 0) {
                    com.translator.offline.ui.AppLogger.log("AudioRecord.read код ошибки: $readSamples")
                }
                continue
            }

            if (!loggedFirstSamples) {
                loggedFirstSamples = true
                com.translator.offline.ui.AppLogger.log("Микрофон активно считывает PCM аудиоданные ($readSamples сэмплов/фрейм)")
            }

            if (isHalfDuplexBlocked) {
                consecutiveSilenceFrames = 0
                if (NativeBridge.isNativeReady()) {
                    NativeBridge.resetVad()
                }
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

            val isSpeech = if (NativeBridge.isNativeReady()) {
                NativeBridge.processVadChunk(directFloatBuffer, readSamples)
            } else {
                false
            }

            if (isSpeech) {
                if (!hasActiveSpeech) {
                    com.translator.offline.ui.AppLogger.log("VAD: обнаружен человеческий голос (речь)!")
                }
                hasActiveSpeech = true
                consecutiveSilenceFrames = 0
                if (NativeBridge.isNativeReady()) {
                    NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
                }
            } else {
                if (hasActiveSpeech) {
                    consecutiveSilenceFrames++
                    if (consecutiveSilenceFrames <= 5) {
                        if (NativeBridge.isNativeReady()) {
                            NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
                        }
                    }

                    val isEndpoint = if (NativeBridge.isNativeReady()) NativeBridge.isAsrEndpoint() else false
                    if (consecutiveSilenceFrames >= 18 || isEndpoint) {
                        com.translator.offline.ui.AppLogger.log("VAD: завершение речевого сегмента, отправка в ASR...")
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
