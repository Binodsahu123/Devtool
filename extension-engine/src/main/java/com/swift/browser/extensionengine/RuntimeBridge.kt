package com.swift.browser.extensionengine

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.JavaScriptReplyProxy
import java.util.concurrent.ConcurrentHashMap

interface BrowserDelegate {
    fun queryTabs(queryInfo: JSONObject): JSONArray
    fun createTab(url: String, active: Boolean)
    fun removeTab(tabId: String)
    fun reloadTab(tabId: String)
    fun updateTab(tabId: String, url: String)
    fun showNotification(title: String, message: String)
    fun downloadFile(url: String, filename: String?)
    fun getActiveTabId(): String?
    /** Returns the canonical tab WebView when the browser host exposes one. */
    fun getWebView(tabId: String): WebView? = null
    fun executeScriptOnTab(tabId: String, code: String, callback: (String?) -> Unit)
    /** Provider-native frame/world-aware execution. Implementations should use a real isolated
     * execution world where available and return one result per selected frame. */
    fun executeExtensionScript(
        extensionId: String,
        target: ScriptExecutionTarget,
        code: String,
        isFunction: Boolean,
        argsJson: String,
        callback: (JSONArray?, String?) -> Unit
    ) {
        callback(null, "SCRIPTING_TARGETED_EXECUTION_UNSUPPORTED")
    }
    fun checkExtensionPermission(extensionId: String, permission: String, callback: (Boolean) -> Unit)
}

