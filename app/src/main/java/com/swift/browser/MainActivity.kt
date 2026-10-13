package com.swift.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.swift.browser.browserengine.splash.FirstLaunchManager
import com.swift.browser.browserengine.splash.SplashScreenEngine
import com.swift.browser.browserengine.splash.SplashRoute
import com.swift.browser.browserengine.splash.OnboardingFlow
import com.swift.browser.browserengine.splash.SplashFlowContainer
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.swift.browser.browserengine.ui.BrowserScreen
import com.swift.browser.browserengine.BrowserViewModel
import com.swift.browser.browserengine.BrowserViewModelFactory
import com.swift.browser.data.BrowserDatabase
import com.swift.browser.data.BrowserRepository
import com.swift.browser.databasecore.PreferenceManager
import com.swift.browser.ui.theme.MyApplicationTheme

class MainActivity : FragmentActivity() {
    private lateinit var viewModel: BrowserViewModel

    // Lightweight first-frame preference access. Construction is deferred until Compose actually
    // needs the theme state; it does not create the database or BrowserViewModel.
    private val startupPreferences: PreferenceManager by lazy(LazyThreadSafetyMode.NONE) {
        PreferenceManager.getInstance(applicationContext)
    }

    private val pendingPermissionCallbacks = java.util.concurrent.ConcurrentHashMap<String, (com.swift.browser.permissionengine.AndroidPermissionResult) -> Unit>()

