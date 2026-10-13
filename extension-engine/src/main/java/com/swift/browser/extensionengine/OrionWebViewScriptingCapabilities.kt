package com.swift.browser.extensionengine

import androidx.webkit.WebViewFeature

enum class ScriptingCapability {
    ISOLATED_WORLD,
    MAIN_WORLD,
    FRAME_TARGETING,
    DOCUMENT_TARGETING,
    DOCUMENT_START,
    DOCUMENT_END,
    DOCUMENT_IDLE,
    CSS_INJECTION,
    WEB_MESSAGE_BRIDGE
}

/** Single capability source for WebView-backed content-script execution. */
object OrionWebViewScriptingCapabilities {
    fun isDocumentStartScriptSupported(): Boolean = feature(WebViewFeature.DOCUMENT_START_SCRIPT)
    fun isFrameAndWorldInjectionSupported(): Boolean = feature(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
    fun isWebMessageListenerSupported(): Boolean = feature(WebViewFeature.WEB_MESSAGE_LISTENER)

    // Enables DOM-based CSS injection into WebView pages.
    fun isCssInjectionSupported(): Boolean = true

    // The browser owns a stable logical frame/document registry backed by a page-world
    // WebMessage transport. These are WebView-provider identities, not Chromium RFH IDs.
    fun supportsAuthoritativeFrameTargeting(): Boolean =
        isWebMessageListenerSupported() && isFrameAndWorldInjectionSupported()

    fun supportsAuthoritativeDocumentTargeting(): Boolean =
        isWebMessageListenerSupported() && isFrameAndWorldInjectionSupported()

    fun supports(capability: ScriptingCapability): Boolean = when (capability) {
        ScriptingCapability.ISOLATED_WORLD -> isFrameAndWorldInjectionSupported()
        ScriptingCapability.MAIN_WORLD -> true
        ScriptingCapability.FRAME_TARGETING -> supportsAuthoritativeFrameTargeting()
        ScriptingCapability.DOCUMENT_TARGETING -> supportsAuthoritativeDocumentTargeting()
        ScriptingCapability.DOCUMENT_START -> isDocumentStartScriptSupported() || isFrameAndWorldInjectionSupported()
        ScriptingCapability.DOCUMENT_END -> isFrameAndWorldInjectionSupported()
        ScriptingCapability.DOCUMENT_IDLE -> isFrameAndWorldInjectionSupported()
        ScriptingCapability.CSS_INJECTION -> isCssInjectionSupported()
        ScriptingCapability.WEB_MESSAGE_BRIDGE -> isWebMessageListenerSupported() && isFrameAndWorldInjectionSupported()
    }

    fun canTargetFrame(frameId: Int, allFrames: Boolean): Boolean =
        (allFrames || frameId >= 0) && supportsAuthoritativeFrameTargeting()

    fun canTargetWorld(world: String): Boolean = when (ContentScriptExecutionWorld.fromString(world)) {
        ContentScriptExecutionWorld.MAIN -> supports(ScriptingCapability.MAIN_WORLD)
        ContentScriptExecutionWorld.ISOLATED -> supports(ScriptingCapability.ISOLATED_WORLD)
        ContentScriptExecutionWorld.USER_SCRIPT -> supports(ScriptingCapability.MAIN_WORLD)
    }

    private fun feature(name: String): Boolean = try {
        WebViewFeature.isFeatureSupported(name)
    } catch (_: Throwable) {
        false
    }
}
