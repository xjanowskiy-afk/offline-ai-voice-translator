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

    private var dynamicNoiseFloorRms = 0.005f
    private var isCalibrated = false
    private var calibrationFramesCount = 0
    private var calibrationSumRms = 0.0

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
                com.translator.offline.ui.AppLogger.log("AcousticEchoCanceler успешно активирован (sessionId=$audioSessionId)")
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("AcousticEchoCanceler недоступен: ${e.message}")
            }
        } else {
            com.translator.offline.ui.AppLogger.log("AcousticEchoCanceler: аппаратный AEC не поддерживается на данном источнике")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                com.translator.offline.ui.AppLogger.log("NoiseSuppressor успешно активирован")
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("NoiseSuppressor недоступен: ${e.message}")
            }
        }

        audioRecord = record
        // Сброс параметров динамической калибровки шума
        isCalibrated = false
        calibrationFramesCount = 0
        calibrationSumRms = 0.0
        dynamicNoiseFloorRms = 0.005f

        try {
            record.startRecording()
            isCapturing = true
            com.translator.offline.ui.AppLogger.log("AudioRecord успешно запущен: 16kHz MONO (AudioSource=${record.audioSource})")
        } catch (e: Exception) {
            com.translator.offline.ui.AppLogger.log("Ошибка startRecording: ${e.message}")
            // Освобождаем нативный ресурс, иначе утечка AudioRecord (аудит #10)
            record.release()
            audioRecord = null
            return
        }

        captureJob = captureScope.launch {
            runCaptureLoop()
        }
    }

    private suspend fun runCaptureLoop() {
        var consecutiveSilenceFrames = 0
        var speechDurationFrames = 0
        var segmentTotalRms = 0.0
        var segmentFramesCount = 0
        var hasActiveSpeech = false
        var loggedFirstSamples = false

        // 1 фрейм = 480 сэмплов при 16000 Гц = 30 мс
        // 1 секунда калибровки = 33 фрейма
        val calibrationTargetFrames = 33
        // Рекомендация Музы #3: silence gap 630 мс (21 фрейм) под немецкую речь
        val silenceGapFrames = 21
        // max фраза 4500 мс (150 фреймов) под длинные Nebensätze с глаголом в конце
        val maxSpeechFrames = 150
        // min фраза 1200 мс (40 фреймов) — отсекает щелчки и DTMF, пропуская короткие ответы
        val minSpeechFrames = 40

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
                speechDurationFrames = 0
                hasActiveSpeech = false
                segmentTotalRms = 0.0
                segmentFramesCount = 0
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

            // Рекомендация Музы #2: Динамическая калибровка шума за 1 секунду
            if (!isCalibrated) {
                calibrationSumRms += rms
                calibrationFramesCount++
                if (calibrationFramesCount >= calibrationTargetFrames) {
                    val avgNoise = (calibrationSumRms / calibrationFramesCount).toFloat()
                    dynamicNoiseFloorRms = avgNoise.coerceIn(0.002f, 0.04f)
                    isCalibrated = true
                    com.translator.offline.ui.AppLogger.log("VAD авто-калибровка завершена: базовый шум RMS = %.4f".format(dynamicNoiseFloorRms))
                }
            }

            val isVadDetected = if (NativeBridge.isNativeReady()) {
                NativeBridge.processVadChunk(directFloatBuffer, readSamples)
            } else {
                false
            }

            // Фильтрация шума: речь считается активной, если VAD сработал И амплитуда выше динамического шума
            val speechThreshold = (dynamicNoiseFloorRms * 1.8f).coerceAtLeast(0.004f)
            val isSpeech = isVadDetected && (rms >= speechThreshold)

            if (isSpeech) {
                if (!hasActiveSpeech) {
                    com.translator.offline.ui.AppLogger.log("VAD: обнаружен человеческий голос (речь)!")
                    hasActiveSpeech = true
                    segmentTotalRms = 0.0
                    segmentFramesCount = 0
                    speechDurationFrames = 0
                }
                consecutiveSilenceFrames = 0
                speechDurationFrames++
                segmentTotalRms += rms
                segmentFramesCount++

                if (NativeBridge.isNativeReady()) {
                    NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
                }

                // Предохранитель от бесконечной фразы (4500 мс)
                if (speechDurationFrames >= maxSpeechFrames) {
                    com.translator.offline.ui.AppLogger.log("VAD: достигнут предел фразы (4500 мс), сброс в ASR...")
                    hasActiveSpeech = false
                    consecutiveSilenceFrames = 0
                    speechDurationFrames = 0
                    onSpeechSegmentReady()
                }
            } else {
                if (hasActiveSpeech) {
                    consecutiveSilenceFrames++
                    speechDurationFrames++
                    segmentTotalRms += rms
                    segmentFramesCount++

                    // Захватываем легкий хвостик речи
                    if (consecutiveSilenceFrames <= 6) {
                        if (NativeBridge.isNativeReady()) {
                            NativeBridge.feedAudioToAsr(directFloatBuffer, readSamples)
                        }
                    }

                    val isEndpoint = if (NativeBridge.isNativeReady()) NativeBridge.isAsrEndpoint() else false
                    
                    // Рекомендация Музы #3: тишина 630 мс (21 фрейм) или ASR endpoint
                    if (consecutiveSilenceFrames >= silenceGapFrames || isEndpoint) {
                        val avgSegmentRms = if (segmentFramesCount > 0) (segmentTotalRms / segmentFramesCount).toFloat() else 0.0f
                        val minValidThreshold = dynamicNoiseFloorRms * 1.3f

                        // Рекомендация Музы #2: Отсев пустых ложных срабатываний
                        if (speechDurationFrames >= minSpeechFrames && avgSegmentRms >= minValidThreshold) {
                            com.translator.offline.ui.AppLogger.log("VAD: сегмент подтвержден (RMS=%.4f, frames=%d), отправка в ASR...".format(avgSegmentRms, speechDurationFrames))
                            onSpeechSegmentReady()
                        } else {
                            com.translator.offline.ui.AppLogger.log("VAD: отсев шума/щелчка (длина %d фреймов, RMS=%.4f < %.4f)".format(speechDurationFrames, avgSegmentRms, minValidThreshold))
                            if (NativeBridge.isNativeReady()) {
                                NativeBridge.resetAsrStream()
                            }
                        }

                        hasActiveSpeech = false
                        consecutiveSilenceFrames = 0
                        speechDurationFrames = 0
                        segmentTotalRms = 0.0
                        segmentFramesCount = 0
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
