package com.swift.browser.extensionengine

import org.json.JSONObject

data class WebRequestData(
    val requestId: String,
    val url: String,
    val method: String,
    val type: String,
    val tabId: Int,
    val frameId: Int = 0,
    val documentId: String? = null,
    val timeStamp: Long = System.currentTimeMillis(),
    val isPrivate: Boolean = false,
    val windowId: Int? = null
)

sealed class WebRequestInterceptResult {
    object Continue : WebRequestInterceptResult()
    object Blocked : WebRequestInterceptResult()
    data class Redirect(val redirectUrl: String) : WebRequestInterceptResult()
    data class ModifyHeaders(
        val requestHeaders: List<DnrHeader> = emptyList(),
        val responseHeaders: List<DnrHeader> = emptyList()
    ) : WebRequestInterceptResult()
    data class Unsupported(val code: String) : WebRequestInterceptResult()
}

/**
 * Adapter implementing Chrome WebRequest API (chrome.webRequest.*) and network interception.
 * Uses the canonical ExtensionPermissionAdapter, unified ExtensionDnrAdapter,
 * WebRequestSubscriptionRegistry, and WebRequestIdMapper.
 */
class ExtensionWebRequestAdapter(
    val permissionAdapter: ExtensionPermissionAdapter,
    val registry: ExtensionRegistry,
    val eventManager: EventManager,
    val dnrAdapter: ExtensionDnrAdapter,
    val subscriptionRegistry: WebRequestSubscriptionRegistry = WebRequestSubscriptionRegistry(),
    val requestRegistry: WebRequestRegistry = WebRequestRegistry(),
    val idMapper: WebRequestIdMapper = WebRequestIdMapper()
) {
    fun notifyEvent(eventName: String, request: ExtensionNetworkRequestContext, extraDetails: JSONObject? = null) {
        val enabledExts = registry.getEnabledExtensions()
        val cleanEvent = if (eventName.startsWith("webRequest.")) eventName else "webRequest.$eventName"

        // Track lifecycle in the canonical state machine. Terminal transitions are CAS-like.
        when (eventName.removePrefix("webRequest.")) {
            "onBeforeRequest" -> requestRegistry.trackRequest(request)
            "onBeforeSendHeaders" -> requestRegistry.updateStage(request.requestId, WebRequestRegistry.RequestStage.BEFORE_SEND_HEADERS)
            "onSendHeaders" -> requestRegistry.updateStage(request.requestId, WebRequestRegistry.RequestStage.SEND_HEADERS)
            "onHeadersReceived" -> requestRegistry.updateStage(request.requestId, WebRequestRegistry.RequestStage.HEADERS_RECEIVED)
            "onResponseStarted" -> requestRegistry.updateStage(request.requestId, WebRequestRegistry.RequestStage.RESPONSE_STARTED)
            "onBeforeRedirect" -> requestRegistry.updateStage(request.requestId, WebRequestRegistry.RequestStage.BEFORE_REDIRECT)
            "onCompleted" -> { if (!requestRegistry.completeRequest(request.requestId)) return; idMapper.releaseRequestId(request.requestId) }
            "onErrorOccurred" -> { if (!requestRegistry.errorRequest(request.requestId, extraDetails?.optString("error", "unknown") ?: "error")) return; idMapper.releaseRequestId(request.requestId) }
        }

        // Get matching subscriptions
        val matchingSubs = subscriptionRegistry.getMatchingSubscriptions(cleanEvent, request)
        val subscribedExtIds = matchingSubs.map { it.extensionId.lowercase().trim() }.toSet()

        for (ext in enabledExts) {
            val extId = ext.id.lowercase().trim()
            if (request.isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) continue

            // Canonical Permission Checks via ExtensionPermissionAdapter ONLY
            val hasWebRequestPerm = permissionAdapter.hasApiPermission(extId, "webRequest", request.isPrivate) ||
                    permissionAdapter.hasApiPermission(extId, "webRequestBlocking", request.isPrivate) ||
                    permissionAdapter.hasApiPermission(extId, "declarativeNetRequest", request.isPrivate)

            if (!hasWebRequestPerm) continue

            val hasHostPerm = permissionAdapter.hasHostPermission(extId, request.url, request.isPrivate)
            if (!hasHostPerm) continue

            // If extension registered explicit subscription with filters, ensure it matches
            if (subscriptionRegistry.hasSubscribers(cleanEvent) && !subscribedExtIds.contains(extId)) continue

            val extensionSubscriptions = matchingSubs.filter { it.extensionId.equals(extId, ignoreCase = true) }
            val includeRequestHeaders = extensionSubscriptions.any {
                it.extraInfoSpec.any { spec -> spec.equals("requestHeaders", ignoreCase = true) || spec.equals("extraHeaders", ignoreCase = true) }
            }
            val includeResponseHeaders = extensionSubscriptions.any {
                it.extraInfoSpec.any { spec -> spec.equals("responseHeaders", ignoreCase = true) || spec.equals("extraHeaders", ignoreCase = true) }
            }
            val payload = request.toEventPayload(
                extraDetails = extraDetails,
                includeRequestHeaders = includeRequestHeaders,
                includeResponseHeaders = includeResponseHeaders
            )
            eventManager.triggerEventForExtension(extId, cleanEvent, payload)
        }
    }

    fun notifyEvent(eventName: String, request: WebRequestData, extraDetails: JSONObject? = null) {
        val context = ExtensionNetworkRequestContext.fromWebRequestData(request)
        notifyEvent(eventName, context, extraDetails)
    }

    fun interceptRequest(
        request: ExtensionNetworkRequestContext,
        dispatchBeforeRequest: Boolean = true
    ): WebRequestInterceptResult {
        val dnrMatch = dnrAdapter.evaluateRequest(
            url = request.url,
            resourceType = request.resourceType,
            requestHeaders = request.requestHeaders,
            isPrivate = request.isPrivate,
            initiator = request.initiator,
            method = request.method
        )

        if (dispatchBeforeRequest) notifyEvent("onBeforeRequest", request)

        if (dnrMatch != null) {
            when (dnrMatch.actionType.lowercase()) {
                "block" -> {
                    // The execution owner emits the terminal error only after it has
                    // actually cancelled the request. The adapter is decision-only.
                    return WebRequestInterceptResult.Blocked
                }
                "redirect", "upgradescheme" -> {
                    val redirectUrl = dnrMatch.redirectUrl
                    if (!redirectUrl.isNullOrBlank()) {
                        return WebRequestInterceptResult.Redirect(redirectUrl)
                    }
                }
                "modifyheaders" -> {
                    return WebRequestInterceptResult.ModifyHeaders(
                        requestHeaders = dnrMatch.requestHeaders,
                        responseHeaders = dnrMatch.responseHeaders
                    )
                }
                "allow", "allowallrequests" -> {
                    return WebRequestInterceptResult.Continue
                }
            }
        }

        return WebRequestInterceptResult.Continue
    }

    fun interceptRequest(request: WebRequestData): WebRequestInterceptResult {
        val context = ExtensionNetworkRequestContext.fromWebRequestData(request)
        return interceptRequest(context)
    }
}
