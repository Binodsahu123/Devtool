package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtensionScriptingAdapter(
    val permissionAdapter: ExtensionPermissionAdapter,
    val registry: ExtensionRegistry,
    val contentScriptManager: ContentScriptManager,
    val scriptInjector: ScriptInjector,
    val cssInjector: CssInjector,
    val worldAwareScriptExecutor: WorldAwareScriptExecutor,
    val permissionManager: PermissionManager? = null
) {
    private val resourceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var frameTargetRegistry: WebNavigationStateMachine? = null

    fun setFrameTargetRegistry(stateMachine: WebNavigationStateMachine) {
        frameTargetRegistry = stateMachine
    }

    companion object {
        val tabInsertedStyles = ConcurrentHashMap<String, MutableSet<String>>() // tabId -> styleKeys
    }

    fun executeScript(
        sender: ExtensionSender,
        spec: JSONObject,
        delegate: BrowserDelegate?,
        context: Context,
        callback: (JSONArray?, String?) -> Unit
    ) {
        val extId = sender.extensionId

        val target = spec.optJSONObject("target")
        val parsedTarget = try {
            ScriptTargetContract.parse(target)
        } catch (e: IllegalArgumentException) {
            callback(null, e.message ?: "SCRIPTING_INVALID_TARGET")
            return
        }
        val resolvedTabId = parsedTarget.canonicalTabId
        val allFrames = parsedTarget.allFrames
        val frameIds = parsedTarget.frameIds
        val targetDocIds = target?.optJSONArray("documentIds")
        val funcCode = spec.optString("func", "")
        val files = spec.optJSONArray("files")
        val hasFunc = funcCode.isNotBlank()
        val hasFiles = files != null && files.length() > 0

        // Chrome validation: exactly one of files or func
        if ((hasFunc && hasFiles) || (!hasFunc && !hasFiles)) {
            callback(null, "Must specify exactly one of 'files' or 'func'.")
            return
        }

        // Chrome validation: args cannot be used with files
        if (hasFiles && spec.has("args")) {
            callback(null, "The 'args' argument cannot be used with the 'files' option.")
            return
        }

        val allTabs = delegate?.queryTabs(JSONObject()) ?: JSONArray()
        var targetUrl: String? = null
        for (i in 0 until allTabs.length()) {
            val t = allTabs.optJSONObject(i)
            if (t != null) {
                val idVal = t.opt("id")?.toString()
                if (idVal == parsedTarget.rawTabId || idVal == resolvedTabId) {
                    targetUrl = t.optString("url", "")
                    break
                }
            }
        }

        if (targetUrl == null) {
            callback(null, "SCRIPTING_TAB_NOT_FOUND")
            return
        }

        // Validate permissions via canonical permissionAdapter
        val validationErr = checkScriptingValidation(sender, targetUrl)
        if (validationErr != null) {
            callback(null, validationErr)
            return
        }

        // Resolve against the callback-fed registry before any execution. Never
        // downgrade an unknown frame/document to the tab's top frame.
        val requestedDocumentIds = parsedTarget.documentIds.takeIf { it.isNotEmpty() }
        val canonicalTabId = resolvedTabId
        val effectiveTargetFrames: List<Int> = frameIds
        val effectiveDocumentIds: List<String>? = requestedDocumentIds

        val world = spec.optString("world", "ISOLATED").uppercase()
        // ISOLATED is Chrome's default, so validate it even when the caller
        // omitted the optional world field. Never silently downgrade it to the
        // Native isolated-world execution is required; no page-world approximation is permitted.
        if (!OrionWebViewScriptingCapabilities.canTargetWorld(world)) {
            callback(null, "SCRIPTING_WORLD_UNSUPPORTED")
            return
        }

        val args = spec.optJSONArray("args") ?: JSONArray()
        val argErr = validateJsonArgs(args)
        if (argErr != null) {
            callback(null, argErr)
            return
        }

        if (funcCode.isNotBlank()) {
            val trimmed = funcCode.trim()
            val isValidFunc = trimmed.startsWith("function") || trimmed.contains("=>") ||
                (trimmed.startsWith("(") && trimmed.endsWith(")"))
            if (!isValidFunc) {
                callback(null, "SCRIPTING_FUNCTION_UNSUPPORTED")
                return
            }
            executePreparedScript(delegate, extId, canonicalTabId, allFrames, effectiveTargetFrames,
                args, world, effectiveDocumentIds, funcCode, true, callback)
            return
        }

        val filePaths = (0 until (files?.length() ?: 0))
            .map { files!!.optString(it, "") }
            .filter { it.isNotBlank() }
        if (filePaths.isEmpty()) {
            callback(null, "SCRIPTING_INVALID_TARGET")
            return
        }

        resourceScope.launch {
            val loaded = try {
                buildString {
                    for (path in filePaths) {
                        val content = readExtensionFile(context, extId, path, sender.isPrivate)
                        if (content.isBlank()) throw IllegalArgumentException("SCRIPTING_RESOURCE_NOT_FOUND")
                        append(content).append("\n")
                    }
                }
            } catch (_: Throwable) {
                null
            }
            withContext(Dispatchers.Main) {
                if (loaded.isNullOrBlank()) {
                    callback(null, "SCRIPTING_RESOURCE_NOT_FOUND")
                } else {
                    executePreparedScript(delegate, extId, canonicalTabId, allFrames, effectiveTargetFrames,
                        args, world, effectiveDocumentIds, loaded, false, callback)
                }
            }
        }
    }

    private fun executePreparedScript(
        delegate: BrowserDelegate?,
        extensionId: String,
        resolvedTabId: String,
        allFrames: Boolean,
        frameIds: List<Int>,
        args: JSONArray,
        world: String,
        documentIds: List<String>?,
        execCode: String,
        isFunction: Boolean,
        callback: (JSONArray?, String?) -> Unit
    ) {
        if (delegate == null) {
            callback(null, "SCRIPTING_WEBVIEW_FEATURE_UNAVAILABLE")
            return
        }
        val targetSpec = ScriptExecutionTarget(
            tabId = resolvedTabId,
            allFrames = allFrames,
            frameIds = if (allFrames || !documentIds.isNullOrEmpty()) emptyList() else frameIds.ifEmpty { listOf(0) },
            documentIds = documentIds,
            world = ContentScriptExecutionWorld.fromString(world),
            extensionId = extensionId
        )
        worldAwareScriptExecutor.execute(
            delegate = delegate,
            target = targetSpec,
            code = execCode,
            isFunction = isFunction,
            argsJson = args.toString(),
            documentId = documentIds?.firstOrNull().orEmpty()
        ) { frameResults, error ->
            if (error != null) {
                callback(null, error)
            } else {
                val resultsArr = JSONArray()
                frameResults.orEmpty().forEach { fr ->
                    resultsArr.put(JSONObject().apply {
                        put("frameId", fr.frameId)
                        if (fr.documentId.isNotBlank()) put("documentId", fr.documentId)
                        put("result", fr.result ?: JSONObject.NULL)
                        if (fr.error != null) put("error", fr.error)
                    })
                }
                callback(resultsArr, null)
            }
        }
    }

    private fun computeCssKey(extensionId: String, css: String, files: JSONArray?): String {
        val raw = StringBuilder(extensionId)
        if (css.isNotBlank()) raw.append("#").append(css)
        if (files != null) {
            for (i in 0 until files.length()) {
                raw.append("#").append(files.optString(i, ""))
            }
        }
        val hash = kotlin.math.abs(raw.toString().hashCode())
        return "ext-css-${extensionId.take(8)}-$hash"
    }

    fun insertCSS(
        sender: ExtensionSender,
        spec: JSONObject,
        delegate: BrowserDelegate?,
        context: Context
    ): JSONObject {
        validateScriptingPermission(sender)
        val target = spec.optJSONObject("target") ?: JSONObject().put("tabId", spec.optString("tabId", ""))
        val parsedTarget = ScriptTargetContract.parse(target)
        val resolvedTabId = parsedTarget.canonicalTabId
        val css = spec.optString("css", "")
        val files = spec.optJSONArray("files")
        if (css.isBlank() && (files == null || files.length() == 0)) {
            throw IllegalArgumentException("SCRIPTING_CSS_REQUIRED")
        }
        if (css.isNotBlank() && files != null && files.length() > 0) {
            throw IllegalArgumentException("SCRIPTING_CSS_SOURCE_EXCLUSIVE")
        }

        val cssContentBuilder = StringBuilder()
        if (css.isNotBlank()) cssContentBuilder.append(css).append("\n")
        if (files != null) {
            for (i in 0 until files.length()) {
                val fPath = files.optString(i, "")
                if (fPath.isBlank()) throw IllegalArgumentException("SCRIPTING_RESOURCE_UNSAFE")
                cssContentBuilder.append(readExtensionFile(context, sender.extensionId, fPath, sender.isPrivate)).append("\n")
            }
        }
        val fullCss = cssContentBuilder.toString()
        if (fullCss.isBlank()) throw IllegalArgumentException("SCRIPTING_CSS_REQUIRED")

        val frameIds = parsedTarget.frameIds
        val documentIds = parsedTarget.documentIds
        val allFrames = parsedTarget.allFrames
        val targetModel = ScriptExecutionTarget(
            tabId = resolvedTabId,
            allFrames = allFrames,
            frameIds = frameIds.ifEmpty { listOf(0) },
            documentIds = documentIds.takeIf { it.isNotEmpty() },
            world = ContentScriptWorld.ISOLATED,
            extensionId = sender.extensionId
        )

        val cssKey = spec.optString("cssKey", computeCssKey(sender.extensionId, css, files))
        val webView = delegate?.getWebView(resolvedTabId)
        if (webView != null && OrionWebViewScriptingCapabilities.isFrameAndWorldInjectionSupported()) {
            val result = arrayOfNulls<JSONArray>(1)
            val error = arrayOfNulls<String>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            webView.post {
                com.swift.browser.extensionengine.nativecore.WebViewCanonicalFrameBridge.executeCss(
                    webView = webView,
                    target = targetModel,
                    cssKey = cssKey,
                    css = fullCss
                ) { r, e ->
                    result[0] = r
                    error[0] = e
                    latch.countDown()
                }
            }
            // API calls normally execute on the main thread from RuntimeBridge. If invoked from
            // background code, wait only for the short dispatch setup; the provider itself remains
            // asynchronous and does not block the WebView thread.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) latch.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (error[0] != null) throw IllegalStateException(error[0])
        } else {
            if (frameIds.any { it != 0 } || documentIds.isNotEmpty() || allFrames) {
                throw UnsupportedOperationException("SCRIPTING_CSS_TARGETING_UNSUPPORTED")
            }
            val evaluator = delegate ?: throw UnsupportedOperationException("SCRIPTING_WEBVIEW_FEATURE_UNAVAILABLE")
            val scriptEvaluator = DelegateScriptEvaluator(evaluator, resolvedTabId)
            cssInjector.createRecord(sender.extensionId, resolvedTabId, 0, "", cssKey, fullCss)
            cssInjector.injectCssWithKey(scriptEvaluator, cssKey, fullCss)
        }
        val trackerKey = "${sender.extensionId}_$resolvedTabId"
        tabInsertedStyles.getOrPut(trackerKey) { ConcurrentHashMap.newKeySet() }.add(cssKey)
        return JSONObject().put("status", "success").put("cssKey", cssKey)
    }

    fun removeCSS(
        sender: ExtensionSender,
        spec: JSONObject,
        delegate: BrowserDelegate?,
        context: Context
    ): JSONObject {
        validateScriptingPermission(sender)
        val target = spec.optJSONObject("target") ?: JSONObject().put("tabId", spec.optString("tabId", ""))
        val parsedTarget = ScriptTargetContract.parse(target)
        val resolvedTabId = parsedTarget.canonicalTabId
        val css = spec.optString("css", "")
        val files = spec.optJSONArray("files")
        val cssKey = spec.optString("cssKey", computeCssKey(sender.extensionId, css, files))
        val frameIds = parsedTarget.frameIds
        val documentIds = parsedTarget.documentIds
        val allFrames = parsedTarget.allFrames
        val targetModel = ScriptExecutionTarget(
            tabId = resolvedTabId,
            allFrames = allFrames,
            frameIds = frameIds.ifEmpty { listOf(0) },
            documentIds = documentIds.takeIf { it.isNotEmpty() },
            world = ContentScriptWorld.ISOLATED,
            extensionId = sender.extensionId
        )
        val trackerKey = "${sender.extensionId}_$resolvedTabId"
        val keysSet = tabInsertedStyles[trackerKey]
        if (keysSet == null || !keysSet.contains(cssKey)) {
            throw IllegalArgumentException("SCRIPTING_REMOVE_CSS_NOT_FOUND")
        }
        val webView = delegate?.getWebView(resolvedTabId)
        if (webView != null && OrionWebViewScriptingCapabilities.isFrameAndWorldInjectionSupported()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val err = arrayOfNulls<String>(1)
            webView.post {
                com.swift.browser.extensionengine.nativecore.WebViewCanonicalFrameBridge.removeCss(
                    webView = webView,
                    target = targetModel,
                    cssKey = cssKey
                ) { _, e -> err[0] = e; latch.countDown() }
            }
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) latch.await(100L, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (err[0] != null) throw IllegalStateException(err[0])
        } else {
            if (frameIds.any { it != 0 } || documentIds.isNotEmpty() || allFrames) throw UnsupportedOperationException("SCRIPTING_CSS_TARGETING_UNSUPPORTED")
            val evaluator = delegate ?: throw UnsupportedOperationException("SCRIPTING_WEBVIEW_FEATURE_UNAVAILABLE")
            cssInjector.removeCssByKey(DelegateScriptEvaluator(evaluator, resolvedTabId), cssKey)
        }
        keysSet.remove(cssKey)
        if (keysSet.isEmpty()) tabInsertedStyles.remove(trackerKey)
        return JSONObject().put("status", "success")
    }


    fun registerContentScripts(sender: ExtensionSender, scriptsArray: JSONArray): JSONObject {
        val extId = sender.extensionId
        validateScriptingPermission(sender)

        val specs = mutableListOf<DynamicContentScriptSpec>()
        val existing = contentScriptManager.getRegisteredContentScripts(extId)
        val existingIds = existing.map { it.id }.toSet()

        for (i in 0 until scriptsArray.length()) {
            val obj = scriptsArray.getJSONObject(i)
            val id = obj.getString("id")
            if (existingIds.contains(id)) {
                throw IllegalArgumentException("SCRIPTING_DUPLICATE_REGISTRATION")
            }
            val matches = parseStringList(obj.optJSONArray("matches"))
            val excludeMatches = parseStringList(obj.optJSONArray("excludeMatches"))
            val includeGlobs = parseStringList(obj.optJSONArray("includeGlobs"))
            val excludeGlobs = parseStringList(obj.optJSONArray("excludeGlobs"))
            val js = parseStringList(obj.optJSONArray("js"))
            val css = parseStringList(obj.optJSONArray("css"))
            val runAt = obj.optString("runAt", "document_idle")
            val allFrames = obj.optBoolean("allFrames", false)
            val matchAboutBlank = obj.optBoolean("matchAboutBlank", false)
            val matchOriginAsFallback = obj.optBoolean("matchOriginAsFallback", false)
            val world = obj.optString("world", "ISOLATED")
            val persistAcrossSessions = obj.optBoolean("persistAcrossSessions", true)

            val spec = DynamicContentScriptSpec(
                id = id,
                matches = matches,
                excludeMatches = excludeMatches,
                includeGlobs = includeGlobs,
                excludeGlobs = excludeGlobs,
                js = js,
                css = css,
                runAt = runAt,
                allFrames = allFrames,
                matchAboutBlank = matchAboutBlank,
                matchOriginAsFallback = matchOriginAsFallback,
                world = world,
                persistAcrossSessions = persistAcrossSessions
            )
            validateDynamicScriptSpec(sender, spec)
            specs.add(spec)
        }

        contentScriptManager.registerContentScripts(extId, specs)
        return JSONObject().put("status", "success")
    }

    fun unregisterContentScripts(sender: ExtensionSender, idsArray: JSONArray?): JSONObject {
        val extId = sender.extensionId
        validateScriptingPermission(sender)

        val ids = parseStringList(idsArray)
        if (ids.isNotEmpty()) {
            val existing = contentScriptManager.getRegisteredContentScripts(extId)
            val existingIds = existing.map { it.id }.toSet()
            for (id in ids) {
                if (!existingIds.contains(id)) {
                    throw IllegalArgumentException("SCRIPTING_REGISTRATION_NOT_FOUND")
                }
            }
        }

        contentScriptManager.unregisterContentScripts(extId, if (ids.isEmpty()) null else ids)
        return JSONObject().put("status", "success")
    }

    fun updateContentScripts(sender: ExtensionSender, scriptsArray: JSONArray): JSONObject {
        val extId = sender.extensionId
        validateScriptingPermission(sender)

        val specs = mutableListOf<DynamicContentScriptSpec>()
        val existing = contentScriptManager.getRegisteredContentScripts(extId)
        val existingIds = existing.map { it.id }.toSet()

        for (i in 0 until scriptsArray.length()) {
            val obj = scriptsArray.getJSONObject(i)
            val id = obj.getString("id")
            if (!existingIds.contains(id)) {
                throw IllegalArgumentException("SCRIPTING_REGISTRATION_NOT_FOUND")
            }
            val matches = parseStringList(obj.optJSONArray("matches"))
            val excludeMatches = parseStringList(obj.optJSONArray("excludeMatches"))
            val includeGlobs = parseStringList(obj.optJSONArray("includeGlobs"))
            val excludeGlobs = parseStringList(obj.optJSONArray("excludeGlobs"))
            val js = parseStringList(obj.optJSONArray("js"))
            val css = parseStringList(obj.optJSONArray("css"))
            val runAt = obj.optString("runAt", "document_idle")
            val allFrames = obj.optBoolean("allFrames", false)
            val existingSpec = existing.firstOrNull { it.id == id }
            val matchAboutBlank = obj.optBoolean("matchAboutBlank", existingSpec?.matchAboutBlank ?: false)
            val matchOriginAsFallback = obj.optBoolean(
                "matchOriginAsFallback",
                existingSpec?.matchOriginAsFallback ?: false
            )
            val world = obj.optString("world", "ISOLATED")
            val persistAcrossSessions = obj.optBoolean(
                "persistAcrossSessions",
                existingSpec?.persistAcrossSessions ?: true
            )

            val spec = DynamicContentScriptSpec(
                id = id,
                matches = matches,
                excludeMatches = excludeMatches,
                includeGlobs = includeGlobs,
                excludeGlobs = excludeGlobs,
                js = js,
                css = css,
                runAt = runAt,
                allFrames = allFrames,
                matchAboutBlank = matchAboutBlank,
                matchOriginAsFallback = matchOriginAsFallback,
                world = world,
                persistAcrossSessions = persistAcrossSessions
            )
            validateDynamicScriptSpec(sender, spec)
            specs.add(spec)
        }

        contentScriptManager.updateContentScripts(extId, specs)
        return JSONObject().put("status", "success")
    }

    fun getRegisteredContentScripts(sender: ExtensionSender): JSONArray {
        validateScriptingPermission(sender)
        val list = contentScriptManager.getRegisteredContentScripts(sender.extensionId)
        val array = JSONArray()
        for (s in list) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("matches", JSONArray(s.matches))
                put("excludeMatches", JSONArray(s.excludeMatches))
                put("includeGlobs", JSONArray(s.includeGlobs))
                put("excludeGlobs", JSONArray(s.excludeGlobs))
                put("js", JSONArray(s.js))
                put("css", JSONArray(s.css))
                put("runAt", s.runAt)
                put("allFrames", s.allFrames)
                put("matchAboutBlank", s.matchAboutBlank)
                put("matchOriginAsFallback", s.matchOriginAsFallback)
                put("world", s.world)
                put("persistAcrossSessions", s.persistAcrossSessions)
            }
            array.put(obj)
        }
        return array
    }

    private fun validateScriptingPermission(sender: ExtensionSender) {
        val extId = sender.extensionId
        val ext = registry.getExtension(extId) ?: throw SecurityException("SCRIPTING_EXTENSION_DISABLED")
        if (!registry.isExtensionEnabled(extId)) {
            throw SecurityException("SCRIPTING_EXTENSION_DISABLED")
        }
        if (sender.isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) {
            throw SecurityException("SCRIPTING_PRIVATE_MODE_DENIED")
        }
        val hasPerm = ext.permissions.contains("scripting") || permissionAdapter.hasApiPermission(extId, "scripting", sender.isPrivate)
        if (!hasPerm) {
            throw SecurityException("SCRIPTING_PERMISSION_DENIED")
        }
    }

    private fun checkScriptingValidation(sender: ExtensionSender, targetUrl: String): String? {
        val extId = sender.extensionId
        val ext = registry.getExtension(extId) ?: return "SecurityError: SCRIPTING_EXTENSION_DISABLED"
        if (!registry.isExtensionEnabled(extId)) {
            return "SecurityError: SCRIPTING_EXTENSION_DISABLED"
        }
        if (sender.isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) {
            return "SecurityError: SCRIPTING_PRIVATE_MODE_DENIED"
        }
        val hasPerm = ext.permissions.contains("scripting") || permissionAdapter.hasApiPermission(extId, "scripting", sender.isPrivate)
        if (!hasPerm) {
            return "SecurityError: SCRIPTING_PERMISSION_DENIED"
        }
        if (targetUrl.startsWith("swift:") || targetUrl.startsWith("chrome://") || targetUrl.startsWith("file://")) {
            return "SecurityError: SCRIPTING_HOST_DENIED"
        }
        val hasHostPermission = permissionAdapter.hasHostPermission(extId, targetUrl, sender.isPrivate) ||
                permissionAdapter.contains(extId, permissions = listOf("activeTab"), origins = emptyList(), isPrivate = sender.isPrivate) ||
                ext.permissions.contains("activeTab")
        if (!hasHostPermission) {
            return "SecurityError: SCRIPTING_HOST_DENIED"
        }
        return null
    }

    private fun validateDynamicScriptSpec(sender: ExtensionSender, spec: DynamicContentScriptSpec) {
        val extId = sender.extensionId
        val ext = registry.getExtension(extId) ?: throw IllegalArgumentException("SCRIPTING_EXTENSION_DISABLED")

        if (spec.id.isBlank()) {
            throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
        }
        if (spec.matches.isEmpty()) {
            throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
        }
        for (pattern in spec.matches) {
            try {
                ExtensionMatchPattern(pattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
            }
        }
        spec.excludeMatches.forEach { pattern ->
            try { ExtensionMatchPattern(pattern) } catch (_: Exception) {
                throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
            }
        }
        spec.includeGlobs.forEach { glob ->
            if (glob.isBlank()) throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
            ExtensionGlobMatcher.globToJsRegex(glob)
        }
        spec.excludeGlobs.forEach { glob ->
            if (glob.isBlank()) throw IllegalArgumentException("SCRIPTING_INVALID_TARGET")
            ExtensionGlobMatcher.globToJsRegex(glob)
        }
        for (path in spec.js) {
            if (path.contains("..") || path.startsWith("/")) {
                throw IllegalArgumentException("SCRIPTING_RESOURCE_UNSAFE")
            }
        }
        for (path in spec.css) {
            if (path.contains("..") || path.startsWith("/")) {
                throw IllegalArgumentException("SCRIPTING_RESOURCE_UNSAFE")
            }
        }
        val runAtLower = spec.runAt.lowercase().trim()
        if (runAtLower != "document_start" && runAtLower != "document_end" && runAtLower != "document_idle") {
            throw IllegalArgumentException("SCRIPTING_RUNAT_UNSUPPORTED")
        }
        val worldUpper = spec.world.uppercase().trim()
        if (worldUpper != "MAIN" && worldUpper != "ISOLATED") {
            throw IllegalArgumentException("SCRIPTING_WORLD_UNSUPPORTED")
        }
    }

    private fun readExtensionFile(context: Context, extensionId: String, path: String, isPrivate: Boolean): String {
        val pm = permissionManager ?: PermissionManager(context, permissionAdapter)
        val resolver = com.swift.browser.extensionengine.resources.ExtensionResourceResolver(context, registry, pm)
        val resourceUrl = "chrome-extension://$extensionId/${path.removePrefix("./").removePrefix("/")}"
        if (path.contains("..")) {
            throw SecurityException("SCRIPTING_RESOURCE_UNSAFE")
        }
        val result = resolver.resolveResource(
            requestUrlStr = resourceUrl,
            initiatorUrlStr = "about:blank",
            isPrivate = isPrivate
        )
        return result.inputStreamProvider?.invoke()?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun validateJsonArgs(args: JSONArray?): String? {
        if (args == null) return null
        for (i in 0 until args.length()) {
            val item = args.opt(i) ?: continue
            val err = validateJsonValue(item)
            if (err != null) return err
        }
        return null
    }

    private fun validateJsonValue(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Boolean || value is Number || value is String) return null
        if (value is JSONObject) {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val err = validateJsonValue(value.opt(key))
                if (err != null) return err
            }
            return null
        }
        if (value is JSONArray) {
            for (i in 0 until value.length()) {
                val err = validateJsonValue(value.opt(i))
                if (err != null) return err
            }
            return null
        }
        return "SCRIPTING_ARGUMENT_UNSUPPORTED"
    }

    private fun parseStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }
}
