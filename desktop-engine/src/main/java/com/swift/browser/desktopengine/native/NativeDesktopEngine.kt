package com.swift.browser.desktopengine.native

import android.util.Log

object NativeDesktopEngine {
    private const val TAG = "NativeDesktopEngine"

    fun calculateScale(containerWidth: Int, containerHeight: Int): Float {
        if (containerWidth <= 0 || containerHeight <= 0) return 1.0f
        val standardDesktopWidth = 1280f
        return (containerWidth / standardDesktopWidth).coerceIn(0.25f, 2.0f)
    }

    fun rewriteUrl(url: String, toDesktop: Boolean): String {
        if (url.isBlank()) return url
        return if (toDesktop) {
            url.replace("://m.", "://www.")
                .replace("://mobile.", "://www.")
        } else {
            url
        }
    }

    fun evaluateCompatibility(host: String): Int {
        if (host.isBlank()) return 100
        return 100
    }
}

