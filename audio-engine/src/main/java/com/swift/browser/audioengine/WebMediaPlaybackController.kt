package com.swift.browser.audioengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.swift.browser.permissionengine.PermissionEngineApi
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

object WebMediaPlaybackController {
    private const val TAG = "WebMediaPlaybackController"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeWebViews = ConcurrentHashMap<String, WeakReference<WebView>>()

    @Volatile
    private var activeMediaWebViewStrongRef: WebView? = null

    @Volatile
    var activeTabId: String? = null
        private set

    @Volatile
    var isMediaPlaying: Boolean = false
        private set

    @Volatile
    var currentTitle: String = "Web Media"
        private set

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var currentPositionMs: Long = 0L
        private set

    @Volatile
    var durationMs: Long = 0L
        private set

    fun registerWebView(tabId: String, webView: WebView) {
        if (tabId.isBlank()) return
        activeWebViews[tabId] = WeakReference(webView)
        Log.d(TAG, "Registered WebView for tabId: $tabId")
    }

    fun unregisterWebView(tabId: String) {
        if (tabId.isBlank()) return
        val removedRef = activeWebViews.remove(tabId)
        if (removedRef?.get() == activeMediaWebViewStrongRef || activeTabId == tabId) {
            activeMediaWebViewStrongRef = null
            if (activeTabId == tabId) {
                activeTabId = null
                isMediaPlaying = false
            }
        }
        Log.d(TAG, "Unregistered WebView for tabId: $tabId")
    }

    fun updateMediaState(
        tabId: String,
        webView: WebView?,
        title: String,
        playing: Boolean,
        url: String,
        posMs: Long = 0L,
        durMs: Long = 0L
    ) {
        if (tabId.isNotBlank()) {
            activeTabId = tabId
            if (webView != null) {
                activeWebViews[tabId] = WeakReference(webView)
            }
        }
        isMediaPlaying = playing
        val targetWebView = webView ?: activeWebViews[tabId]?.get() ?: getAnyActiveWebView()
        if (playing && targetWebView != null) {
            activeMediaWebViewStrongRef = targetWebView
        } else if (!playing && targetWebView == activeMediaWebViewStrongRef) {
            // Keep strong ref while active tab is playing, clear on stop
            activeMediaWebViewStrongRef = null
        }
        if (title.isNotBlank()) currentTitle = title
        if (url.isNotBlank()) currentUrl = url
        if (posMs > 0) currentPositionMs = posMs
        if (durMs > 0) durationMs = durMs

        Log.i(TAG, "[MEDIA_STATE_UPDATED] tabId=$tabId, playing=$playing, title=$title, url=$url")
    }

    fun getActiveWebView(): WebView? {
        val strongRef = activeMediaWebViewStrongRef
        if (strongRef != null) return strongRef
        val tabId = activeTabId ?: return getAnyActiveWebView()
        return activeWebViews[tabId]?.get() ?: getAnyActiveWebView()
    }

    fun setBgActive(active: Boolean) {
        mainHandler.post {
            val wv = getActiveWebView()
            if (wv != null) {
                val js = "window.__swiftBgActive = $active;"
                wv.evaluateJavascript(js, null)
            }
        }
    }

    private fun getAnyActiveWebView(): WebView? {
        for ((_, ref) in activeWebViews) {
            val wv = ref.get()
            if (wv != null) return wv
        }
        return null
    }

    fun playWebMedia() {
        mainHandler.post {
            val wv = getActiveWebView()
            if (wv != null) {
                Log.i(TAG, "[MEDIA_PLAY_REQUESTED] Dispatching JS play() to active WebView")
                val js = """
                    (function() {
                        window.__swiftWasPlaying = true;
                        window.__swiftUserPaused = false;
                        window.__swiftBgActive = true;
                        var mediaList = document.querySelectorAll('video, audio');
                        for (var i = 0; i < mediaList.length; i++) {
                            mediaList[i].play().catch(function(e){ console.log('Swift play error:', e); });
                        }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
                isMediaPlaying = true
            } else {
                Log.w(TAG, "[MEDIA_PLAY_REQUESTED] No active WebView found to trigger play")
            }
        }
    }

    fun pauseWebMedia() {
        mainHandler.post {
            val wv = getActiveWebView()
            if (wv != null) {
                Log.i(TAG, "[MEDIA_PAUSE_REQUESTED] Dispatching JS pause() to active WebView")
                val js = """
                    (function() {
                        window.__swiftWasPlaying = false;
                        window.__swiftUserPaused = true;
                        window.__swiftBgActive = false;
                        var mediaList = document.querySelectorAll('video, audio');
                        for (var i = 0; i < mediaList.length; i++) {
                            mediaList[i].pause();
                        }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
                isMediaPlaying = false
            } else {
                Log.w(TAG, "[MEDIA_PAUSE_REQUESTED] No active WebView found to trigger pause")
            }
        }
    }

    fun seekWebMedia(posMs: Long) {
        mainHandler.post {
            val wv = getActiveWebView()
            if (wv != null) {
                val posSec = (posMs.coerceAtLeast(0L)) / 1000.0
                Log.i(TAG, "[MEDIA_SEEK_REQUESTED] Seeking to $posSec seconds in active WebView")
                val js = """
                    (function() {
                        var mediaList = document.querySelectorAll('video, audio');
                        for (var i = 0; i < mediaList.length; i++) {
                            mediaList[i].currentTime = $posSec;
                        }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
                currentPositionMs = posMs
            }
        }
    }

    fun isAnyWebMediaPlaying(context: Context? = null): Boolean {
        if (!isMediaPlaying) return false
        if (context != null) {
            val allowed = PermissionEngineApi.isBackgroundWebMediaPlaybackAllowed(context, currentUrl)
            if (!allowed) {
                Log.d(TAG, "[BACKGROUND_MEDIA_PERMISSION_DENIED] Background media playback blocked by PermissionEngine")
                return false
            }
        }
        return true
    }
}
