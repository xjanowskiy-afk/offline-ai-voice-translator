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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupManager = ModelSetupManager(this)

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
                                val serviceIntent = Intent(this@MainActivity, TranslatorForegroundService::class.java)
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                            }
                        )
                    } else {
                        var isSetupDone by remember { mutableStateOf(setupManager.isAllModelsReady()) }
                        var isTabletopMode by remember { mutableStateOf(false) }
                        var showAudioSheet by remember { mutableStateOf(false) }
                        var showDebugSheet by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            if (!isBound) {
                                val serviceIntent = Intent(this@MainActivity, TranslatorForegroundService::class.java)
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                            }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
