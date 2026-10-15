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

    private val _sourceLanguage = MutableStateFlow("ru")
    val sourceLanguage = _sourceLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow("de")
    val targetLanguage = _targetLanguage.asStateFlow()

    private val _swapLeftRight = MutableStateFlow(false)
    val swapLeftRight = _swapLeftRight.asStateFlow()

    var onToggleRecordingCallback: ((Boolean) -> Unit)? = null

    fun initEngines(modelsDir: File, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.log("Инициализация C++ нейросетей в RAM...")
            val vadOk = NativeBridge.initVad(File(modelsDir, "silero_vad.onnx").absolutePath)
            val asrOk = NativeBridge.initAsr(
                File(modelsDir, "encoder.onnx").absolutePath,
                File(modelsDir, "decoder.onnx").absolutePath,
                File(modelsDir, "joiner.onnx").absolutePath,
                File(modelsDir, "tokens.txt").absolutePath
            )
            val llmOk = NativeBridge.initLlm(File(modelsDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf").absolutePath)
            val ttsOk = NativeBridge.initTtsDual(
                File(modelsDir, "ru_RU-irina-medium.onnx").absolutePath,
                File(modelsDir, "ru_RU-irina-medium.onnx.json").absolutePath,
                File(modelsDir, "de_DE-thorsten-high.onnx").absolutePath,
                File(modelsDir, "de_DE-thorsten-high.onnx.json").absolutePath,
                File(modelsDir, "espeak-ng-data").absolutePath
            )

            val allOk = vadOk && asrOk && llmOk && ttsOk
            AppLogger.log("Результат инициализации движков: $allOk (VAD=$vadOk, ASR=$asrOk, LLM=$llmOk, TTS=$ttsOk)")
            launch(Dispatchers.Main) {
                onDone(allOk)
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
