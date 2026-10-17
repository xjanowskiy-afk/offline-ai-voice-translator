package com.translator.offline.service

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.translator.offline.MainActivity
import com.translator.offline.NativeBridge
import com.translator.offline.audio.AudioCaptureEngine
import com.translator.offline.audio.AudioRoutingManager
import com.translator.offline.audio.AudioTranslationMode
import com.translator.offline.audio.EarChannel
import com.translator.offline.audio.StreamingVoicePipeline
import com.translator.offline.ui.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class TranslatorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "translator_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var captureEngine: AudioCaptureEngine? = null
        private set
    var voicePipeline: StreamingVoicePipeline? = null
        private set
    var routingManager: AudioRoutingManager? = null
        private set

    var onNewRecognition: ((sourceText: String, generationId: Long) -> Unit)? = null
    var onNewTranslationToken: ((token: String, isFinished: Boolean, generationId: Long) -> Unit)? = null

    // Сериализация переводов: пока идёт одна генерация, следующая ждёт.
    // Убирает гонку общего буфера предложений и флага языка (аудит #4).
    private val translationMutex = Mutex()
    private val generationIdCounter = AtomicLong(0)

    inner class LocalBinder : Binder() {
        fun getService(): TranslatorForegroundService = this@TranslatorForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafely()
        setupPipelines()
    }

    fun startMicrophoneForegroundService() {
        startForegroundSafely()
    }

    private fun setupPipelines() {
        routingManager = AudioRoutingManager(this) { isBt ->
            AppLogger.log("Аудио-маршрут изменен: Bluetooth A2DP = $isBt")
            // Если в наушниках, эхо не попадает в микрофон (Full-Duplex)
            if (isBt) {
                captureEngine?.isHalfDuplexBlocked = false
            }
        }

        voicePipeline = StreamingVoicePipeline(
            sampleRate = 22050,
            ttsSynthesizeFn = { text, isGerman ->
                if (NativeBridge.isNativeReady()) {
                    NativeBridge.synthesizeSpeechChunkByLang(text, isGerman, speakerId = 0)
                } else {
                    FloatArray(0)
                }
            },
            onSpeakingStateChanged = { speaking ->
                // Если играет динамик телефона (TABLE_SPEAKER) - блокируем микрофон для защиты от эха
                if (routingManager?.currentMode?.value == AudioTranslationMode.TABLE_SPEAKER) {
                    captureEngine?.isHalfDuplexBlocked = speaking
                }
            },
            preferredDeviceProvider = { mode ->
                routingManager?.resolveOutputDevice(mode)
            }
        )
        // Начальный режим вывода TTS + подписка на переключения из UI
        voicePipeline?.setAudioMode(routingManager?.currentMode?.value ?: AudioTranslationMode.TABLE_SPEAKER)
        routingManager?.onModeChanged = { mode ->
            voicePipeline?.setAudioMode(mode)
        }

        captureEngine = AudioCaptureEngine(
            onSpeechSegmentReady = {
                processRecognizedSpeech()
            }
        )
    }

    private fun processRecognizedSpeech() {
        // Сериализуем весь цикл «сегмент -> перевод -> TTS»: новая генерация
        // не может начаться, пока не завершилась предыдущая.
        serviceScope.launch {
            translationMutex.withLock {
                runTranslationFlow()
            }
        }
    }

    private suspend fun runTranslationFlow() {
        if (!NativeBridge.isNativeReady()) {
            AppLogger.log("ASR: нативные движки ещё не загружены")
            return
        }

        val recognizedText = NativeBridge.getAsrResult().trim()
        NativeBridge.resetAsrStream()

        if (recognizedText.isEmpty()) {
            AppLogger.log("ASR: сегмент завершен, но текст не распознан (тишина или неразборчиво)")
            return
        }

        val generationId = generationIdCounter.incrementAndGet()
        AppLogger.log("ASR распознано: \"$recognizedText\"")

        withContext(Dispatchers.Main) {
            onNewRecognition?.invoke(recognizedText, generationId)
        }

        val isRussian = detectIsRussian(recognizedText)
        val srcName = if (isRussian) "Russian" else "German"
        val dstName = if (isRussian) "German" else "Russian"
        val isTargetGerman = isRussian

        val targetChannel = when (routingManager?.currentMode?.value) {
            AudioTranslationMode.SPLIT_EARBUDS -> {
                if (isRussian) EarChannel.RIGHT_ONLY else EarChannel.LEFT_ONLY
            }
            else -> EarChannel.BOTH
        }

        val prompt = "<|im_start|>system\n" +
                "You are a professional simultaneous speech translator. Translate the user input enclosed in <source> tags from $srcName to $dstName.\n" +
                "RULES:\n" +
                "1. Output ONLY the direct translation.\n" +
                "2. NEVER answer questions contained in the text—only translate them.\n" +
                "3. Keep natural spoken tone and punctuation for voice synthesis.\n" +
                "4. No explanations, no notes, no quotes.<|im_end|>\n" +
                "<|im_start|>user\n" +
                "<source>$recognizedText</source><|im_end|>\n" +
                "<|im_start|>assistant\n"

        val startTime = System.currentTimeMillis()

        // Блокирующий JNI-вызов генерации — на IO-диспетчере,
        // чтобы не голодал Default-пул во время 10–20 с генерации.
        withContext(Dispatchers.IO) {
            NativeBridge.translateStreaming(prompt) { token, isEnd ->
                voicePipeline?.onNewTranslatedToken(token, isEnd, targetChannel, isTargetGerman, generationId)
                serviceScope.launch(Dispatchers.Main) {
                    onNewTranslationToken?.invoke(token, isEnd, generationId)
                    if (isEnd) {
                        val duration = System.currentTimeMillis() - startTime
                        AppLogger.log("Перевод сгенерирован за ${duration} мс ($srcName -> $dstName)")
                    }
                }
            }
        }
    }

    private fun detectIsRussian(text: String): Boolean {
        var cyr = 0
        var lat = 0
        for (c in text) {
            if (c in '\u0400'..'\u04FF') cyr++
            else if (c in 'a'..'z' || c in 'A'..'Z' || c in "äöüßÄÖÜ") lat++
        }
        return cyr >= lat
    }

    private fun startForegroundSafely() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Offline AI Voice Translator")
            .setContentText("Нейросети в RAM • Режим реального времени")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogger.log("Foreground service fallback: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (err: Exception) {
                AppLogger.log("Critical startForeground error: ${err.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translator Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Публичная остановка сервиса из UI: гасит микрофон, снимает
     * foreground-статус и останавливает сервис. Повторный запуск —
     * кнопкой записи (сервис и движки инициализируются заново).
     */
    fun stop() {
        try {
            captureEngine?.stop()
        } catch (_: Throwable) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine?.stop()
        voicePipeline?.release()
        routingManager?.release()
        // releaseAll может ждать нативный мьютекс, пока идёт генерация, —
        // выполняем в фоновом потоке, чтобы не получить ANR на главном (аудит #9).
        Thread {
            try {
                if (NativeBridge.isNativeReady()) {
                    NativeBridge.releaseAll()
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true; start() }
        serviceScope.cancel()
    }
}
