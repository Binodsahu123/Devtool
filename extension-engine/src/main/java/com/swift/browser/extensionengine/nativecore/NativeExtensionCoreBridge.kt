package com.swift.browser.extensionengine.nativecore

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browser-engine native bridge. It owns only native state/lifecycle bookkeeping;
 * Kotlin remains the source of truth for extension registry/API permissions.
 */
object NativeExtensionCoreBridge {
    private const val TAG = "NativeExtensionCoreBridge"
    private const val LIB = "swift_extension_core"
    private val loadAttempted = AtomicBoolean(false)
    @Volatile private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadAttempted.compareAndSet(false, true)) {
            loaded = try {
                System.loadLibrary(LIB)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Native extension core unavailable; retaining Kotlin/WebView provider", t)
                false
            }
        }
        return loaded
    }

    fun worldName(extensionId: String): String? =
        if (!ensureLoaded()) null else runCatching { nativeGetWorldName(extensionId) }.getOrNull()

    fun validateMainWorldScript(code: String): Boolean {
        if (!ensureLoaded()) return !code.trimStart().startsWith("javascript:", ignoreCase = true)
        return runCatching { nativeValidateMainWorldScript(code) }.getOrDefault(false)
    }















    private external fun nativeGetWorldName(extensionId: String): String
    private external fun nativeValidateMainWorldScript(code: String): Boolean
}
