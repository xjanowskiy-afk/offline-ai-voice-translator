package com.translator.offline

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.translator.offline.downloader.ModelSetupManager
import com.translator.offline.service.TranslatorForegroundService
import com.translator.offline.ui.*
import com.translator.offline.viewmodel.TranslatorViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: TranslatorViewModel by viewModels()
    private var service: TranslatorForegroundService? = null
    private var isBound = false
    private lateinit var setupManager: ModelSetupManager

    private var amplitudeJob: kotlinx.coroutines.Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                val localBinder = binder as TranslatorForegroundService.LocalBinder
                val s = localBinder.getService()
                service = s
                isBound = true

                s.onNewRecognition = { text ->
                    viewModel.onNewSourceRecognized(text)
                }
                s.onNewTranslationToken = { token, isEnd ->
                    viewModel.onTranslationToken(token, isEnd)
                }

                // Передаем живую амплитуду микрофона в ViewModel для красивого эквалайзера
                amplitudeJob?.cancel()
                s.captureEngine?.let { engine ->
                    amplitudeJob = lifecycleScope.launch {
                        engine.audioAmplitude.collect { amp ->
                            viewModel.setAudioAmplitude(amp)
                        }
                    }
                }
            } catch (e: Exception) {
                com.translator.offline.ui.AppLogger.log("Ошибка связывания сервиса: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            amplitudeJob?.cancel()
            amplitudeJob = null
            service = null
            isBound = false
        }
    }

    private fun startAndBindServiceSafely() {
        if (isBound) return
        if (!PermissionAndSystemHelper.hasRecordAudioPermission(this)) return
        try {
            val serviceIntent = Intent(this, TranslatorForegroundService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            com.translator.offline.ui.AppLogger.log("Не удалось запустить ForegroundService: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setupManager = ModelSetupManager(this)

            // Инициализируем нейросети в RAM при старте только если ВСЕ файлы 100% на месте
            if (setupManager.isAllModelsReady()) {
                viewModel.initEngines(setupManager.modelsDir) { success ->
                    com.translator.offline.ui.AppLogger.log("Автозагрузка AI при старте: $success")
                }
            }

            viewModel.onToggleRecordingCallback = { start ->
                val s = service
                if (s != null) {
                    if (start) {
                        s.captureEngine?.start()
                    } else {
                        s.captureEngine?.stop()
                    }
                }
            }

            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var hasAllPermissions by remember {
                            mutableStateOf(
                                PermissionAndSystemHelper.hasRecordAudioPermission(this@MainActivity) &&
                                        PermissionAndSystemHelper.hasNotificationPermission(this@MainActivity) &&
                                        PermissionAndSystemHelper.isIgnoringBatteryOptimizations(this@MainActivity)
                            )
                        }

                    if (!hasAllPermissions) {
                        PermissionsGuardScreen(
                            onAllPermissionsGranted = {
                                hasAllPermissions = true
                                startAndBindServiceSafely()
                            }
                        )
                    } else {
                        var isSetupDone by remember { mutableStateOf(setupManager.isAllModelsReady()) }
                        var isTabletopMode by remember { mutableStateOf(false) }
                        var showAudioSheet by remember { mutableStateOf(false) }
                        var showDebugSheet by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            startAndBindServiceSafely()
                            try {
                                WindowInfoTracker.getOrCreate(this@MainActivity)
                                    .windowLayoutInfo(this@MainActivity)
                                    .collectLatest { layoutInfo ->
                                        val foldingFeature = layoutInfo.displayFeatures
                                            .filterIsInstance<FoldingFeature>()
                                            .firstOrNull()

                                        isTabletopMode = foldingFeature != null &&
                                                foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
                                                foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL
                                    }
                            } catch (e: Throwable) {
                                com.translator.offline.ui.AppLogger.log("WindowInfoTracker error: ${e.message}")
                            }
                        }

                        if (!isSetupDone) {
                            OnboardingSetupScreen(setupManager = setupManager) {
                                viewModel.initEngines(setupManager.modelsDir) {
                                    isSetupDone = true
                                }
                            }
                        } else {
                            if (isTabletopMode) {
                                FoldableSplitLayout(
                                    viewModel = viewModel,
                                    onOpenAudioSettings = { showAudioSheet = true },
                                    onOpenDebugLogs = { showDebugSheet = true }
                                )
                            } else {
                                TranslatorMainScreen(
                                    viewModel = viewModel,
                                    onOpenAudioSettings = { showAudioSheet = true },
                                    onOpenDebugLogs = { showDebugSheet = true }
                                )
                            }
                        }

                    if (showAudioSheet) {
                        val routingMgr = service?.routingManager
                        if (routingMgr != null) {
                            val swap by viewModel.swapLeftRight.collectAsState()
                            AudioModeSelectorSheet(
                                routingManager = routingMgr,
                                swapLeftRight = swap,
                                onToggleSwapEarbuds = {
                                    viewModel.toggleSwapEarbuds()
                                    service?.voicePipeline?.swapLeftRight = viewModel.swapLeftRight.value
                                },
                                onTestSound = { ch ->
                                    service?.voicePipeline?.playTestBeep(ch)
                                },
                                onDismiss = { showAudioSheet = false }
                            )
                        }
                    }

                    if (showDebugSheet) {
                        DebugConsoleSheet(onDismiss = { showDebugSheet = false })
                    }
                    }
                }
            }
        }
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val intent = Intent(this, CrashAndLogActivity::class.java).apply {
                putExtra(CrashAndLogActivity.EXTRA_CRASH_INFO, sw.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
