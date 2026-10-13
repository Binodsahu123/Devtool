package com.swift.browser.browserengine

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.swift.browser.extensionengine.ExtensionNetworkRequestContext
import com.swift.browser.extensionengine.WebRequestInterceptResult
import com.swift.browser.securityengine.SecurityEngineProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Single authoritative network interception coordinator for Orion Browser.
 * Enforces security policy, adblock policy, and extension DNR / WebRequest rules.
 * NEVER proxies normal requests through OkHttp.
 */
object WebResourceInterceptionCoordinator {
    private const val TAG = "WebResourceInterception"
    private const val MAX_EXTENSION_REDIRECTS = 10
    private data class PreparedNetworkRequest(
        val engine: com.swift.browser.extensionengine.ExtensionEngineImpl,
        val request: ExtensionNetworkRequestContext,
        val preflightKey: String
    )

    sealed class MainFrameNavigationDecision {
        object Continue : MainFrameNavigationDecision()
        object Blocked : MainFrameNavigationDecision()
        data class Redirect(val url: String) : MainFrameNavigationDecision()
    }

    private data class PreflightRecord(
        val result: WebRequestInterceptResult,
        val createdAt: Long
    )

    private const val PREFLIGHT_TTL_MS = 4_000L
    private val redirectChains = ConcurrentHashMap<String, MutableList<String>>()
    private val activeMainFrameRequests = ConcurrentHashMap<String, ExtensionNetworkRequestContext>()
    private val preflightDecisions = ConcurrentHashMap<String, PreflightRecord>()

