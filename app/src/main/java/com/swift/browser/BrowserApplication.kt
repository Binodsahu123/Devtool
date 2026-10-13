package com.swift.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class BrowserApplication : Application() {
    enum class WebViewStartupState {
        NOT_STARTED,
        STARTING,
        READY,
        FAILED
    }

    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private val webViewStartupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Orion-WebView-Startup").apply {
            isDaemon = true
        }
    }

    companion object {
        @Volatile
        var webViewStartupState: WebViewStartupState = WebViewStartupState.NOT_STARTED
            private set

        @Volatile
        var webViewStartupError: Throwable? = null
            private set

        private val _webViewStartupStateFlow = MutableStateFlow(WebViewStartupState.NOT_STARTED)
        val webViewStartupStateFlow: StateFlow<WebViewStartupState> = _webViewStartupStateFlow.asStateFlow()

        @Volatile
        private var webViewStartupRequested = false

        fun isWebViewReady(): Boolean = webViewStartupState == WebViewStartupState.READY
        fun isWebViewFailed(): Boolean = webViewStartupState == WebViewStartupState.FAILED
    }

    fun startWebViewStartupEarly() {
        if (webViewStartupRequested && webViewStartupState != WebViewStartupState.NOT_STARTED) return
        webViewStartupRequested = true
        webViewStartupState = WebViewStartupState.STARTING
        _webViewStartupStateFlow.value = WebViewStartupState.STARTING
        webViewStartupError = null

        android.util.Log.d("StartupTiming", "WEBVIEW_START_REQUESTED: ${System.currentTimeMillis()}")

        try {
            val config = WebViewStartUpConfig.Builder(webViewStartupExecutor)
                .setShouldRunUiThreadStartUpTasks(true)
                .build()

            WebViewCompat.startUpWebView(
                applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        webViewStartupState = WebViewStartupState.READY
                        _webViewStartupStateFlow.value = WebViewStartupState.READY
                        android.util.Log.d(
                            "StartupTiming",
                            "WEBVIEW_START_READY: ${System.currentTimeMillis()} ui=${result.getTotalTimeInUiThreadMillis() ?: -1}ms"
                        )
                    }

                    override fun onError(error: WebViewStartupException) {
                        webViewStartupState = WebViewStartupState.FAILED
                        webViewStartupError = error
                        _webViewStartupStateFlow.value = WebViewStartupState.FAILED
                        android.util.Log.e(
                            "StartupTiming",
                            "WEBVIEW_START_FAILED: ${System.currentTimeMillis()} class=${error.javaClass.name}\n${android.util.Log.getStackTraceString(error)}"
                        )
                        com.swift.browser.browserengine.StartupCoordinator.instance.markFailed(error)
                    }
                }
            )
        } catch (t: Throwable) {
            webViewStartupState = WebViewStartupState.FAILED
            webViewStartupError = t
            _webViewStartupStateFlow.value = WebViewStartupState.FAILED
            android.util.Log.e(
                "StartupTiming",
                "WEBVIEW_START_FAILED: ${System.currentTimeMillis()} class=${t.javaClass.name}\n${android.util.Log.getStackTraceString(t)}"
            )
            com.swift.browser.browserengine.StartupCoordinator.instance.markFailed(t)
        }
    }

    fun retryWebViewStartup() {
        webViewStartupRequested = false
        startWebViewStartupEarly()
    }

    suspend fun completeWebViewStartupAfterFirstFrame(timeoutMs: Long = 3000L): Boolean {
        if (webViewStartupState == WebViewStartupState.READY) return true
        if (webViewStartupState == WebViewStartupState.FAILED) return false

        return try {
            withTimeout(timeoutMs) {
                _webViewStartupStateFlow.first { it == WebViewStartupState.READY || it == WebViewStartupState.FAILED }
                webViewStartupState == WebViewStartupState.READY
            }
        } catch (e: Exception) {
            webViewStartupState == WebViewStartupState.READY
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("StartupTiming", "APP_ON_CREATE: ${System.currentTimeMillis()}")
        com.swift.browser.browserengine.StartupCoordinator.instance.onApplicationStarted()

        // Start non-UI WebView initialization before optional SDK/engine work.
        startWebViewStartupEarly()

        // Initialize StartupTracker and Analytics Core
        com.swift.browser.analyticscore.StartupTracker.init(this)
        com.swift.browser.analyticscore.AnalyticsCore.startupAnalytics.markAppLaunchStart()
        com.swift.browser.analyticscore.AnalyticsCore.startSession()
        com.swift.browser.analyticscore.AnalyticsCore.recordStartupPhase("APPLICATION_ON_CREATE", com.swift.browser.analyticscore.StartupType.COLD)
        com.swift.browser.analyticscore.AnalyticsCore.crashAnalytics.attachUncaughtExceptionHandler()

        // Initialize Browser Engine core lifecycle (in-memory)
        try {
            com.swift.browser.analyticscore.StartupTracker.recordStage(
                stage = "BROWSER_ENGINE_START",
                className = "BrowserEngine",
                methodName = "initialize",
                success = true
            )
            com.swift.browser.browserengine.BrowserEngine.initialize(this)
            android.util.Log.d("StartupTiming", "BROWSER_ENGINE_READY: ${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            com.swift.browser.analyticscore.StartupTracker.recordStage(
                stage = "BROWSER_ENGINE_START",
                className = "BrowserEngine",
                methodName = "initialize",
                success = false,
                error = t
            )
            com.swift.browser.browserengine.StartupCoordinator.instance.markFailed(t)
        }

        try {
            com.swift.browser.analyticscore.StartupTracker.recordStage(
                stage = "PERMISSION_ENGINE_START",
                className = "PermissionEngineProvider",
                methodName = "initialize",
                success = true
            )
            com.swift.browser.permissionengine.PermissionEngineProvider.initialize(this)
            com.swift.browser.desktopengine.api.DesktopEngineProvider.init(this)
        } catch (t: Throwable) {
            com.swift.browser.analyticscore.StartupTracker.recordStage(
                stage = "PERMISSION_ENGINE_START",
                className = "PermissionEngineProvider",
                methodName = "initialize",
                success = false,
                error = t
            )
        }

        // Run heavy/database initializations asynchronously on background thread using applicationScope
        applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.swift.browser.historyengine.api.HistoryEngineProvider.initialize(
                    com.swift.browser.data.BrowserDatabase.getDatabase(applicationContext).historyDao()
                )
                com.swift.browser.analyticscore.StartupTracker.recordStage(
                    stage = "HISTORY_ENGINE_START",
                    className = "HistoryEngineProvider",
                    methodName = "initialize",
                    success = true
                )
            } catch (t: Throwable) {
                com.swift.browser.analyticscore.StartupTracker.recordStage(
                    stage = "HISTORY_ENGINE_START",
                    className = "HistoryEngineProvider",
                    methodName = "initialize",
                    success = false,
                    error = t
                )
            }

            try {
                val cacheDir = cacheDir
                val webViewCacheDirs = listOf(
                    "WebView/Default/HTTP Cache/Code Cache/js",
                    "WebView/Default/HTTP Cache/Code Cache/wasm",
                    "WebView/Default/HTTP Cache/index-dir",
                    "WebView/Default/GPUCache",
                    "WebView/Default/Service Worker/CacheStorage",
                    "WebView/Default/Service Worker/ScriptCache"
                )
                for (dirRelPath in webViewCacheDirs) {
                    val dir = File(cacheDir, dirRelPath)
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                com.swift.browser.bookmarkengine.api.BookmarkEngineProvider.getEngine(this@BrowserApplication, applicationScope)
                com.swift.browser.vpnengine.api.VpnEngineProvider.api.init(this@BrowserApplication)
                com.swift.browser.browserengine.SwiftDeveloperEngine.initFromPrefs(this@BrowserApplication)
                com.swift.browser.notificationengine.api.NotificationEngineProvider.api.init(this@BrowserApplication)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()
            saveErrorLog(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveErrorLog(throwable: Throwable) {
        try {
            com.swift.browser.analyticscore.AnalyticsCore.crashAnalytics.recordCrash(throwable, "Fatal application crash", isFatal = true)
            val file = File(filesDir, "crash_log.txt")
            file.writeText("${Date()}\n${throwable.stackTraceToString()}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