class RuntimeBridge(
    val context: Context,
    val webView: WebView?,
    val storageManager: StorageManager,
    val storageBackend: ExtensionStorageBackend,
    val messageBus: MessageBus,
    val delegate: BrowserDelegate?,
    val backgroundScriptManager: BackgroundScriptManager? = null,
    val eventManager: EventManager,
    val tabId: String? = null,
    val frameId: Int? = null,
    /** Browser-owned document identity captured at bridge creation. */
    val documentId: String? = null,
    val portManager: PortManager? = null,
    val tabBridge: TabBridge? = null,
    val registry: ExtensionRegistry,
    val permissionManager: PermissionManager,
    val isPrivate: Boolean = false,
    val privateSessionId: String? = null,
    val permissionsAdapter: ExtensionPermissionAdapter,
    val dnrAdapter: ExtensionDnrAdapter,
    val webRequestAdapter: ExtensionWebRequestAdapter,
    val scriptingAdapter: ExtensionScriptingAdapter,
    val actionAdapter: ExtensionActionAdapter,
    val contextMenusAdapter: ExtensionContextMenusAdapter,
    val commandsAdapter: ExtensionCommandsAdapter,
    val omniboxAdapter: ExtensionOmniboxAdapter,
    val sidePanelAdapter: ExtensionSidePanelAdapter,
    val managementAdapter: ExtensionManagementAdapter,
    val topSitesAdapter: ExtensionTopSitesAdapter,
    val idleAdapter: ExtensionIdleAdapter,
    val ttsAdapter: ExtensionTtsAdapter,
    val searchAdapter: ExtensionSearchAdapter,
    val alarmsAdapter: ExtensionAlarmsAdapter,
    val systemAdapter: ExtensionSystemAdapter,
    val notificationsAdapter: ExtensionNotificationsAdapter,
    /** Canonical browser-core services shared with the browser UI. */
    val coreServices: BrowserCoreServices,
    val webNavigationAdapter: ExtensionWebNavigationAdapter,
    val tabsAdapter: ExtensionTabsAdapter,
    val windowsAdapter: ExtensionWindowsAdapter,
    val tabGroupsAdapter: ExtensionTabGroupsAdapter,
    val sessionsAdapter: ExtensionSessionsAdapter,
    val cookieAdapter: ExtensionCookieAdapter,
    val bookmarksAdapter: ExtensionBookmarksAdapter,
    val historyAdapter: ExtensionHistoryAdapter,
    val downloadsAdapter: ExtensionDownloadsAdapter,
    val browsingDataAdapter: ExtensionBrowsingDataAdapter,
    val privacyAdapter: ExtensionPrivacyAdapter,
    val contentSettingsAdapter: ExtensionContentSettingsAdapter,
    val offscreenAdapter: ExtensionOffscreenAdapter,
    val userScriptsAdapter: ExtensionUserScriptsAdapter,
    val powerAdapter: ExtensionPowerAdapter,
    val debuggerAdapter: com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter? = null,
    val serviceWorkerEventDispatcher: ServiceWorkerEventDispatcher? = null,
    val messageRouter: ExtensionMessageRouter? = null,
    val contextTypeOverride: ExtensionContextType? = null,
    val forcedOwnExtensionId: String? = null
) : MessageListener, PortConnectionListener {

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private data class PendingBridgeRequest(
        val tabId: String?,
        val frameId: Int,
        val documentId: String?,
        val replyProxy: JavaScriptReplyProxy? = null
    )
    private val pendingBridgeRequests = ConcurrentHashMap<String, PendingBridgeRequest>()

    val jsEventRegistry = ExtensionJsEventRegistry(eventManager, webRequestAdapter, registry)

    val jsRouter = ExtensionJsBridgeRouter(
        registry = registry,
        permissionAdapter = permissionsAdapter,
        storageBackend = storageBackend,
        messageBus = messageBus,
        eventManager = eventManager,
        tabsAdapter = tabsAdapter,
        windowsAdapter = windowsAdapter,
        tabGroupsAdapter = tabGroupsAdapter,
        sessionsAdapter = sessionsAdapter,
        cookieAdapter = cookieAdapter,
        bookmarksAdapter = bookmarksAdapter,
        historyAdapter = historyAdapter,
        downloadsAdapter = downloadsAdapter,
        dnrAdapter = dnrAdapter,
        webRequestAdapter = webRequestAdapter,
        scriptingAdapter = scriptingAdapter,
        actionAdapter = actionAdapter,
        contextMenusAdapter = contextMenusAdapter,
        commandsAdapter = commandsAdapter,
        omniboxAdapter = omniboxAdapter,
        sidePanelAdapter = sidePanelAdapter,
        managementAdapter = managementAdapter,
        topSitesAdapter = topSitesAdapter,
        idleAdapter = idleAdapter,
        ttsAdapter = ttsAdapter,
        searchAdapter = searchAdapter,
        alarmsAdapter = alarmsAdapter,
        systemAdapter = systemAdapter,
        notificationsAdapter = notificationsAdapter,
        browsingDataAdapter = browsingDataAdapter,
        context = context,
        delegate = delegate,
        webNavigationAdapter = webNavigationAdapter,
        privacyAdapter = privacyAdapter,
        contentSettingsAdapter = contentSettingsAdapter,
        offscreenAdapter = offscreenAdapter,
        userScriptsAdapter = userScriptsAdapter,
        powerAdapter = powerAdapter,
        debuggerAdapter = debuggerAdapter
    )

    var onWorkerCrash: ((String, String) -> Unit)? = null

    init {
        permissionsAdapter.setRegistry(registry)
        permissionsAdapter.setEventManager(eventManager)
        alarmsAdapter.serviceWorkerEventDispatcher = serviceWorkerEventDispatcher
        messageBus.registerListener(this)
        messageBus.registerPortListener(this)
        scriptingAdapter.setFrameTargetRegistry(webNavigationAdapter.stateMachine)
    }

    fun buildSender(extensionId: String): ExtensionSender {
        val url = webView?.url
        val isExtUrl = com.swift.browser.extensionengine.origin.ExtensionUrl.isExtensionUrl(url)
        val contextType = contextTypeOverride ?: when {
            isExtUrl -> {
                val lower = (url ?: "").lowercase()
                when {
                    lower.contains("popup") -> ExtensionContextType.POPUP
                    lower.contains("options") -> ExtensionContextType.OPTIONS
                    lower.contains("sidepanel") || lower.contains("side_panel") -> ExtensionContextType.SIDE_PANEL
                    lower.contains("devtools") -> ExtensionContextType.DEVTOOLS
                    lower.contains("background") -> ExtensionContextType.BACKGROUND
                    else -> ExtensionContextType.EXTENSION_PAGE
                }
            }
            tabId != null -> ExtensionContextType.CONTENT_SCRIPT
            else -> ExtensionContextType.WEB_PAGE
        }
        val origin = if (url != null && url.startsWith("http")) {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}${if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
        } else url

        val effectiveTabId = when (contextType) {
            ExtensionContextType.SERVICE_WORKER, ExtensionContextType.BACKGROUND -> null
            else -> tabId ?: delegate?.getActiveTabId()
        }

        val effectiveFrameId = frameId ?: 0
        val effectiveDocumentId = documentId ?: effectiveTabId?.let { id ->
            webNavigationAdapter.stateMachine.getFrameIdentity(id, effectiveFrameId)?.documentId
        }
        return ExtensionSender(
            extensionId = extensionId,
            tabId = effectiveTabId,
            windowId = null,
            frameId = effectiveFrameId,
            documentId = effectiveDocumentId,
            url = url,
            origin = origin,
            contextType = contextType,
            isPrivate = isPrivate,
            privateSessionId = privateSessionId
        )
    }

    private fun validateCaller(extensionId: String): Boolean {
        val cleanId = extensionId.lowercase().trim()
        if (cleanId.isBlank()) return false

        val ext = registry.getExtension(cleanId) ?: return false
        if (!registry.isExtensionEnabled(cleanId)) return false

        if (isPrivate && !permissionsAdapter.isAllowedInPrivate(cleanId)) {
            return false
        }

        // If this bridge is bound directly to an extension UI surface (Popup, Options, SidePanel, Background)
        if (ownExtensionId != null && ownExtensionId.equals(cleanId, ignoreCase = true)) {
            return true
        }

        // Native service workers intentionally have no WebView. Their caller
        // identity is established by the execution backend, so require extension
        // registration/enablement above but do not demand a page URL or host permission.
        if (webView == null && (contextTypeOverride == ExtensionContextType.SERVICE_WORKER ||
                    contextTypeOverride == ExtensionContextType.BACKGROUND)) {
            return true
        }

        val url = webView?.url
        if (url.isNullOrBlank() || url == "about:blank") {
            // When document is starting or within an extension context
            if (contextTypeOverride != null && contextTypeOverride != ExtensionContextType.WEB_PAGE) {
                return true
            }
            return false
        }

        val lowerUrl = url.lowercase().trim()
        if (lowerUrl.startsWith("file://") || lowerUrl.startsWith("content://")) {
            return false
        }

        val urlResult = com.swift.browser.extensionengine.origin.ExtensionUrl.parseExtensionUrl(url)
        if (urlResult != null && urlResult.extensionId.equals(cleanId, ignoreCase = true)) {
            if (urlResult.isSandbox || com.swift.browser.extensionengine.origin.ExtensionOriginValidator.validate(
                    extensionId = cleanId,
                    urlStr = url,
                    expectedContext = com.swift.browser.extensionengine.security.ExtensionPageType.EXTENSION_PAGE,
                    isPrivate = isPrivate,
                    registry = registry,
                    permissionManager = permissionManager
                ) == com.swift.browser.extensionengine.origin.ExtensionOriginValidator.ValidationResult.SANDBOX_PAGE_ISOLATED
            ) {
                return false
            }
            return true
        }

        return permissionManager.hasHostPermission(cleanId, ext.hostPermissions, ext.permissions, url)
    }


    /** Entry point used by the origin-scoped WebMessage transport. */
    fun postWebMessage(
        payloadJsonStr: String,
        callbackId: String = "",
        replyProxy: JavaScriptReplyProxy? = null
    ) {
        postMessage(payloadJsonStr, callbackId, replyProxy)
    }

    @JavascriptInterface
    fun postMessage(
        payloadJsonStr: String,
        callbackId: String,
        replyProxy: JavaScriptReplyProxy? = null
    ) {
        val root = try {
            JSONObject(payloadJsonStr)
        } catch (e: Exception) {
            return
        }

        val api = root.optString("api", "")
        val extensionId = root.optString("extensionId", "")
        val args = root.optJSONArray("args") ?: JSONArray()
        val runtimeGenerationId = root.optString("runtimeGenerationId", "gen1")
        if (callbackId.isNotBlank()) {
            val requestSender = buildSender(extensionId)
            pendingBridgeRequests[callbackId] = PendingBridgeRequest(
                requestSender.tabId, requestSender.frameId ?: 0, requestSender.documentId, replyProxy
            )
        }

        val startTime = System.currentTimeMillis()
        val extName = registry.getExtension(extensionId)?.name ?: extensionId

        mainScope.launch {
            try {
                if (!validateCaller(extensionId)) {
                    val errMsg = "SecurityError: Invalid execution context or missing host permission for extension '$extensionId'."
                    ExtensionDebuggerEngine.instance.recordEvent(
                        ExtensionDiagnosticEvent(
                            timestamp = startTime,
                            extensionId = extensionId,
                            extensionName = extName,
                            context = (contextTypeOverride ?: ExtensionContextType.CONTENT_SCRIPT).name,
                            tabId = tabId?.toIntOrNull(),
                            frameId = frameId,
                            apiOrEventName = api,
                            category = if (api.startsWith("permissions.")) "PERMISSION" else "API",
                            level = "ERROR",
                            latencyMs = System.currentTimeMillis() - startTime,
                            success = false,
                            errorMessage = errMsg
                        )
                    )
                    sendErrorResponse(callbackId, errMsg)
                    return@launch
                }
                handleApiCall(api, extensionId, args, callbackId, runtimeGenerationId)
                
                ExtensionDebuggerEngine.instance.recordEvent(
                    ExtensionDiagnosticEvent(
                        timestamp = startTime,
                        extensionId = extensionId,
                        extensionName = extName,
                        context = (contextTypeOverride ?: ExtensionContextType.CONTENT_SCRIPT).name,
                        tabId = tabId?.toIntOrNull(),
                        frameId = frameId,
                        apiOrEventName = api,
                        category = when {
                            api.startsWith("storage.") -> "STORAGE"
                            api.startsWith("cookies.") -> "STORAGE"
                            api.startsWith("declarativeNetRequest.") || api.startsWith("webRequest.") -> "NETWORK"
                            api.startsWith("scripting.") -> "CONTENT_SCRIPT"
                            else -> "API"
                        },
                        level = "INFO",
                        latencyMs = System.currentTimeMillis() - startTime,
                        success = true,
                        details = "Args count: ${args.length()}"
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
                val errStr = e.localizedMessage ?: e.toString()
                ExtensionDebuggerEngine.instance.recordEvent(
                    ExtensionDiagnosticEvent(
                        timestamp = startTime,
                        extensionId = extensionId,
                        extensionName = extName,
                        context = (contextTypeOverride ?: ExtensionContextType.CONTENT_SCRIPT).name,
                        tabId = tabId?.toIntOrNull(),
                        frameId = frameId,
                        apiOrEventName = api,
                        category = "EXCEPTION",
                        level = "ERROR",
                        latencyMs = System.currentTimeMillis() - startTime,
                        success = false,
                        errorMessage = errStr
                    )
                )
                sendErrorResponse(callbackId, errStr)
            }
        }
    }

    private suspend fun handleApiCall(
        api: String,
        extensionId: String,
        args: JSONArray,
        callbackId: String,
        runtimeGenerationId: String
    ) {
        val sender = buildSender(extensionId)

        when (api) {
            "event.addListener" -> {
                val eventName = args.optString(0, "")
                val listenerId = args.optString(1, "")
                val filter = args.optJSONObject(2)
                val extraSpec = args.optJSONArray(3)
                jsEventRegistry.addListener(
                    extensionId = extensionId,
                    runtimeGenerationId = runtimeGenerationId,
                    eventName = eventName,
                    listenerId = listenerId,
                    filter = filter,
                    extraInfoSpec = extraSpec,
                    isBackgroundContext = isBackgroundExecutionContext(extensionId)
                )
                sendSuccessResponse(callbackId, JSONObject().put("status", "added"))
            }
            "event.removeListener" -> {
                val eventName = args.optString(0, "")
                val listenerId = args.optString(1, "")
                jsEventRegistry.removeListener(extensionId, eventName, listenerId)
                sendSuccessResponse(callbackId, JSONObject().put("status", "removed"))
            }
            "runtime.sendMessage" -> {
                val targetExtId = if (args.length() > 1 && args.opt(0) is String) args.optString(0) else extensionId
                val rawMsg = if (args.length() > 1 && args.opt(0) is String) args.opt(1) else args.opt(0)
                val message = if (rawMsg is JSONObject) rawMsg else JSONObject().put("__is_wrapped__", true).put("value", rawMsg)

                val router = messageRouter
                if (router != null) {
                    router.handleSendMessage(
                        sender = sender,
                        targetExtensionId = targetExtId,
                        payload = message,
                        callbackId = callbackId,
                        expectsResponse = callbackId.isNotBlank()
                    ) { response, error ->
                        if (error != null) {
                            sendErrorResponse(callbackId, error)
                        } else {
                            sendSuccessResponse(callbackId, response ?: JSONObject())
                        }
                    }
                } else {
                    messageBus.broadcastMessage(sender, message, callbackId)
                }
            }
            "runtime.response", "runtime.sendResponse" -> {
                val targetCallbackId = if (args.length() > 2) args.optString(1, "") else args.optString(0, "")
                val responseData = if (args.length() > 2) (args.opt(2) ?: JSONObject()) else (args.opt(1) ?: JSONObject())
                val router = messageRouter
                if (router != null) {
                    router.handleSendResponse(extensionId, targetCallbackId, responseData)
                }
                messageBus.broadcastResponse(extensionId, targetCallbackId, responseData)
                sendSuccessResponse(callbackId, JSONObject().put("status", "ok"))
            }
            "tabs.sendMessage" -> {
                val targetTabIdRaw = args.opt(0)
                val rawMsg = args.opt(1)
                val message = if (rawMsg is JSONObject) rawMsg else JSONObject().put("__is_wrapped__", true).put("value", rawMsg)
                val targetTabId = TabIdMapper.getUuidFromString(targetTabIdRaw?.toString() ?: "")

                val router = messageRouter
                if (router != null) {
                    val options = (args.opt(2) as? JSONObject)
                    val targetFrameId = try {
                        options?.optInt("frameId", -1)?.takeIf { it >= 0 }
                    } catch (_: Exception) { null }
                    val targetDocumentId = options?.optString("documentId", null)?.trim()?.takeIf { !it.isNullOrBlank() }
                    if (targetFrameId != null && targetDocumentId != null) {
                        sendErrorResponse(callbackId, "TAB_MESSAGE_TARGET_CONFLICT")
                        return
                    }
                    router.handleSendMessage(
                        sender = sender,
                        targetExtensionId = extensionId,
                        payload = message,
                        callbackId = callbackId,
                        expectsResponse = callbackId.isNotBlank(),
                        targetTabId = targetTabId,
                        targetFrameId = targetFrameId,
                        targetDocumentId = targetDocumentId
                    ) { response, error ->
                        if (error != null) {
                            sendErrorResponse(callbackId, error)
                        } else {
                            sendSuccessResponse(callbackId, response ?: JSONObject())
                        }
                    }
                } else {
                    messageBus.broadcastMessage(sender, message, callbackId, targetTabId)
                }
            }
            "runtime.portConnect", "tabs.connect" -> {
                val targetExtId = if (api == "tabs.connect") extensionId else args.optString(0, extensionId)
                val channelId = args.optString(1, "")
                val portName = args.optString(2, "")
                val targetTabId = if (api == "tabs.connect") TabIdMapper.getUuidFromString(args.optString(0, "")) else null
                val targetFrameId = if (api == "tabs.connect") args.optInt(3, -1).takeIf { it >= 0 } else null
                val targetDocumentId = if (api == "tabs.connect") args.optString(4, "").trim().ifBlank { null } else null
                if (api == "tabs.connect" && targetFrameId != null && !targetDocumentId.isNullOrBlank()) {
                    sendErrorResponse(callbackId, "INVALID_TARGET: frameId and documentId are mutually exclusive.")
                    return
                }
                val targetContext = if (api == "tabs.connect") {
                    ExtensionContextType.CONTENT_SCRIPT
                } else {
                    val target = registry.getExtension(targetExtId)
                    if (target?.isServiceWorker == true) ExtensionContextType.SERVICE_WORKER else ExtensionContextType.BACKGROUND
                }

                if (portManager != null) {
                    if (targetExtId.isBlank() || registry.getExtension(targetExtId) == null || !registry.isExtensionEnabled(targetExtId)) {
                        sendErrorResponse(callbackId, MessagingError.EXTENSION_NOT_FOUND)
                        return
                    }
                    if (!sender.extensionId.equals(targetExtId, ignoreCase = true)) {
                        val allowed = messageRouter?.checkExternallyConnectable(sender, targetExtId) == true
                        if (!allowed) {
                            sendErrorResponse(callbackId, MessagingError.EXTERNAL_CONNECTION_DENIED)
                            return
                        }
                    }
                    val isServiceWorkerTarget = targetContext == ExtensionContextType.SERVICE_WORKER
                    val isMv2BackgroundTarget = targetContext == ExtensionContextType.BACKGROUND &&
                        registry.getExtension(targetExtId)?.manifestVersion == 2

                    val connectNow = {
                        portManager.connect(
                            extensionId = targetExtId,
                            channelId = channelId,
                            portName = portName,
                            senderId = sender.extensionId,
                            sender = sender,
                            tabId = sender.tabId,
                            frameId = sender.frameId,
                            targetContextType = targetContext,
                            targetTabId = targetTabId,
                            targetFrameId = targetFrameId,
                            targetDocumentId = targetDocumentId,
                            emitConnect = !isServiceWorkerTarget
                        )
                        if (isServiceWorkerTarget) {
                            serviceWorkerEventDispatcher?.dispatchPortConnectEvent(
                                extensionId = targetExtId,
                                channelId = channelId,
                                portName = portName,
                                senderId = sender.extensionId
                            )
                        }
                        backgroundScriptManager?.notifyPortActivityChanged(
                            targetExtId, sender.isPrivate, sender.privateSessionId
                        )
                        sendSuccessResponse(callbackId, JSONObject().put("status", "connected"))
                    }

                    if (isMv2BackgroundTarget && backgroundScriptManager != null) {
                        backgroundScriptManager.wakeForMessage(
                            extensionId = targetExtId,
                            bootstrapScript = "",
                            isPrivate = sender.isPrivate,
                            privateSessionId = sender.privateSessionId,
                            permissionManager = permissionManager
                        ) { success ->
                            if (success) connectNow()
                            else sendErrorResponse(callbackId, MessagingError.MESSAGE_TARGET_UNAVAILABLE)
                        }
                    } else {
                        connectNow()
                    }
                }
            }
            "runtime.portPostMessage" -> {
                val channelId = args.optString(0, "")
                val rawMsg = args.opt(1)
                val message = if (rawMsg is JSONObject) rawMsg else JSONObject().put("__is_wrapped__", true).put("value", rawMsg)

                if (portManager != null) {
                    portManager.postMessage(channelId, message, sender.extensionId)
                }
                sendSuccessResponse(callbackId, JSONObject().put("status", "sent"))
            }
            "runtime.portDisconnect" -> {
                val channelId = args.optString(0, "")
                if (portManager != null) {
                    val connection = portManager.registry.get(channelId)
                    portManager.disconnect(channelId, sender.extensionId)
                    connection?.targetId?.let { targetId ->
                        if (registry.getExtension(targetId)?.manifestVersion == 2) {
                            backgroundScriptManager?.notifyPortActivityChanged(
                                targetId, sender.isPrivate, sender.privateSessionId
                            )
                        }
                    }
                }
                sendSuccessResponse(callbackId, JSONObject().put("status", "disconnected"))
            }
            "runtime.getBackgroundPage" -> {
                val ext = registry.getExtension(extensionId)
                if (ext == null || ext.manifestVersion != 2 || ext.backgroundScripts.isEmpty() && ext.backgroundSpec.page.isBlank()) {
                    sendErrorResponse(callbackId, "BACKGROUND_PAGE_UNAVAILABLE")
                    return
                }
                val manager = backgroundScriptManager
                if (manager == null) {
                    sendErrorResponse(callbackId, "BACKGROUND_PAGE_UNAVAILABLE")
                    return
                }
                manager.wakeForMessage(
                    extensionId = extensionId,
                    bootstrapScript = "",
                    isPrivate = isPrivate,
                    privateSessionId = privateSessionId,
                    permissionManager = permissionManager
                ) { ready ->
                    if (ready) {
                        sendSuccessResponse(
                            callbackId,
                            JSONObject().apply {
                                put("available", true)
                                put("extensionId", extensionId)
                                put("url", "chrome-extension://$extensionId/")
                            }
                        )
                    } else {
                        sendErrorResponse(callbackId, "BACKGROUND_PAGE_UNAVAILABLE")
                    }
                }
            }
            "runtime.backgroundPageCall" -> {
                val manager = backgroundScriptManager
                val functionName = args.optString(0, "")
                val callArgs = args.optJSONArray(1) ?: JSONArray()
                if (manager == null) {
                    sendErrorResponse(callbackId, "BACKGROUND_PAGE_UNAVAILABLE")
                    return
                }
                manager.invokeBackgroundFunction(
                    extensionId = extensionId,
                    functionName = functionName,
                    args = callArgs,
                    isPrivate = isPrivate,
                    privateSessionId = privateSessionId
                ) { ok, value, error ->
                    if (ok) sendSuccessResponse(callbackId, value ?: JSONObject.NULL)
                    else sendErrorResponse(callbackId, error ?: "BACKGROUND_PAGE_UNAVAILABLE")
                }
            }
            "runtime.reportCrash" -> {
                val reason = args.optString(0, "unknown")
                onWorkerCrash?.invoke(extensionId, reason)
                sendSuccessResponse(callbackId, JSONObject().put("status", "reported"))
            }
            else -> {
                val result = jsRouter.handleCall(sender, api, args, isPrivate, privateSessionId)
                if (result != null) {
                    sendSuccessResponse(callbackId, result)
                } else {
                    sendSuccessResponse(callbackId, JSONObject().put("status", "success"))
                }
            }
        }
    }

    private fun isBackgroundExecutionContext(extensionId: String): Boolean {
        val override = contextTypeOverride
            ?: (webView?.tag as? ExtensionContextType)
        if (override == ExtensionContextType.BACKGROUND || override == ExtensionContextType.SERVICE_WORKER) return true
        val own = ownExtensionId
        return own != null && own.equals(extensionId, ignoreCase = true) &&
            buildSender(own).contextType == ExtensionContextType.BACKGROUND
    }

    val ownExtensionId: String? by lazy {
        forcedOwnExtensionId?.trim()?.takeIf { it.isNotBlank() } ?: run {
            val url = webView?.url ?: return@run null
            val res = com.swift.browser.extensionengine.origin.ExtensionUrl.parseExtensionUrl(url)
            res?.extensionId
        }
    }

    override fun onStructuredMessageReceived(
        sender: ExtensionSender,
        message: JSONObject,
        callbackId: String?,
        targetTabId: String?
    ) {
        val view = webView ?: return
        val tab = tabId

        val targetExtensionId = if (message.has("__targetExtensionId__")) message.optString("__targetExtensionId__") else null
        val explicitTargetTabId = message.optString("__targetTabId__", null)
        val explicitTargetFrameId = if (message.has("__targetFrameId__")) message.optInt("__targetFrameId__", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null
        val explicitTargetDocumentId = message.optString("__targetDocumentId__", null)?.trim()?.takeIf { !it.isNullOrBlank() }
        val targetContextType = message.optString("__targetContextType__", null)
        val realPayload = message.optJSONObject("__payload__") ?: message

        val ownExtId = ownExtensionId
        if (ownExtId != null) {
            if (targetExtensionId != null && !ownExtId.equals(targetExtensionId, ignoreCase = true)) return
            if (targetContextType != null && buildSender(ownExtId).contextType.name != targetContextType) return
        } else {
            val resolvedTargetTab = explicitTargetTabId ?: targetTabId
            if (resolvedTargetTab != null) {
                if (tab != resolvedTargetTab) return
            } else if (tab != null) {
                return
            }
            if (explicitTargetFrameId != null && frameId != explicitTargetFrameId) return
            if (explicitTargetDocumentId != null) {
                val currentDocumentId = documentId
                if (currentDocumentId == null || currentDocumentId != explicitTargetDocumentId) return
            }
        }

        view.post {
            try {
                val isWrapped = realPayload.optBoolean("__is_wrapped__", false)
                val msgStr = if (isWrapped) {
                    val wrappedVal = realPayload.opt("value")
                    if (wrappedVal is String) {
                        JSONObject.quote(wrappedVal)
                    } else {
                        wrappedVal?.toString() ?: "null"
                    }
                } else {
                    realPayload.toString()
                }
                val senderJson = sender.toJSONObject().toString()
                val cbParam = callbackId?.let { JSONObject.quote(it) } ?: "null"
                val targetExtToPass = JSONObject.quote(targetExtensionId ?: ownExtId ?: sender.extensionId)

                view.evaluateJavascript(
                    "if(window._extOnMessage) { window._extOnMessage($targetExtToPass, $msgStr, $senderJson, $cbParam); }",
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onMessageReceived(extensionId: String, senderTabId: String?, message: JSONObject, callbackId: String?, targetTabId: String?) {
        val sender = ExtensionSender(extensionId = extensionId, tabId = senderTabId)
        onStructuredMessageReceived(sender, message, callbackId, targetTabId)
    }

    override fun onResponseReceived(extensionId: String, callbackId: String, response: Any) {
        val ownExtId = ownExtensionId
        if (ownExtId != null && !ownExtId.equals(extensionId, ignoreCase = true)) {
            return
        }
        val view = webView ?: return
        view.post {
            try {
                val resStr = if (response is String) {
                    JSONObject.quote(response)
                } else {
                    response.toString()
                }
                val cbJs = JSONObject.quote(callbackId)
                view.evaluateJavascript(
                    "if(window._extResponse) { window._extResponse($cbJs, null, $resStr); }",
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPortConnect(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String,
        targetContextType: ExtensionContextType?,
        targetTabId: String?,
        targetFrameId: Int?,
        targetDocumentId: String?
    ) {
        val view = webView ?: return
        val own = ownExtensionId
        if (own != null && !own.equals(extensionId, ignoreCase = true)) return
        if (targetContextType != null) {
            val ownContext = own?.let { buildSender(it).contextType }
            if (ownContext != null && ownContext != targetContextType) return
        }
        if (targetTabId != null && tabId != targetTabId) return
        if (targetFrameId != null && frameId != targetFrameId) return
        if (!targetDocumentId.isNullOrBlank()) {
            val currentDocumentId = own?.let { buildSender(it).documentId }
            if (currentDocumentId != targetDocumentId) return
        }
        view.post {
            try {
                val channelJs = JSONObject.quote(channelId)
                val portNameJs = JSONObject.quote(portName)
                val senderInfo = JSONObject().apply {
                    put("id", senderId)
                    put("extensionId", senderId)
                }.toString()
                view.evaluateJavascript(
                    "if(window._extPortConnect) { window._extPortConnect($channelJs, $portNameJs, $senderInfo); }",
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPortMessage(
        channelId: String,
        message: JSONObject,
        targetExtensionId: String?,
        targetContextType: ExtensionContextType?,
        targetTabId: String?,
        targetFrameId: Int?,
        targetDocumentId: String?
    ) {
        val view = webView ?: return
        if (targetExtensionId != null && ownExtensionId != null && !ownExtensionId.equals(targetExtensionId, ignoreCase = true)) return
        if (targetContextType != null) {
            val currentContext = ownExtensionId?.let { buildSender(it).contextType }
                ?: if (tabId != null) ExtensionContextType.CONTENT_SCRIPT else ExtensionContextType.WEB_PAGE
            if (currentContext != targetContextType) return
        }
        if (targetTabId != null && tabId != targetTabId) return
        if (targetFrameId != null && frameId != targetFrameId) return
        if (!targetDocumentId.isNullOrBlank()) {
            val currentDocumentId = ownExtensionId?.let { buildSender(it).documentId }
            if (currentDocumentId != targetDocumentId) return
        }
        view.post {
            try {
                val isWrapped = message.optBoolean("__is_wrapped__", false)
                val msgStr = if (isWrapped) {
                    val wrappedVal = message.opt("value")
                    if (wrappedVal is String) {
                        JSONObject.quote(wrappedVal)
                    } else {
                        wrappedVal?.toString() ?: "null"
                    }
                } else {
                    message.toString()
                }
                val channelJs = JSONObject.quote(channelId)
                view.evaluateJavascript(
                    "if(window._extPortMessage) { window._extPortMessage($channelJs, $msgStr); }",
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPortDisconnect(
        channelId: String,
        targetExtensionId: String?,
        targetContextType: ExtensionContextType?,
        targetTabId: String?,
        targetFrameId: Int?,
        targetDocumentId: String?
    ) {
        val view = webView ?: return
        if (targetExtensionId != null && ownExtensionId != null && !ownExtensionId.equals(targetExtensionId, ignoreCase = true)) return
        if (targetContextType != null) {
            val currentContext = ownExtensionId?.let { buildSender(it).contextType }
                ?: if (tabId != null) ExtensionContextType.CONTENT_SCRIPT else ExtensionContextType.WEB_PAGE
            if (currentContext != targetContextType) return
        }
        if (targetTabId != null && tabId != targetTabId) return
        if (targetFrameId != null && frameId != targetFrameId) return
        if (!targetDocumentId.isNullOrBlank()) {
            val currentDocumentId = ownExtensionId?.let { buildSender(it).documentId }
            if (currentDocumentId != targetDocumentId) return
        }
        view.post {
            try {
                val channelJs = JSONObject.quote(channelId)
                view.evaluateJavascript(
                    "if(window._extPortDisconnect) { window._extPortDisconnect($channelJs); }",
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun failPendingForDocument(tabId: String?, frameId: Int, documentId: String?, reason: String = DOCUMENT_DESTROYED) {
        val matching = pendingBridgeRequests.entries.filter { (_, pending) ->
            (tabId == null || pending.tabId == tabId) && pending.frameId == frameId &&
                (documentId.isNullOrBlank() || pending.documentId == documentId)
        }
        matching.forEach { (callbackId, _) ->
            if (pendingBridgeRequests.remove(callbackId) != null) sendErrorResponse(callbackId, reason)
        }
        portManager?.cleanupForFrame(tabId ?: this.tabId ?: return, frameId)
    }

    private fun sendSuccessResponse(callbackId: String, result: Any) {
        val pending = pendingBridgeRequests.remove(callbackId)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val resStr = result.toString()
                val proxy = pending?.replyProxy
                if (proxy != null) {
                    proxy.postMessage(
                        JSONObject().put("type", "api-response")
                            .put("callbackId", callbackId)
                            .put("resultJson", resStr)
                            .toString()
                    )
                    return@post
                }
                webView?.evaluateJavascript("window._extResponse(${JSONObject.quote(callbackId)}, null, $resStr)", null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendErrorResponse(callbackId: String, errorMsg: String) {
        val pending = pendingBridgeRequests.remove(callbackId)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val proxy = pending?.replyProxy
                if (proxy != null) {
                    proxy.postMessage(
                        JSONObject().put("type", "api-response")
                            .put("callbackId", callbackId)
                            .put("error", errorMsg)
                            .toString()
                    )
                    return@post
                }
                val errObj = JSONObject().put("error", errorMsg).toString()
                val cbJs = JSONObject.quote(callbackId)
                webView?.evaluateJavascript("window._extResponse($cbJs, $errObj, null)", null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

