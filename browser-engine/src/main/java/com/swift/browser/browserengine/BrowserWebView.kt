package com.swift.browser.browserengine

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.abs

interface BrowserWebViewScrollListener {
    fun onWebViewScroll(diffY: Int, isAtTop: Boolean)
    fun onTouchStart() {}
    fun onTouchRelease() {}
}

interface BrowserWebViewNavigationListener {
    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean
    fun goForward()
}

open class BrowserWebView @JvmOverloads constructor(
    context: Context,
    var tabId: String = "",
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var scrollListener: BrowserWebViewScrollListener? = null
    var navigationListener: BrowserWebViewNavigationListener? = null
    var speechBridge: WebSpeechRecognitionBridge? = null

    var isPrivate: Boolean = false
    var privateSessionId: String? = null
    var profileName: String? = null

    companion object {
        private val NEXT_GENERATION =
            java.util.concurrent.atomic.AtomicLong(1L)
    }

    val webViewIdentity: String =
        java.util.UUID.randomUUID().toString()

    var generation: Long =
        NEXT_GENERATION.getAndIncrement()
        private set

    fun incrementGeneration(): Long {
        generation = NEXT_GENERATION.getAndIncrement()
        return generation
    }

    var isDestroyed: Boolean = false
        private set

    private var startX = 0f
    private var startY = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawY = 0f
    private val swipeThreshold = 150f
    private val yThreshold = 100f
    private var isUserTouching = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != android.view.View.VISIBLE && com.swift.browser.audioengine.WebMediaPlaybackController.isAnyWebMediaPlaying(context)) {
            android.util.Log.i("BrowserWebView", "[MEDIA_BACKGROUND_BYPASS] Overriding onWindowVisibilityChanged to View.VISIBLE to prevent WebView auto-pause")
            super.onWindowVisibilityChanged(android.view.View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (isDestroyed) return

        val listener = scrollListener ?: return
        if (t <= 4) {
            listener.onWebViewScroll(0, isAtTop = true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isDestroyed) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isUserTouching = true
                startX = event.x
                startY = event.y
                startRawX = event.rawX
                startRawY = event.rawY
                lastRawY = event.rawY
                isFocusable = true
                isFocusableInTouchMode = true
                if (!isDestroyed) {
                    scrollListener?.onTouchStart()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                isUserTouching = true
                val deltaY = (lastRawY - event.rawY).toInt()
                lastRawY = event.rawY
                val isAtTop = (scrollY <= 4)
                if (deltaY != 0 && !isDestroyed) {
                    scrollListener?.onWebViewScroll(deltaY, isAtTop = (isAtTop && deltaY < 0))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUserTouching = false
                if (!isDestroyed) {
                    scrollListener?.onTouchRelease()
                }
                val diffX = event.rawX - startRawX
                val diffY = event.rawY - startRawY
                if (abs(diffX) > abs(diffY) && abs(diffX) > swipeThreshold && abs(diffY) < yThreshold) {
                    if (diffX > 0) {
                        val nav = navigationListener
                        if (nav != null && nav.canGoBack()) {
                            nav.goBack()
                            return true
                        } else if (canGoBack()) {
                            safeGoBack()
                            return true
                        }
                    } else {
                        val nav = navigationListener
                        if (nav != null && nav.canGoForward()) {
                            nav.goForward()
                            return true
                        } else if (canGoForward()) {
                            safeGoForward()
                            return true
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun canGoBack(): Boolean {
        if (isDestroyed) return false
        return try {
            super.canGoBack()
        } catch (e: Throwable) {
            false
        }
    }

    override fun canGoForward(): Boolean {
        if (isDestroyed) return false
        return try {
            super.canGoForward()
        } catch (e: Throwable) {
            false
        }
    }

    override fun goBack() {
        safeGoBack()
    }

    override fun goForward() {
        safeGoForward()
    }

    fun safeGoBack(): Boolean {
        if (isDestroyed) return false
        return try {
            if (canGoBack()) {
                super.goBack()
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    fun safeGoForward(): Boolean {
        if (isDestroyed) return false
        return try {
            if (canGoForward()) {
                super.goForward()
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    fun safeCopyBackForwardList(): android.webkit.WebBackForwardList? {
        if (isDestroyed) return null
        return try {
            super.copyBackForwardList()
        } catch (e: Throwable) {
            null
        }
    }

    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        scrollListener = null
        navigationListener = null
        try {
            if (tabId.isNotBlank()) {
                com.swift.browser.audioengine.WebMediaPlaybackController.unregisterWebView(tabId)
                com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.onWebViewDestroyed(tabId)
                com.swift.browser.browserengine.webrtc.WebRtcRecoveryCoordinator.cancelRecoveryForTab(tabId)
                com.swift.browser.browserengine.webrtc.WebMediaDeviceManager.unregisterWebView(tabId)
                com.swift.browser.browserengine.screencapture.ScreenCaptureManager.onWebViewDestroyed(tabId)
            }
            speechBridge?.destroy()
            speechBridge = null
        } catch (e: Exception) {
            // Ignore destroy errors
        }
        try {
            super.destroy()
        } catch (e: Throwable) {
            // Protect against platform WebView destroy exceptions
        }
    }
}
