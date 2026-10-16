package com.translator.offline.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translator.offline.NativeBridge
import com.translator.offline.audio.AudioRoutingManager
import com.translator.offline.audio.AudioTranslationMode
import com.translator.offline.audio.EarChannel
import com.translator.offline.ui.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class MessageLog(
    val sourceText: String,
    val translatedText: String,
    val isUser: Boolean
)

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val _messages = MutableStateFlow<List<MessageLog>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _currentStreamingText = MutableStateFlow("")
    val currentStreamingText = _currentStreamingText.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0.0f)
    val audioAmplitude = _audioAmplitude.asStateFlow()

    private val _isEnginesReady = MutableStateFlow(false)
    val isEnginesReady = _isEnginesReady.asStateFlow()

    private val _sourceLanguage = MutableStateFlow("ru")
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("de")
    val targetLanguage = _targetLanguage.asStateFlow()

    private val _swapLeftRight = MutableStateFlow(false)
    val swapLeftRight = _swapLeftRight.asStateFlow()

    var onToggleRecordingCallback: ((Boolean) -> Unit)? = null

    fun setAudioAmplitude(amp: Float) {
        _audioAmplitude.value = amp
    }

    fun initEngines(modelsDir: File, onDone: (Boolean) -> Unit) {
        if (_isEnginesReady.value) {
            AppLogger.log("Нейросети уже загружены в RAM, повторная инициализация пропущена.")
            onDone(true)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                AppLogger.log("Инициализация C++ нейросетей в RAM...")

                val vadFile = File(modelsDir, "silero_vad.onnx")
                val encFile = File(modelsDir, "encoder.onnx")
                val decFile = File(modelsDir, "decoder.onnx")
                val joinFile = File(modelsDir, "joiner.onnx")
                val tokFile = File(modelsDir, "tokens.txt")
                val llmFile = File(modelsDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
                val ruTts = File(modelsDir, "ru_RU-irina-medium.onnx")
                val ruJson = File(modelsDir, "ru_RU-irina-medium.onnx.json")
                val deTts = File(modelsDir, "de_DE-thorsten-high.onnx")
                val deJson = File(modelsDir, "de_DE-thorsten-high.onnx.json")
                val espeakDir = File(modelsDir, "espeak-ng-data")

                val missing = mutableListOf<String>()
                if (!vadFile.exists() || vadFile.length() < 1000) missing.add("silero_vad.onnx")
                if (!encFile.exists() || encFile.length() < 1000) missing.add("encoder.onnx")
                if (!decFile.exists() || decFile.length() < 1000) missing.add("decoder.onnx")
                if (!joinFile.exists() || joinFile.length() < 1000) missing.add("joiner.onnx")
                if (!tokFile.exists() || tokFile.length() < 100) missing.add("tokens.txt")
                if (!llmFile.exists() || llmFile.length() < 100000) missing.add("qwen2.5-1.5b-instruct-q4_k_m.gguf")
                if (!ruTts.exists() || ruTts.length() < 1000) missing.add("ru_RU-irina-medium.onnx")
                if (!ruJson.exists() || ruJson.length() < 100) missing.add("ru_RU-irina-medium.onnx.json")
                if (!deTts.exists() || deTts.length() < 1000) missing.add("de_DE-thorsten-high.onnx")
                if (!deJson.exists() || deJson.length() < 100) missing.add("de_DE-thorsten-high.onnx.json")
                if (!espeakDir.exists() || !espeakDir.isDirectory || espeakDir.list().isNullOrEmpty()) missing.add("espeak-ng-data (папка)")

                if (missing.isNotEmpty()) {
                    AppLogger.log("ОШИБКА: отсутствуют или повреждены файлы: ${missing.joinToString(", ")}")
                    launch(Dispatchers.Main) {
                        _isEnginesReady.value = false
                        onDone(false)
                    }
                    return@launch
                }

                if (!NativeBridge.loadLibrariesSafely()) {
                    val err = NativeBridge.getLoadError() ?: "Неизвестная ошибка загрузки .so библиотек"
                    AppLogger.log("ОШИБКА C++ библиотек: $err")
                    launch(Dispatchers.Main) {
                        _isEnginesReady.value = false
                        onDone(false)
                    }
                    return@launch
                }

                val vadOk = NativeBridge.initVad(vadFile.absolutePath)
                val asrOk = NativeBridge.initAsr(
                    encFile.absolutePath,
                    decFile.absolutePath,
                    joinFile.absolutePath,
                    tokFile.absolutePath
                )
                val llmOk = NativeBridge.initLlm(llmFile.absolutePath)
                val ttsOk = NativeBridge.initTtsDual(
                    ruTts.absolutePath,
                    ruJson.absolutePath,
                    deTts.absolutePath,
                    deJson.absolutePath,
                    espeakDir.absolutePath
                )

                val allOk = vadOk && asrOk && llmOk && ttsOk
                AppLogger.log("Результат инициализации движков: $allOk (VAD=$vadOk, ASR=$asrOk, LLM=$llmOk, TTS=$ttsOk)")
                launch(Dispatchers.Main) {
                    _isEnginesReady.value = allOk
                    onDone(allOk)
                }
            } catch (t: Throwable) {
                AppLogger.log("Критическая ошибка при инициализации AI движков: ${t.message}")
                launch(Dispatchers.Main) {
                    _isEnginesReady.value = false
                    onDone(false)
                }
            }
        }
    }

    fun swapLanguages() {
        val oldSrc = _sourceLanguage.value
        _sourceLanguage.value = _targetLanguage.value
        _targetLanguage.value = oldSrc
        AppLogger.log("Направление изменено: ${_sourceLanguage.value.uppercase()} -> ${_targetLanguage.value.uppercase()}")
    }

    fun toggleRecording(start: Boolean) {
        _isRecording.value = start
        onToggleRecordingCallback?.invoke(start)
        AppLogger.log("Запись микрофона: $start")
    }

    fun toggleSwapEarbuds() {
        _swapLeftRight.value = !_swapLeftRight.value
        AppLogger.log("Смена L/R каналов наушников: ${_swapLeftRight.value}")
    }

    fun onNewSourceRecognized(text: String) {
        _currentStreamingText.value = ""
        val list = _messages.value.toMutableList().apply {
            add(MessageLog(sourceText = text, translatedText = "", isUser = true))
        }
        _messages.value = list
    }

    fun onTranslationToken(token: String, isFinished: Boolean) {
        if (isFinished) {
            _currentStreamingText.value = ""
        } else {
            _currentStreamingText.value += token
            val list = _messages.value.toMutableList()
            if (list.isNotEmpty()) {
                val last = list.last()
                list[list.lastIndex] = last.copy(translatedText = last.translatedText + token)
                _messages.value = list
            }
        }
    }
}
