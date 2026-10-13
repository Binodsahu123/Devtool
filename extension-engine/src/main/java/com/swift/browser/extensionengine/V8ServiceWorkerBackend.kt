package com.swift.browser.extensionengine

import android.content.Context
import android.util.Log
import com.swift.browser.extensionv8.V8Runtime
import com.swift.browser.extensionv8.V8WorkerBootstrap
import com.swift.browser.extensionv8.V8RuntimeConfig
import com.swift.browser.extensionv8.V8RuntimeInterface
import com.swift.browser.extensionv8.NativeV8RuntimeCoordinator
import com.swift.browser.extensionv8.withLogging
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ServiceWorkerExecutionBackend implementation using Native V8 Background Execution Runtime.
 */
class V8ServiceWorkerBackend(
    private val context: Context,
    private val v8RuntimeProvider: () -> V8RuntimeInterface = { V8Runtime(context, V8RuntimeConfig()).withLogging() }
) : ServiceWorkerExecutionBackend, MessageListener, PortConnectionListener {

    companion object {
        private const val TAG = "OrionV8"
        private const val MAX_WORKER_FETCH_BYTES = 16L * 1024L * 1024L
    }

    private val v8RuntimeHolder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val rt = v8RuntimeProvider()
        rt.workerManager.onHostApiCall = { extensionId, generationId, apiName, requestId, payloadJson ->
            handleHostApiCall(extensionId, generationId, apiName, requestId, payloadJson)
        }
        rt.workerManager.onWorkerCrash = { extensionId, reason ->
            val generation = serviceWorkerRegistry?.getWorkerGeneration(extensionId) ?: 0
            // Clear NativeV8RuntimeCoordinator ownership before the higher-level lifecycle
            // manager decides whether the worker should enter restart backoff.
            if (generation > 0) {
                nativeRuntimeCoordinator.onWorkerCrashed(extensionId, generation)
            }
            val worker = serviceWorkerRegistry?.getWorker(extensionId)
            worker?.keepaliveReasons?.clear()
            portManager?.cleanupForExtension(extensionId)
            onWorkerCrashListener?.invoke(extensionId, reason)
        }
        rt
    }

    val v8Runtime: V8RuntimeInterface
        get() = v8RuntimeHolder

    private val nativeRuntimeCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NativeV8RuntimeCoordinator(v8Runtime)
    }

    fun isV8Available(): Boolean = synchronized(this) {
        if (!isRuntimeInstantiated()) return false
        return try {
            v8Runtime.isV8Supported()
        } catch (_: Throwable) {
            false
        }
    }

    fun isRuntimeInstantiated(): Boolean = synchronized(this) {
        // Check if lazy holder is initialized
        try {
            val field = javaClass.getDeclaredField("v8RuntimeHolder\$delegate")
            field.isAccessible = true
            val lazyVal = field.get(this) as? Lazy<*>
            lazyVal?.isInitialized() ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private var runtimeBridgeProvider: ((Any) -> Any)? = null
    private var contextAwareRuntimeBridgeProvider: ((Any, ExtensionContextType) -> Any)? = null
    private var networkPermissionChecker: ((String, String, Boolean) -> Boolean)? = null
    private var webRequestAdapter: ExtensionWebRequestAdapter? = null
    private var onWorkerCrashListener: ((String, String) -> Unit)? = null
    private var eventManager: EventManager? = null
    private val workerPrivateModes = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    var messageRouter: ExtensionMessageRouter? = null
    var messageBus: MessageBus? = null
        private set
    var portManager: PortManager? = null
        private set
    var serviceWorkerRegistry: ServiceWorkerRegistry? = null
        private set
    var wakeController: ServiceWorkerWakeController? = null
        private set
    var extensionRegistry: ExtensionRegistry? = null
        private set

    init {
        Log.i(TAG, "V8_BACKEND_CREATED: V8ServiceWorkerBackend descriptor created (V8 runtime deferred)")
    }

    fun setMessagingInfrastructure(
        messageBus: MessageBus,
        portManager: PortManager,
        serviceWorkerRegistry: ServiceWorkerRegistry,
        wakeController: ServiceWorkerWakeController,
        extensionRegistry: ExtensionRegistry? = null,
        eventManager: EventManager? = null,
        messageRouter: ExtensionMessageRouter? = null
    ) {
        this.messageBus = messageBus
        this.portManager = portManager
        this.serviceWorkerRegistry = serviceWorkerRegistry
        this.wakeController = wakeController
        this.extensionRegistry = extensionRegistry
        this.eventManager = eventManager
        this.messageRouter = messageRouter
        messageBus.registerListener(this)
        messageBus.registerPortListener(this)
    }

    override fun getBackendType(): ServiceWorkerBackendType = ServiceWorkerBackendType.V8

    override fun startWorker(
        parsedExtension: ParsedExtension,
        scriptPath: String,
        generationId: Int,
        bootstrapScript: String,
        isPrivate: Boolean,
        onStartupResult: (WorkerStartupResult, String?) -> Unit
    ) {
        Log.i(TAG, "V8_WORKER_REQUEST: Extension ${parsedExtension.id} requested V8 worker start")
        val runtimeResult = nativeRuntimeCoordinator.ensureWorkerRuntime(
            parsedExtension.id,
            generationId
        )
        if (runtimeResult.isFailure) {
            val failureReason = runtimeResult.exceptionOrNull()?.message ?: v8Runtime.backendStatus()
            Log.w(TAG, "V8_WORKER_FAILED: No usable Native V8 runtime for MV3 extension ${parsedExtension.id}; status=$failureReason")
            onStartupResult(WorkerStartupResult.FAILED, "No usable Native V8 runtime available ($failureReason)")
            return
        }

        val extensionId = parsedExtension.id
        workerPrivateModes[extensionId] = isPrivate
        val requestedModule = parsedExtension.backgroundSpec.type.equals("module", ignoreCase = true)

        try {
            // Load extension worker script content. Native V8 executes ES modules natively.
            val rawScriptContent = loadExtensionScript(parsedExtension, scriptPath)
            val scriptContent = rawScriptContent
            val isModule = requestedModule
            // Provide a source index for both module resolution and synchronous classic
            // importScripts(). Native code still reads from the extension root defensively;
            // the index is an optimization and a deterministic fallback for packaged sources.
            val moduleSourcesBlob = buildModuleSourcesBlob(parsedExtension, scriptPath, rawScriptContent)

            // Native worker contexts have their own worker-global bootstrap. The browser WebView
            // bootstrap is intentionally not reused here because it depends on window/document.
            val workerBootstrap = V8WorkerBootstrap.buildDefaultBootstrapScript(extensionId, parsedExtension.manifestJson)

            Log.i(TAG, "V8_WORKER_START: Launching V8 isolate for extension $extensionId (gen=$generationId)")
            val extensionRootPath = ExtensionDirectoryResolver
                .getExtensionDir(context, parsedExtension.id, parsedExtension.name)
                .canonicalPath
            v8Runtime.workerManager.startWorker(
                extensionId = extensionId,
                scriptPath = scriptPath,
                generationId = generationId,
                bootstrapScript = workerBootstrap,
                scriptContent = scriptContent,
                isModule = isModule,
                isPrivate = isPrivate,
                moduleSourcesBlob = moduleSourcesBlob,
                extensionRootPath = extensionRootPath,
                onStartupResult = { success, errorMsg ->
                    if (success) {
                        Log.i(TAG, "V8_WORKER_READY: Extension $extensionId worker successfully started and ready in V8 isolate")
                        onStartupResult(WorkerStartupResult.SUCCESS, null)
                    } else {
                        Log.w(TAG, "V8_WORKER_FAILED: Extension $extensionId worker failed to start: $errorMsg")
                        onStartupResult(WorkerStartupResult.FAILED, errorMsg ?: "V8 worker startup failed")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error launching V8 worker for extension $extensionId", t)
            onStartupResult(WorkerStartupResult.FAILED, t.message ?: "Failed to prepare V8 worker script")
        }
    }

    override fun evaluateEvent(
        extensionId: String,
        generationId: Int,
        eventName: String,
        payload: Any?,
        callback: ((Boolean) -> Unit)?
    ) {
        v8Runtime.workerManager.evaluateEvent(
            extensionId = extensionId,
            generationId = generationId,
            eventName = eventName,
            payload = payload,
            callback = callback
        )
    }

    override fun stopWorker(extensionId: String) {
        val generation = serviceWorkerRegistry?.getWorkerGeneration(extensionId) ?: 0
        nativeRuntimeCoordinator.onWorkerStopped(extensionId, generation)
        workerPrivateModes.remove(extensionId)
        val worker = serviceWorkerRegistry?.getWorker(extensionId)
        worker?.keepaliveReasons?.clear()
        portManager?.cleanupForExtension(extensionId)
        v8Runtime.workerManager.stopWorker(extensionId)
    }

    override fun stopAll() {
        workerPrivateModes.clear()
        v8Runtime.workerManager.stopAll()
    }

    /** Releases backend-owned asynchronous bridge work without forcing Native V8 to load. */
    fun shutdown() {
        workerPrivateModes.clear()
        runCatching { v8Runtime.workerManager.stopAll() }
        runCatching { messageBus?.unregisterListener(this) }
        runCatching { messageBus?.unregisterPortListener(this) }
    }

    override fun isWorkerRunning(extensionId: String): Boolean {
        return v8Runtime.workerManager.isWorkerRunning(extensionId)
    }

    override fun getWorkerGeneration(extensionId: String): Int {
        return v8Runtime.workerManager.getWorkerGeneration(extensionId)
    }

    override fun setRuntimeBridgeProvider(provider: (Any) -> Any) {
        this.runtimeBridgeProvider = provider
    }

    override fun setContextAwareRuntimeBridgeProvider(provider: (Any, ExtensionContextType) -> Any) {
        this.contextAwareRuntimeBridgeProvider = provider
    }

    override fun setNetworkPermissionChecker(checker: (String, String, Boolean) -> Boolean) {
        this.networkPermissionChecker = checker
    }

    /** Connects the real V8 fetch executor to the existing DNR/WebRequest pipeline. */
    fun setWebRequestAdapter(adapter: ExtensionWebRequestAdapter) {
        this.webRequestAdapter = adapter
    }

    private fun applyHeaderRules(
        original: Map<String, String>,
        rules: List<DnrHeader>
    ): MutableMap<String, String> {
        val result = original.toMutableMap()
        for (rule in rules) {
            val existingKey = result.keys.firstOrNull { it.equals(rule.header, ignoreCase = true) }
            when (rule.operation.lowercase()) {
                "remove" -> if (existingKey != null) result.remove(existingKey)
                "set" -> {
                    if (existingKey != null) result.remove(existingKey)
                    result[rule.header] = rule.value.orEmpty()
                }
                "append" -> {
                    val value = rule.value.orEmpty()
                    if (existingKey == null) result[rule.header] = value
                    else result[existingKey] = listOf(result[existingKey].orEmpty(), value).filter { it.isNotEmpty() }.joinToString(", ")
                }
            }
        }
        return result
    }

    private fun headersToJson(headers: Map<String, String>, field: String): JSONObject {
        val array = JSONArray()
        headers.forEach { (name, value) ->
            array.put(JSONObject().put("name", name).put("value", value))
        }
        return JSONObject().put(field, array)
    }

    override fun setOnWorkerCrashListener(listener: ((String, String) -> Unit)?) {
        this.onWorkerCrashListener = listener
    }

    // MessageListener implementation for incoming extension messages
    override fun onMessageReceived(
        extensionId: String,
        senderTabId: String?,
        message: JSONObject,
        callbackId: String?,
        targetTabId: String?
    ) {
        val targetExtId = message.optString("__targetExtensionId__", message.optString("__targetExtensionId", extensionId)).lowercase().trim()
        if (!isWorkerRunning(targetExtId)) return
        val worker = serviceWorkerRegistry?.getWorker(targetExtId) ?: return
        val genId = worker.workerGenerationId
        if (callbackId != null) {
            worker.addKeepalive("MESSAGE_RESPONSE", callbackId)
        }
        // ExtensionMessageRouter sends a structured transport envelope. Unwrap it once at
        // the V8 backend boundary so extension code receives the same message object as Chrome.
        val deliveredMessage = if (message.has("__payload__")) {
            message.optJSONObject("__payload__") ?: message.opt("__payload__")
        } else if (message.has("__payload")) {
            message.optJSONObject("__payload") ?: message.opt("__payload")
        } else {
            message
        }
        val effectiveTargetTabId = targetTabId ?: message.optString("__targetTabId__", message.optString("__targetTabId", senderTabId))
        val effectiveSenderId = message.optString("__senderExtensionId__", message.optString("__senderExtensionId", targetExtId))
        val payload = JSONObject().apply {
            put("message", deliveredMessage)
            put("callbackId", callbackId)
            put("sender", JSONObject().apply {
                put("id", effectiveSenderId)
                if (senderTabId != null) put("tabId", senderTabId)
                if (effectiveTargetTabId != null) put("targetTabId", effectiveTargetTabId)
                val fId = if (message.has("__targetFrameId__")) message.optInt("__targetFrameId__", 0) else message.optInt("__targetFrameId", 0)
                put("frameId", fId)
            })
            val gen = message.optString("__targetGenerationId__", message.optString("__targetGenerationId", ""))
            if (gen.isNotBlank()) put("targetGenerationId", gen)
        }
        evaluateEvent(targetExtId, genId, "runtime.onMessage", payload)
    }

    override fun onResponseReceived(extensionId: String, callbackId: String, response: Any) {
        val worker = serviceWorkerRegistry?.getWorker(extensionId)
        val genId = worker?.workerGenerationId ?: 1
        worker?.removeKeepalive(callbackId)
        val responseJson = when (response) {
            is JSONObject, is JSONArray -> response.toString()
            else -> JSONObject().put("response", response).toString()
        }
        resolveHostCall(
            extensionId = extensionId,
            generationId = genId,
            requestId = callbackId,
            resultJson = responseJson,
            errorMsg = ""
        )
    }

    // PortConnectionListener implementation for incoming port connections and messages
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
        if (!isWorkerRunning(extensionId)) return
        val worker = serviceWorkerRegistry?.getWorker(extensionId) ?: return
        val genId = worker.workerGenerationId
        worker.addKeepalive("PORT_CONNECTION", channelId)
        val payload = JSONObject().apply {
            put("channelId", channelId)
            put("name", portName)
            put("sender", JSONObject().apply {
                put("id", senderId)
                if (targetTabId != null) put("tabId", targetTabId)
            })
        }
        evaluateEvent(extensionId, genId, "runtime.onConnect", payload)
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
        val extId = targetExtensionId ?: return
        if (!isWorkerRunning(extId)) return
        val worker = serviceWorkerRegistry?.getWorker(extId) ?: return
        val genId = worker.workerGenerationId
        val payload = JSONObject().apply {
            put("channelId", channelId)
            put("message", message)
        }
        evaluateEvent(extId, genId, "port.onMessage", payload)
    }

    override fun onPortDisconnect(
        channelId: String,
        targetExtensionId: String?,
        targetContextType: ExtensionContextType?,
        targetTabId: String?,
        targetFrameId: Int?,
        targetDocumentId: String?
    ) {
        val extId = targetExtensionId ?: return
        val worker = serviceWorkerRegistry?.getWorker(extId)
        worker?.removeKeepalive(channelId)
        if (isWorkerRunning(extId)) {
            val genId = worker?.workerGenerationId ?: 1
            val payload = JSONObject().apply {
                put("channelId", channelId)
            }
            evaluateEvent(extId, genId, "port.onDisconnect", payload)
        }
    }

    /** Maps sensitive event families to the Chrome permission required to observe them. */
    private fun requiredEventPermission(eventName: String): String? {
        return when {
            eventName.startsWith("bookmarks.") -> "bookmarks"
            eventName.startsWith("history.") -> "history"
            eventName.startsWith("downloads.") -> "downloads"
            eventName.startsWith("cookies.") -> "cookies"
            eventName.startsWith("webRequest.") -> "webRequest"
            eventName.startsWith("declarativeNetRequest.") -> "declarativeNetRequest"
            eventName.startsWith("management.") -> "management"
            eventName.startsWith("notifications.") -> "notifications"
            eventName.startsWith("idle.") -> "idle"
            eventName.startsWith("sessions.") -> "sessions"
            eventName.startsWith("tabGroups.") -> "tabGroups"
            eventName.startsWith("topSites.") -> "topSites"
            eventName.startsWith("commands.") -> "commands"
            eventName.startsWith("tts.") -> "tts"
            eventName.startsWith("omnibox.") -> null
            eventName.startsWith("tabs.") -> null
            eventName.startsWith("windows.") -> null
            eventName.startsWith("runtime.") -> null
            eventName.startsWith("storage.") -> "storage"
            eventName.startsWith("action.") || eventName.startsWith("browserAction.") || eventName.startsWith("pageAction.") -> null
            eventName.startsWith("contextMenus.") -> null
            else -> null
        }
    }

    private fun resolveHostCall(extensionId: String, generationId: Int, requestId: String, resultJson: String, errorMsg: String) {
        // Never call back into native V8 on the same JNI stack that invoked HostApiCallback.
        // V8WorkerManager posts the response onto the worker scheduler, and only then calls
        // the completion callback. The browser-side request keepalive therefore covers the
        // complete Java -> scheduler -> JNI response path.
        try {
            v8Runtime.workerManager.resolveHostCall(
                extensionId, generationId, requestId, resultJson, errorMsg
            ) { delivered ->
                serviceWorkerRegistry?.completeRequest(
                    extensionId,
                    requestId,
                    delivered && errorMsg.isBlank()
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Deferred V8 host response failed for $extensionId/$requestId", t)
            serviceWorkerRegistry?.completeRequest(extensionId, requestId, false)
        }
    }

    private fun handleHostApiCall(
        extensionId: String,
        generationId: Int,
        apiName: String,
        requestId: String,
        payloadJson: String
    ) {
        if (serviceWorkerRegistry?.registerRequest(extensionId, requestId, generationId, v8Runtime.config.hostCallTimeoutMs, apiName) == false) {
            resolveHostCall(extensionId, generationId, requestId, "", "WORKER_GENERATION_MISMATCH")
            return
        }
        val rawPayload = if (payloadJson.isNotBlank()) {
            try { JSONObject(payloadJson) } catch (_: Throwable) { null }
        } else null
        val args = when {
            rawPayload == null -> JSONArray()
            rawPayload.has("args") -> rawPayload.optJSONArray("args") ?: JSONArray()
            else -> JSONArray().put(rawPayload)
        }

        // 0. Event listener registration. This is intentionally synchronous so a
        // Service Worker that registers listeners at top-level is visible to the
        // host before external events are dispatched.
        if (apiName == "runtime.registerEventListener" || apiName == "runtime.unregisterEventListener") {
            val eventName = args.optString(0)
            if (eventName.isBlank()) {
                resolveHostCall(extensionId, generationId, requestId, "null", "Missing event name")
                return
            }
            if (apiName == "runtime.registerEventListener") {
                val requiredPermission = requiredEventPermission(eventName)
                val ext = extensionRegistry?.getExtension(extensionId)
                val granted = if (requiredPermission == null) {
                    true
                } else {
                    val reg = extensionRegistry
                    if (ext == null || reg == null || !reg.isExtensionEnabled(extensionId)) {
                        false
                    } else {
                        val manifestGranted = ext.permissions.any { it.equals(requiredPermission, ignoreCase = true) } ||
                            ext.hostPermissions.any { it.equals(requiredPermission, ignoreCase = true) }
                        manifestGranted
                    }
                }
                if (!granted) {
                    resolveHostCall(
                        extensionId, generationId, requestId, "",
                        "Permission denied for event $eventName"
                    )
                    return
                }
                eventManager?.addLazyListener(eventName, extensionId)
            } else {
                eventManager?.removeLazyListener(eventName, extensionId)
            }
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        // 1. Keepalive management
        if (apiName == "runtime.keepalive") {
            val action = args.optString(0)
            val handle = args.optString(1)
            val reason = args.optString(2)
            val worker = serviceWorkerRegistry?.getWorker(extensionId)
            when (action) {
                "startWaitUntil" -> serviceWorkerRegistry?.addTimedKeepalive(
                    extensionId, "WAIT_UNTIL", handle, v8Runtime.config.eventKeepaliveTimeoutMs
                )
                "endWaitUntil" -> worker?.removeKeepalive(handle)
                "startMessageResponse" -> serviceWorkerRegistry?.addTimedKeepalive(
                    extensionId, "MESSAGE_RESPONSE", handle, v8Runtime.config.eventKeepaliveTimeoutMs
                )
                "endMessageResponse" -> worker?.removeKeepalive(handle)
                "startKeepalive" -> serviceWorkerRegistry?.addTimedKeepalive(
                    extensionId, reason.ifBlank { "MANUAL" }, handle, v8Runtime.config.eventKeepaliveTimeoutMs
                )
                "endKeepalive" -> worker?.removeKeepalive(handle)
            }
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        // 2. Message response from JS
        if (apiName == "runtime.sendResponse" || apiName == "runtime.response") {
            val callbackId = if (args.length() > 2) args.optString(1) else args.optString(0)
            val messageId = if (args.length() > 2) args.optString(0) else ""
            val response = if (args.length() > 2) (args.opt(2) ?: JSONObject()) else (args.opt(1) ?: JSONObject())
            if (callbackId.isNotBlank()) {
                messageRouter?.handleSendResponse(extensionId, callbackId, response)
                messageBus?.broadcastResponse(extensionId, callbackId, response)
            }
            val worker = serviceWorkerRegistry?.getWorker(extensionId)
            if (callbackId.isNotBlank()) worker?.removeKeepalive(callbackId)
            if (messageId.isNotBlank()) worker?.removeKeepalive(messageId)
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        // 3. Port connections
        if (apiName == "runtime.portConnect") {
            val targetId = args.optString(0)
            val channelId = args.optString(1)
            val portName = args.optString(2)
            portManager?.connect(
                extensionId = targetId,
                channelId = channelId,
                portName = portName,
                senderId = extensionId,
                targetContextType = ExtensionContextType.SERVICE_WORKER
            )
            val worker = serviceWorkerRegistry?.getWorker(extensionId)
            worker?.addKeepalive("PORT_CONNECTION", channelId)
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        if (apiName == "runtime.portPostMessage") {
            val channelId = args.optString(0)
            val messageObj = args.optJSONObject(1) ?: JSONObject().put("data", args.opt(1))
            portManager?.postMessage(channelId, messageObj, senderExtensionId = extensionId)
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        if (apiName == "runtime.portDisconnect") {
            val channelId = args.optString(0)
            portManager?.disconnect(channelId, senderExtensionId = extensionId)
            val worker = serviceWorkerRegistry?.getWorker(extensionId)
            worker?.removeKeepalive(channelId)
            resolveHostCall(extensionId, generationId, requestId, "{\"status\":\"ok\"}", "")
            return
        }

        // 4. Real host-backed fetch for native worker contexts. All external network
        // requests are DNR/WebRequest-gated before the existing OkHttp transaction executes.
        if (apiName == "runtime.fetch") {
            val req = args.optJSONObject(0) ?: JSONObject()
            val initialUrl = req.optString("url", "")
            if (!(initialUrl.startsWith("https://", true) || initialUrl.startsWith("http://", true))) {
                resolveHostCall(extensionId, generationId, requestId, "", "Only http(s) fetch URLs are supported")
                return
            }
            val isPrivate = workerPrivateModes[extensionId] == true
            if (networkPermissionChecker?.invoke(extensionId, initialUrl, isPrivate) != true) {
                resolveHostCall(extensionId, generationId, requestId, "", "Network request blocked by extension host permissions")
                return
            }
            val method = req.optString("method", "GET").uppercase()
            val headers = req.optJSONObject("headers") ?: JSONObject()
            val originalHeaders = mutableMapOf<String, String>()
            val headerNames = headers.names()
            if (headerNames != null) {
                for (i in 0 until headerNames.length()) {
                    val key = headerNames.optString(i, "")
                    if (key.isNotBlank()) originalHeaders[key] = headers.optString(key, "")
                }
            }
            val bodyText = if (req.isNull("body")) null else req.optString("body", "")
            val adapter = webRequestAdapter
            val stableRequestId = adapter?.idMapper?.getOrCreateRequestId("service-worker:$extensionId:$generationId:$requestId") ?: requestId
            var currentUrl = initialUrl
            var currentHeaders = originalHeaders
            var redirectChain = mutableListOf(initialUrl)
            var redirectCount = 0
            var requestContext = ExtensionNetworkRequestContext(
                requestId = stableRequestId,
                url = currentUrl,
                method = method,
                resourceType = "xmlhttprequest",
                tabId = -1,
                frameId = 0,
                parentFrameId = -1,
                documentId = null,
                documentUrl = "chrome-extension://$extensionId",
                isPrivate = isPrivate,
                privateSessionId = null,
                requestHeaders = currentHeaders,
                isForMainFrame = false,
                initiator = "chrome-extension://$extensionId",
                origin = "chrome-extension://$extensionId",
                redirectChain = redirectChain,
                windowId = if (isPrivate) 2 else 1
            )
            var modifyRules = emptyList<DnrHeader>()
            var responseRules = emptyList<DnrHeader>()
            var decision: WebRequestInterceptResult = adapter?.interceptRequest(requestContext) ?: WebRequestInterceptResult.Continue
            while (decision is WebRequestInterceptResult.Redirect) {
                val target = decision.redirectUrl
                if (target.isBlank() || target == currentUrl || redirectChain.contains(target) || redirectCount >= 10) {
                    adapter?.notifyEvent("onErrorOccurred", requestContext, JSONObject().put("error", "DNR_REDIRECT_LOOP"))
                    resolveHostCall(extensionId, generationId, requestId, "", "DNR redirect loop")
                    return
                }
                adapter?.notifyEvent("onBeforeRedirect", requestContext, JSONObject().put("redirectUrl", target).put("extensionRedirect", true))
                redirectCount++
                redirectChain.add(target)
                currentUrl = target
                requestContext = requestContext.copy(url = currentUrl, redirectChain = redirectChain.toList())
                decision = adapter?.interceptRequest(requestContext) ?: WebRequestInterceptResult.Continue
            }
            if (decision is WebRequestInterceptResult.Blocked) {
                adapter?.notifyEvent("onErrorOccurred", requestContext, JSONObject().put("error", "net::ERR_BLOCKED_BY_CLIENT"))
                resolveHostCall(extensionId, generationId, requestId, "", "Network request blocked by declarativeNetRequest")
                return
            }
            if (decision is WebRequestInterceptResult.ModifyHeaders) {
                modifyRules = decision.requestHeaders
                responseRules = decision.responseHeaders
                currentHeaders = applyHeaderRules(currentHeaders, modifyRules)
                requestContext = requestContext.copy(requestHeaders = currentHeaders)
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val client = OkHttpClient.Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()
                    val builder = Request.Builder().url(currentUrl)
                    currentHeaders.forEach { (key, value) -> builder.header(key, value) }
                    val requestBody = if (bodyText != null && method !in setOf("GET", "HEAD")) {
                        bodyText.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
                    } else null
                    builder.method(method, requestBody)
                    val finalRequest = builder.build()
                    adapter?.notifyEvent("onBeforeSendHeaders", requestContext, headersToJson(currentHeaders, "requestHeaders"))
                    adapter?.notifyEvent("onSendHeaders", requestContext, headersToJson(currentHeaders, "requestHeaders"))
                    client.newCall(finalRequest).execute().use { response ->
                        val responseHeaderMap = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                        val finalResponseHeaders = applyHeaderRules(responseHeaderMap, responseRules)
                        adapter?.notifyEvent("onHeadersReceived", requestContext, headersToJson(finalResponseHeaders, "responseHeaders").put("statusCode", response.code))
                        adapter?.notifyEvent("onResponseStarted", requestContext)
                        val responseBody = response.body
                        val declaredLength = responseBody?.contentLength() ?: 0L
                        if (declaredLength > MAX_WORKER_FETCH_BYTES) {
                            throw IllegalStateException("Extension worker fetch response exceeds 16 MiB limit")
                        }
                        val bodyBytes = if (responseBody == null) {
                            ByteArray(0)
                        } else {
                            val out = java.io.ByteArrayOutputStream()
                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > MAX_WORKER_FETCH_BYTES) {
                                        throw IllegalStateException("Extension worker fetch response exceeds 16 MiB limit")
                                    }
                                    out.write(buffer, 0, read)
                                }
                            }
                            out.toByteArray()
                        }
                        val body = String(bodyBytes, Charsets.UTF_8)
                        val base64 = android.util.Base64.encodeToString(bodyBytes, android.util.Base64.NO_WRAP)
                        val responseHeaders = JSONObject()
                        finalResponseHeaders.forEach { (key, value) -> responseHeaders.put(key, value) }
                        val out = JSONObject().apply {
                            put("ok", response.isSuccessful)
                            put("status", response.code)
                            put("statusText", response.message)
                            put("url", response.request.url.toString())
                            put("body", body)
                            put("bodyBase64", base64)
                            put("headers", responseHeaders)
                        }
                        adapter?.notifyEvent("onCompleted", requestContext, JSONObject().put("statusCode", response.code))
                        resolveHostCall(extensionId, generationId, requestId, out.toString(), "")
                    }
                } catch (t: Throwable) {
                    adapter?.notifyEvent("onErrorOccurred", requestContext, JSONObject().put("error", t.message ?: "fetch failed"))
                    resolveHostCall(extensionId, generationId, requestId, "", t.message ?: "fetch failed")
                }
            }
            return
        }

        // 6. Worker timers. Native V8 has no browser event loop, so timers are owned by the
        // Android worker scheduler and only re-enter the isolate through nativeFireTimer().
        if (apiName == "runtime.setTimer") {
            val isInterval = args.optBoolean(0, false)
            val delayMs = args.optLong(1, 0L).coerceAtLeast(0L)
            val timerId = args.optInt(2, 0)
            val ok = v8Runtime.workerManager.scheduleNativeTimer(
                extensionId = extensionId,
                generationId = generationId,
                isInterval = isInterval,
                delayMs = delayMs,
                timerId = timerId
            )
            resolveHostCall(
                extensionId, generationId, requestId,
                if (ok) "{\"status\":\"ok\"}" else "",
                if (ok) "" else "Worker generation mismatch"
            )
            return
        }

        if (apiName == "runtime.clearTimer") {
            val timerId = args.optInt(0, 0)
            val ok = v8Runtime.workerManager.clearNativeTimer(extensionId, generationId, timerId)
            resolveHostCall(
                extensionId, generationId, requestId,
                if (ok) "{\"status\":\"ok\"}" else "",
                if (ok) "" else "Worker generation mismatch"
            )
            return
        }

        // 6. General Chrome API calls via RuntimeBridge
        val bridge = contextAwareRuntimeBridgeProvider?.invoke(this, ExtensionContextType.SERVICE_WORKER)
            ?: runtimeBridgeProvider?.invoke(this)

        if (bridge is RuntimeBridge) {
            val sender = bridge.buildSender(extensionId)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = bridge.jsRouter.handleCall(
                        sender = sender,
                        api = apiName,
                        args = args,
                        isPrivate = sender.isPrivate,
                        privateSessionId = sender.privateSessionId,
                        browserDelegate = bridge.delegate
                    )
                    val resultJson = when (result) {
                        is JSONObject, is JSONArray -> result.toString()
                        null -> "null"
                        is String -> JSONObject.quote(result)
                        is Number, is Boolean -> result.toString()
                        else -> JSONObject.wrap(result)?.toString() ?: "null"
                    }
                    resolveHostCall(
                        extensionId = extensionId,
                        generationId = generationId,
                        requestId = requestId,
                        resultJson = resultJson,
                        errorMsg = ""
                    )
                } catch (e: Throwable) {
                    resolveHostCall(
                        extensionId = extensionId,
                        generationId = generationId,
                        requestId = requestId,
                        resultJson = "",
                        errorMsg = e.message ?: "Host API call failed"
                    )
                }
            }
        } else {
            Log.e(TAG, "Host API call failed: Bridge not available for extensionId=$extensionId, api=$apiName")
            resolveHostCall(
                extensionId = extensionId,
                generationId = generationId,
                requestId = requestId,
                resultJson = "",
                errorMsg = "Bridge not available"
            )
        }
    }

    /**
     * Collect statically imported extension modules for native V8's real ES-module resolver.
     * V8, not this regex, performs the actual parsing/linking/evaluation; the regex only finds
     * file dependencies so the embedder can provide their bytes synchronously to the resolver.
     */
    private fun buildModuleSourcesBlob(parsedExtension: ParsedExtension, entryPath: String, entrySource: String): String {
        val root = ExtensionDirectoryResolver.getExtensionDir(context, parsedExtension.id, parsedExtension.name)
        val seen = LinkedHashMap<String, String>()
        val queue = ArrayDeque<String>()
        queue.add(normalizeModuleKey(entryPath))

        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (seen.containsKey(key)) continue
            val file = ExtensionDirectoryResolver.findFileCaseInsensitive(root, key) ?: continue
            if (!file.isFile) continue
            val source = try { file.readText() } catch (_: Throwable) { continue }
            seen[key] = source

            val imports = linkedSetOf<String>()
            val patterns = listOf(
                Regex("""\bimport\s+(?:[\s\S]*?\s+from\s*)?["']([^"']+)["']"""),
                Regex("""\bexport\s+(?:\*|\{[\s\S]*?\})\s+from\s+["']([^"']+)["']"""),
                Regex("""\bimport\s*\(\s*["']([^"']+)["']\s*\)""")
            )
            for (pattern in patterns) {
                pattern.findAll(source).forEach { m ->
                    val spec = m.groupValues.getOrNull(1).orEmpty()
                    if (spec.isNotBlank()) imports += spec
                }
            }
            for (specifier in imports) {
                resolveModuleKey(key, specifier)?.let { resolved ->
                    if (!seen.containsKey(resolved)) queue.add(resolved)
                }
            }
        }

        // Always use the source passed by the caller as the authoritative entry bytes.
        seen[normalizeModuleKey(entryPath)] = entrySource

        val out = StringBuilder()
        for ((key, source) in seen) {
            out.append(key.length).append(':').append(key)
            out.append(source.toByteArray(Charsets.UTF_8).size).append(':').append(source)
        }
        return out.toString()
    }

    private fun normalizeModuleKey(path: String): String {
        return path.replace('\\', '/').trimStart('/').replace(Regex("/+/"), "/")
    }

    private fun resolveModuleKey(referrer: String, specifier: String): String? {
        val spec = specifier.trim()
        if (spec.startsWith("chrome-extension://") || spec.startsWith("swift-extension://")) {
            val idx = spec.indexOf('/', spec.indexOf("://") + 3)
            return if (idx >= 0) normalizeModuleKey(spec.substring(idx + 1)) else null
        }
        if (spec.startsWith("/")) return normalizeModuleKey(spec)
        if (spec.startsWith(".")) {
            val base = referrer.substringBeforeLast('/', "")
            val raw = if (base.isBlank()) spec else "$base/$spec"
            val parts = ArrayList<String>()
            for (part in raw.replace('\\', '/').split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else return null
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }
        // Bare specifiers are not valid extension-relative module URLs.
        return null
    }

    private fun loadExtensionScript(parsedExtension: ParsedExtension, scriptPath: String): String {
        val extDir = ExtensionDirectoryResolver.getExtensionDir(context, parsedExtension.id, parsedExtension.name)
        val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, scriptPath)
        return if (file != null && file.exists()) {
            try { file.readText() } catch (e: Exception) { "" }
        } else ""
    }
}
