package com.translator.offline.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioTranslationMode {
    TABLE_SPEAKER,
    SOLO_PROMPTER,
    @Deprecated("Не поддерживается: один AudioTrack не может выводить на два устройства одновременно")
    HYBRID_SPEAKER_AND_EARBUD,
    SPLIT_EARBUDS
}

class AudioRoutingManager(
    private val context: Context,
    private val onOutputRouteChanged: (isBluetoothA2dp: Boolean) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected = _isBluetoothConnected.asStateFlow()

    private val _currentMode = MutableStateFlow(AudioTranslationMode.TABLE_SPEAKER)
    val currentMode = _currentMode.asStateFlow()

    /** Вызывается при смене режима из UI (нужен пайплайну для setPreferredDevice). */
    var onModeChanged: ((AudioTranslationMode) -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkBluetoothStatus()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkBluetoothStatus()
        }
    }

    init {
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            checkBluetoothStatus()
        } catch (_: Throwable) {}
    }

    private fun checkBluetoothStatus() {
        try {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasBt = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == 26 // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31+)
            }
            _isBluetoothConnected.value = hasBt
            onOutputRouteChanged(hasBt)
        } catch (_: Throwable) {
            _isBluetoothConnected.value = false
            onOutputRouteChanged(false)
        }
    }

    fun setMode(mode: AudioTranslationMode) {
        _currentMode.value = mode
        applyRoutingForCurrentMode()
        onModeChanged?.invoke(mode)
    }

    /**
     * Устройство вывода для AudioTrack.setPreferredDevice() под выбранный режим.
     * Возвращает null, если подходящего устройства нет — тогда трек использует
     * системный маршрут по умолчанию.
     */
    fun resolveOutputDevice(mode: AudioTranslationMode): AudioDeviceInfo? {
        return try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            when (mode) {
                AudioTranslationMode.TABLE_SPEAKER ->
                    outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                AudioTranslationMode.SOLO_PROMPTER, AudioTranslationMode.SPLIT_EARBUDS ->
                    outputs.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                AudioTranslationMode.HYBRID_SPEAKER_AND_EARBUD -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speakerDevice != null) {
                audioManager.setCommunicationDevice(speakerDevice)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        onOutputRouteChanged(false)
    }

    fun routeToBluetoothA2dp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == 26 // AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (btDevice != null) {
                audioManager.setCommunicationDevice(btDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        onOutputRouteChanged(_isBluetoothConnected.value)
    }

    private fun applyRoutingForCurrentMode() {
        when (_currentMode.value) {
            AudioTranslationMode.TABLE_SPEAKER -> routeToSpeaker()
            AudioTranslationMode.SOLO_PROMPTER,
            AudioTranslationMode.SPLIT_EARBUDS -> routeToBluetoothA2dp()
            AudioTranslationMode.HYBRID_SPEAKER_AND_EARBUD -> {
            }
        }
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }
}