    fun shouldInterceptRequest(
        context: Context,
        view: WebView?,
        request: WebResourceRequest?,
        currentDocUrl: String?,
        onAdBlocked: () -> Unit
    ): WebResourceResponse? {
        return try {
            val urlStr = request?.url?.toString() ?: return null

            // 1. Extension Resolver (chrome-extension://, swift-extension://, https://<id>.extension.orion.invalid)
            if (com.swift.browser.extensionengine.origin.ExtensionUrl.isExtensionUrl(urlStr)) {
                val interceptRes = com.swift.browser.extensionengine.ExtensionDirectoryResolver.handleExtensionRequest(context, urlStr)
                if (interceptRes != null) return interceptRes
            }

            // 2. Subresource Security Policy Check
            if (request != null && !request.isForMainFrame) {
                val isSafe = SecurityEngineProvider.api.isSubresourceSafe(urlStr, currentDocUrl)
                if (!isSafe) {
                    Log.w(TAG, "Subresource blocked by Security Engine: $urlStr")
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by Security Policy", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }

            // 3. Extension DNR & WebRequest Interception
            if (request != null) {
                try {
                    val prepared = prepareNetworkRequest(context, view, request, currentDocUrl, registerLifecycle = true)
                    if (prepared != null) {
                        val cached = if (request.isForMainFrame) {
                            consumePreflight(prepared.preflightKey)
                        } else {
                            null
                        }
                        val webResult = if (cached != null) {
                            prepared.engine.webRequestAdapter.notifyEvent("onBeforeRequest", prepared.request)
                            cached
                        } else {
                            prepared.engine.webRequestAdapter.interceptRequest(prepared.request)
                        }

                        when (webResult) {
                            is WebRequestInterceptResult.Blocked -> {
                                onAdBlocked()
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked",
                                    mapOf("Cache-Control" to "no-store", "X-Swift-Extension-Blocked" to "1"),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            is WebRequestInterceptResult.Redirect -> {
                                val target = webResult.redirectUrl
                                val chainKey = redirectChainKey(prepared.request)
                                val chain = redirectChains.computeIfAbsent(chainKey) { mutableListOf(prepared.request.url) }
                                if (target == prepared.request.url || chain.contains(target) || chain.size >= MAX_EXTENSION_REDIRECTS) {
                                    redirectChains.remove(chainKey)
                                    return WebResourceResponse(
                                        "text/plain", "UTF-8", 508, "Extension redirect loop",
                                        emptyMap(), ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                if (!chain.contains(target)) chain.add(target)
                                // In WebView the in-flight subresource URL cannot be safely rewritten at
                                // this callback boundary. Main-frame redirects are handled in
                                // shouldOverrideUrlLoading through the same decision cache.
                                if (request.isForMainFrame) {
                                    Log.d(TAG, "Main-frame redirect preflight cached: ${prepared.request.url} -> $target")
                                } else {
                                    Log.w(TAG, "DNR_REDIRECT_REQUIRES_CHROMIUM_NETWORK_PROVIDER: ${prepared.request.url} -> $target")
                                }
                            }
                            is WebRequestInterceptResult.ModifyHeaders -> {
                                Log.w(TAG, "DNR_HEADER_MODIFICATION_REQUIRES_CHROMIUM_NETWORK_PROVIDER")
                            }
                            is WebRequestInterceptResult.Unsupported -> {
                                Log.w(TAG, "WebView network capability unsupported: ${webResult.code}")
                            }
                            else -> {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error evaluating extension network policy for: $urlStr", e)
                }
            }
            // Return null so native Android WebView handles the request directly
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Error in interception coordinator: ${e.message}", e)
            null
        }
    }

    /**
     * Preflight the browser's top-level navigation through the canonical DNR evaluator.
     * WebView does not expose an API for a synthetic HTTP 3xx response from
     * shouldInterceptRequest (3xx is explicitly unsupported), so a supported main-frame
     * redirect is applied by taking control of the navigation in shouldOverrideUrlLoading.
     * Subresource redirects still require a Chromium Network Service provider.
     */
    fun resolveMainFrameNavigationDecision(
        context: Context,
        view: WebView?,
        request: WebResourceRequest?,
        currentDocUrl: String?
    ): MainFrameNavigationDecision {
        if (request == null || !request.isForMainFrame) return MainFrameNavigationDecision.Continue
        val url = request.url?.toString().orEmpty()
        if (url.isBlank()) return MainFrameNavigationDecision.Continue

        return try {
            val prepared = prepareNetworkRequest(
                context = context,
                view = view,
                request = request,
                currentDocUrl = currentDocUrl,
                registerLifecycle = false
            ) ?: return MainFrameNavigationDecision.Continue

            val result = prepared.engine.webRequestAdapter.interceptRequest(
                prepared.request,
                dispatchBeforeRequest = false
            )
            preflightDecisions[prepared.preflightKey] = PreflightRecord(result, System.currentTimeMillis())
            prunePreflight()

            when (result) {
                is WebRequestInterceptResult.Blocked -> {
                    prepared.engine.webRequestAdapter.notifyEvent(
                        "onErrorOccurred",
                        prepared.request,
                        JSONObject().put("error", "net::ERR_BLOCKED_BY_CLIENT")
                    )
                    MainFrameNavigationDecision.Blocked
                }
                is WebRequestInterceptResult.Redirect -> {
                    val target = normalizeNavigationRedirect(url, result.redirectUrl)
                    if (target == null) {
                        MainFrameNavigationDecision.Continue
                    } else {
                        cacheNavigationRedirect(prepared.request, target)
                        prepared.engine.webRequestAdapter.notifyEvent(
                            "onBeforeRedirect",
                            prepared.request,
                            JSONObject().put("redirectUrl", target)
                        )
                        MainFrameNavigationDecision.Redirect(target)
                    }
                }
                else -> {
                    val dnrMatch = prepared.engine.dnrAdapter.evaluateRequest(
                        url = url,
                        resourceType = "main_frame",
                        requestHeaders = request.requestHeaders ?: emptyMap(),
                        isPrivate = prepared.request.isPrivate,
                        initiator = currentDocUrl,
                        method = request.method ?: "GET"
                    )
                    val target = when (dnrMatch?.actionType?.lowercase()) {
                        "redirect" -> normalizeNavigationRedirect(url, dnrMatch.redirectUrl)
                        "upgradescheme" -> if (url.startsWith("http://", ignoreCase = true)) {
                            "https://" + url.substring(7)
                        } else null
                        else -> null
                    }
                    if (!target.isNullOrBlank() && target != url) {
                        cacheNavigationRedirect(prepared.request, target)
                        MainFrameNavigationDecision.Redirect(target)
                    } else {
                        MainFrameNavigationDecision.Continue
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Main-frame network preflight failed", t)
            MainFrameNavigationDecision.Continue
        }
    }

    fun resolveMainFrameNavigationRedirect(
        context: Context,
        view: WebView?,
        request: WebResourceRequest?,
        currentDocUrl: String?
    ): String? =
        (resolveMainFrameNavigationDecision(context, view, request, currentDocUrl)
            as? MainFrameNavigationDecision.Redirect)?.url

    private fun prepareNetworkRequest(
        context: Context,
        view: WebView?,
        request: WebResourceRequest,
        currentDocUrl: String?,
        registerLifecycle: Boolean
    ): PreparedNetworkRequest? {
        val engine = com.swift.browser.extensionengine.ExtensionEngineApi
            .getInstance(context.applicationContext)
            .extensionManager.engine as? com.swift.browser.extensionengine.ExtensionEngineImpl
            ?: return null

        val bridge = view?.tag as? com.swift.browser.extensionengine.RuntimeBridge
        val tabIdStr = bridge?.tabId ?: return null
        val tabId = tabIdStr.toIntOrNull() ?: -1
        val isPrivate = bridge?.isPrivate ?: false
        val privateSessionId = bridge?.privateSessionId
        val canonicalTabId = com.swift.browser.extensionengine.TabIdMapper.getUuidFromString(tabIdStr)
        val canonicalFrame = if (request.isForMainFrame) {
            engine.webNavigationAdapter.stateMachine.getFrameIdentity(canonicalTabId, 0)
        } else null

        val frameId = if (request.isForMainFrame) 0 else -1
        val documentId = canonicalFrame?.documentId
        val method = request.method ?: "GET"
        val url = request.url?.toString().orEmpty()
        val preflightKey = listOf(tabIdStr, frameId, documentId.orEmpty(), method, url, currentDocUrl.orEmpty()).joinToString("|")

        val requestScopeKey = listOf(
            tabIdStr, frameId, documentId.orEmpty(), method, url, currentDocUrl.orEmpty()
        ).joinToString("|")
        val requestId = engine.webRequestAdapter.idMapper.getOrCreateRequestId(requestScopeKey)

        val chainKey = redirectChainKey(tabIdStr, frameId, documentId, method)
        if (redirectChains.size > 4096) redirectChains.keys.firstOrNull()?.let { redirectChains.remove(it) }
        val chain = redirectChains.computeIfAbsent(chainKey) { mutableListOf(url) }
        if (chain.lastOrNull() != url && !chain.contains(url)) chain.add(url)

        val networkContext = ExtensionNetworkRequestContext(
            requestId = requestId,
            url = url,
            method = method,
            resourceType = guessResourceType(request),
            tabId = tabId,
            frameId = frameId,
            parentFrameId = canonicalFrame?.parentFrameId ?: -1,
            documentId = documentId,
            documentUrl = canonicalFrame?.url ?: currentDocUrl,
            isPrivate = isPrivate,
            privateSessionId = privateSessionId,
            requestHeaders = request.requestHeaders ?: emptyMap(),
            isForMainFrame = request.isForMainFrame,
            initiator = currentDocUrl,
            origin = runCatching {
                val parsed = android.net.Uri.parse(url)
                if (parsed.scheme != null && parsed.host != null) "${parsed.scheme}://${parsed.host}" else null
            }.getOrNull(),
            redirectChain = chain.toList(),
            windowId = if (isPrivate) 2 else 1
        )

        if (registerLifecycle) {
            if (request.isForMainFrame) activeMainFrameRequests[tabIdStr] = networkContext
        }
        return PreparedNetworkRequest(engine, networkContext, preflightKey)
    }

    private fun redirectChainKey(request: ExtensionNetworkRequestContext): String =
        redirectChainKey(
            tabId = request.tabId.toString(),
            frameId = request.frameId,
            documentId = request.documentId,
            method = request.method
        )

    private fun redirectChainKey(tabId: String, frameId: Int, documentId: String?, method: String): String =
        listOf(tabId, frameId, documentId.orEmpty(), method).joinToString("|")

    private fun normalizeNavigationRedirect(sourceUrl: String, candidate: String?): String? {
        val target = candidate?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (target == sourceUrl) return null
        return target
    }

    private fun cacheNavigationRedirect(request: ExtensionNetworkRequestContext, target: String) {
        val key = redirectChainKey(request)
        val chain = redirectChains.computeIfAbsent(key) { mutableListOf(request.url) }
        if (!chain.contains(target)) chain.add(target)
        if (chain.size > MAX_EXTENSION_REDIRECTS) redirectChains.remove(key)
    }

    private fun consumePreflight(key: String): WebRequestInterceptResult? {
        val record = preflightDecisions.remove(key) ?: return null
        if (System.currentTimeMillis() - record.createdAt > PREFLIGHT_TTL_MS) return null
        return record.result
    }

    private fun prunePreflight() {
        val cutoff = System.currentTimeMillis() - PREFLIGHT_TTL_MS
        preflightDecisions.entries.removeIf { it.value.createdAt < cutoff }
    }

    fun onMainFrameCompleted(context: Context, tabId: String, url: String, statusCode: Int? = null) {
        val requestContext = activeMainFrameRequests.remove(tabId) ?: return
        if (requestContext.url != url) { activeMainFrameRequests[tabId] = requestContext; return }
        val engineImpl = try { com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).extensionManager.engine as? com.swift.browser.extensionengine.ExtensionEngineImpl } catch (_: Throwable) { null } ?: return
        engineImpl.webRequestAdapter.notifyEvent("onResponseStarted", requestContext)
        val details = statusCode?.let { JSONObject().put("statusCode", it) }
        engineImpl.webRequestAdapter.notifyEvent("onCompleted", requestContext, details)
        redirectChains.remove(listOf(tabId, requestContext.frameId, requestContext.documentId ?: "", requestContext.method).joinToString("|"))
    }

    fun onMainFrameError(context: Context, tabId: String, url: String, error: String) {
        val requestContext = activeMainFrameRequests.remove(tabId) ?: return
        if (requestContext.url != url) { activeMainFrameRequests[tabId] = requestContext; return }
        val engineImpl = try { com.swift.browser.extensionengine.ExtensionEngineApi.getInstance(context).extensionManager.engine as? com.swift.browser.extensionengine.ExtensionEngineImpl } catch (_: Throwable) { null } ?: return
        engineImpl.webRequestAdapter.notifyEvent("onErrorOccurred", requestContext, JSONObject().put("error", error))
        redirectChains.remove(listOf(tabId, requestContext.frameId, requestContext.documentId ?: "", requestContext.method).joinToString("|"))
    }

    private fun guessResourceType(request: WebResourceRequest?): String {
        if (request == null) return "other"
        if (request.isForMainFrame) return "main_frame"
        val path = request.url?.path?.lowercase() ?: ""
        return when {
            path.endsWith(".js") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                    path.endsWith(".ico") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                    path.endsWith(".otf") -> "font"
            path.endsWith(".mp4") || path.endsWith(".mp3") || path.endsWith(".webm") ||
                    path.endsWith(".ogg") -> "media"
            path.endsWith(".html") || path.endsWith(".htm") -> "sub_frame"
            else -> {
                val accept = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
                when {
                    accept.contains("text/html") -> "sub_frame"
                    accept.contains("text/css") -> "stylesheet"
                    accept.contains("javascript") || accept.contains("application/x-javascript") -> "script"
                    accept.contains("image/") -> "image"
                    else -> "other"
                }
            }
        }
    }

}