    private val runtimePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        val anyDenied = result.values.any { !it }
        val androidResult = com.swift.browser.permissionengine.AndroidPermissionResult(
            granted = allGranted,
            denied = anyDenied,
            permanentlyDenied = false,
            individuallyGrantedPermissions = result
        )
        val callbacks = pendingPermissionCallbacks.values.toList()
        pendingPermissionCallbacks.clear()
        callbacks.forEach { cb ->
            runCatching { cb.invoke(androidResult) }
        }
    }

    private val systemPermissionRequester = com.swift.browser.permissionengine.AndroidRuntimePermissionManager.SystemPermissionRequester { requestId, permissions, callback ->
        if (permissions.isNotEmpty()) {
            pendingPermissionCallbacks[requestId] = callback
            runtimePermissionLauncher.launch(permissions.toTypedArray())
        } else {
            callback(
                com.swift.browser.permissionengine.AndroidPermissionResult(
                    granted = true,
                    denied = false,
                    permanentlyDenied = false,
                    individuallyGrantedPermissions = emptyMap()
                )
            )
        }
    }

    private val voicePermissionsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true
        android.util.Log.i("MainActivity", "Permissions result. RECORD_AUDIO: $recordAudioGranted, CAMERA: $cameraGranted")
    }

    private var pendingMediaProjectionCallback: ((Int, Intent?) -> Unit)? = null
    private var pendingMediaProjectionRequestId: String? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val data = result.data
        android.util.Log.i("MainActivity", "MediaProjection activity result: resultCode=$resultCode, data=$data")
        pendingMediaProjectionCallback?.invoke(resultCode, data)
        pendingMediaProjectionCallback = null
        pendingMediaProjectionRequestId = null
    }

    private val mediaProjectionHostRequester = object : com.swift.browser.browserengine.screencapture.ScreenCaptureManager.MediaProjectionHostRequester {
        override fun requestMediaProjectionConsent(requestId: String, onResult: (resultCode: Int, resultData: Intent?) -> Unit) {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
            if (mediaProjectionManager != null) {
                pendingMediaProjectionRequestId = requestId
                pendingMediaProjectionCallback = onResult
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(intent)
            } else {
                onResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }

        override fun startForegroundService(resultCode: Int, resultData: Intent) {
            com.swift.browser.screencapture.ScreenCaptureForegroundService.startService(
                this@MainActivity,
                resultCode,
                resultData
            )
        }

        override fun stopForegroundService() {
            com.swift.browser.screencapture.ScreenCaptureForegroundService.stopService(this@MainActivity)
        }
    }

    private val firstFrameReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isInitializingViewModel = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun initializeBrowserViewModelIfNeeded() {
        if (::viewModel.isInitialized) return
        android.util.Log.d("StartupTiming", "VIEWMODEL_INIT_START: ${System.currentTimeMillis()}")

        val db = BrowserDatabase.getDatabase(applicationContext)
        val repository = BrowserRepository(db)
        val prefs = startupPreferences
        val factory = BrowserViewModelFactory(application, repository, prefs)

        viewModel = ViewModelProvider(this, factory)[BrowserViewModel::class.java]

        android.util.Log.d("StartupTiming", "VIEWMODEL_INIT_COMPLETE: ${System.currentTimeMillis()}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("StartupTiming", "MAIN_ACTIVITY_ON_CREATE: ${System.currentTimeMillis()}")
        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "MAIN_ACTIVITY_START",
            className = "MainActivity",
            methodName = "onCreate",
            success = true
        )
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        com.swift.browser.permissionengine.AndroidRuntimePermissionManager.registerSystemRequester(systemPermissionRequester)
        com.swift.browser.browserengine.screencapture.ScreenCaptureManager.registerHostRequester(mediaProjectionHostRequester)

        splashScreen.setKeepOnScreenCondition { !firstFrameReady.get() }
        android.util.Log.d("StartupTiming", "SYSTEM_SPLASH_READY: ${System.currentTimeMillis()}")

        val firstLaunchManager = FirstLaunchManager(this)
        val splashScreenEngine = SplashScreenEngine(this, firstLaunchManager)
        val initialRoute = if (firstLaunchManager.isFirstLaunch()) SplashRoute.Onboarding else SplashRoute.SplashOnly
        val pendingLaunchIntent = intent

        // BrowserViewModel/Database construction is intentionally deferred until after the first
        // Compose frame. This keeps the first frame out of the database/repository startup path.
        // The existing ViewModel remains activity-scoped and is initialized exactly once.

        com.swift.browser.analyticscore.StartupTracker.recordStage(
            stage = "SET_CONTENT",
            className = "MainActivity",
            methodName = "setContent",
            success = true
        )
        android.util.Log.d("StartupTiming", "COMPOSE_SET_CONTENT: ${System.currentTimeMillis()}")

        setContent {
            val appThemeMode = startupPreferences.appTheme.collectAsStateWithLifecycle().value
            val startupState by com.swift.browser.browserengine.StartupCoordinator.instance.startupState.collectAsStateWithLifecycle()
            val startupError by com.swift.browser.browserengine.StartupCoordinator.instance.startupError.collectAsStateWithLifecycle()
            var currentRoute by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initialRoute) }
            var browserCoreReady by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(::viewModel.isInitialized) }
            var splashCompleted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos {
                    firstFrameReady.set(true)
                    android.util.Log.d(
                        "StartupTiming",
                        "FIRST_COMPOSE_FRAME: ${System.currentTimeMillis()}"
                    )
                    com.swift.browser.browserengine.StartupCoordinator.instance.onFirstFrameRendered()
                    com.swift.browser.analyticscore.StartupTracker.recordStage(
                        stage = "FIRST_FRAME",
                        className = "MainActivity",
                        methodName = "withFrameNanos",
                        success = true
                    )
                }

                val webViewReady = (application as? BrowserApplication)?.completeWebViewStartupAfterFirstFrame() ?: true
                if (isInitializingViewModel.compareAndSet(false, true)) {
                    initializeBrowserViewModelIfNeeded()
                    browserCoreReady = true
                }

                if (webViewReady && com.swift.browser.browserengine.StartupCoordinator.instance.startupState.value != com.swift.browser.browserengine.StartupState.FAILED) {
                    com.swift.browser.browserengine.StartupCoordinator.instance.markReady()
                }

                if (pendingLaunchIntent != null && ::viewModel.isInitialized) {
                    viewModel.handleIncomingIntent(pendingLaunchIntent)
                }

                if (splashCompleted || !firstLaunchManager.isFirstLaunch()) {
                    currentRoute = SplashRoute.Home
                }

                com.swift.browser.analyticscore.StartupTracker.recordStage(
                    stage = "COMPOSE_READY",
                    className = "MainActivity",
                    methodName = "LaunchedEffect",
                    success = true
                )
                com.swift.browser.analyticscore.StartupTracker.recordStage(
                    stage = "STARTUP_COMPLETE",
                    className = "MainActivity",
                    methodName = "onCreate.completed",
                    success = true
                )
            }
            
            MyApplicationTheme(appThemeMode = appThemeMode) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (startupState == com.swift.browser.browserengine.StartupState.FAILED) {
                        com.swift.browser.browserengine.splash.BrowserRecoveryScreen(
                            error = startupError ?: BrowserApplication.webViewStartupError,
                            onRetry = {
                                (application as? BrowserApplication)?.retryWebViewStartup()
                                com.swift.browser.browserengine.StartupCoordinator.instance.retryStartup()
                                isInitializingViewModel.set(false)
                                initializeBrowserViewModelIfNeeded()
                            },
                            onContinue = {
                                currentRoute = SplashRoute.Home
                            }
                        )
                    } else if (currentRoute != SplashRoute.Home) {
                        SplashFlowContainer(
                            engine = splashScreenEngine,
                            firstLaunchManager = firstLaunchManager,
                            onComplete = {
                                splashCompleted = true
                                currentRoute = SplashRoute.Home
                            }
                        )
                    } else if (::viewModel.isInitialized) {
                        BrowserScreen(
                            viewModel = viewModel,
                            modifier = Modifier
                        )
                    } else {
                        com.swift.browser.browserengine.splash.SplashScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::viewModel.isInitialized) {
            viewModel.handleIncomingIntent(intent)
        }
    }

    override fun onPause() {
        com.swift.browser.audioengine.WebMediaPlaybackController.setBgActive(true)
        com.swift.browser.browserengine.BrowserEngine.lifecycleEngine.onPause()
        if (::viewModel.isInitialized) {
            viewModel.onAppPause()
            viewModel.captureActiveVideoState()
            viewModel.saveTabsState()
            val tabId = viewModel.uiState.value.activeTabId
            if (tabId.isNotEmpty()) {
                com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.onWebViewPaused(tabId)
            }
        }
        super.onPause()
    }

    override fun onStop() {
        if (::viewModel.isInitialized) {
            viewModel.saveTabsState()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        com.swift.browser.audioengine.WebMediaPlaybackController.setBgActive(false)
        com.swift.browser.browserengine.BrowserEngine.lifecycleEngine.onResume()
        if (::viewModel.isInitialized) {
            val tabId = viewModel.uiState.value.activeTabId
            if (tabId.isNotEmpty()) {
                com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.onWebViewResumed(tabId)
            }
        }
        
        if (::viewModel.isInitialized) {
            viewModel.onAppResume()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::viewModel.isInitialized) {
            // viewModel.clearWebViewCache(applicationContext)
        }
    }

    override fun onDestroy() {
        if (::viewModel.isInitialized) {
            viewModel.onAppDestroy()
        }
        com.swift.browser.permissionengine.AndroidRuntimePermissionManager.unregisterSystemRequester(systemPermissionRequester)
        com.swift.browser.browserengine.screencapture.ScreenCaptureManager.unregisterHostRequester(mediaProjectionHostRequester)
        com.swift.browser.browserengine.screencapture.ScreenCaptureManager.onActivityDestroyed()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE && ::viewModel.isInitialized) {
            // viewModel.clearWebViewCache(applicationContext)
        }
    }
}
