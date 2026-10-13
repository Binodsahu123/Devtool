package com.swift.browser.extensionengine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.swift.browser.extensionengine.nativecore.WebViewUserScriptWorldBridge
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * Real implementation of Chrome MV3 userScripts API.
 * Manages user script registrations, world configurations, match patterns,
 * and script execution pipelines with persistent backing.
 */
class ExtensionUserScriptsAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry,
    val worldAwareScriptExecutor: WorldAwareScriptExecutor
) {
    data class RegisteredUserScript(
        val id: String,
        val matches: List<String>,
        val excludeMatches: List<String> = emptyList(),
        val includeGlobs: List<String> = emptyList(),
        val excludeGlobs: List<String> = emptyList(),
        val js: List<String> = emptyList(),
        val jsFiles: List<Boolean> = emptyList(),
        val allFrames: Boolean = false,
        val runAt: String = "document_idle",
        val world: String = "USER_SCRIPT",
        val worldId: String = "default",
        val persistAcrossSessions: Boolean = true
    )

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("extension_user_scripts", Context.MODE_PRIVATE)
    }

    private val inMemoryScripts = ConcurrentHashMap<String, MutableMap<String, RegisteredUserScript>>()
    private val registeredWorldIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val registeredWorldLock = Any()

    /** Called by ExtensionEngineImpl after persistent registration state changes. */
    @Volatile
    var onRegistrationsChanged: (() -> Unit)? = null

    init {
        loadPersistedScripts()
    }

    private fun verifyUserScriptsPermission(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId)
            ?: throw SecurityException("SecurityError: Extension ${sender.extensionId} not found.")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "userScripts")) {
            throw SecurityException("SecurityError: Extension does not have 'userScripts' permission.")
        }
    }

    fun register(sender: ExtensionSender, scripts: JSONArray): JSONObject {
        verifyUserScriptsPermission(sender)
        val extId = sender.extensionId
        val extMap = inMemoryScripts.getOrPut(extId) { ConcurrentHashMap() }
        val staged = LinkedHashMap<String, RegisteredUserScript>()

        for (i in 0 until scripts.length()) {
            val scriptObj = scripts.getJSONObject(i)
            val id = scriptObj.getString("id").trim()
            if (id.isBlank() || id.startsWith("_")) throw IllegalArgumentException("USER_SCRIPTS_INVALID_ID")
            if (extMap.containsKey(id) || staged.containsKey(id)) {
                throw IllegalArgumentException("UserScript with ID '$id' is already registered.")
            }

            val matches = readStringArray(scriptObj.optJSONArray("matches"), "USER_SCRIPTS_MATCHES_REQUIRED")
            val excludeMatches = readStringArray(scriptObj.optJSONArray("excludeMatches"))
            val includeGlobs = readStringArray(scriptObj.optJSONArray("includeGlobs"))
            val excludeGlobs = readStringArray(scriptObj.optJSONArray("excludeGlobs"))
            val (js, jsFiles) = parseScriptSources(extId, scriptObj.optJSONArray("js"))
            if (matches.isEmpty()) throw IllegalArgumentException("USER_SCRIPTS_MATCHES_REQUIRED")
            if (js.isEmpty()) throw IllegalArgumentException("USER_SCRIPTS_JS_REQUIRED")

            val parsedWorld = scriptObj.optString("world", "USER_SCRIPT").trim().uppercase()
            if (parsedWorld != "USER_SCRIPT" && parsedWorld != "MAIN") throw IllegalArgumentException("USER_SCRIPTS_INVALID_WORLD")
            val parsedRunAt = scriptObj.optString("runAt", "document_idle").trim().lowercase()
            if (parsedRunAt !in setOf("document_start", "document_end", "document_idle")) throw IllegalArgumentException("USER_SCRIPTS_INVALID_RUN_AT")
            val parsedWorldId = scriptObj.optString("worldId", "default").trim().ifBlank { "default" }
            val persistAcrossSessions = scriptObj.optBoolean("persistAcrossSessions", true)
            validateWorldId(parsedWorldId)
            if (parsedWorld == "MAIN" && parsedWorldId != "default") throw IllegalArgumentException("USER_SCRIPTS_WORLD_ID_MAIN_INVALID")

            staged[id] = RegisteredUserScript(
                id = id,
                matches = matches,
                excludeMatches = excludeMatches,
                includeGlobs = includeGlobs,
                excludeGlobs = excludeGlobs,
                js = js,
                jsFiles = jsFiles,
                allFrames = scriptObj.optBoolean("allFrames", false),
                runAt = parsedRunAt,
                world = parsedWorld,
                worldId = parsedWorldId,
                persistAcrossSessions = persistAcrossSessions
            )
        }

        // Chrome's registration operation is transactional: validation of a batch must not leave
        // a partially registered set behind. Commit only after every item has passed validation.
        extMap.putAll(staged)
        synchronized(registeredWorldLock) {
            val ids = registeredWorldIds.getOrPut(extId) { linkedSetOf() }
            staged.values.filter { it.world.equals("USER_SCRIPT", true) }.forEach { ids += it.worldId }
        }
        savePersistedScripts(extId)
        onRegistrationsChanged?.invoke()
        return JSONObject().put("status", "success")
    }

    private fun readStringArray(array: JSONArray?, emptyError: String? = null): List<String> {
        if (array == null) {
            if (emptyError != null) return emptyList()
            return emptyList()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim()
                if (value.isBlank()) throw IllegalArgumentException("USER_SCRIPTS_INVALID_STRING_ARRAY")
                add(value)
            }
        }.distinct()
    }

    private fun parseScriptSources(extId: String, array: JSONArray?): Pair<List<String>, List<Boolean>> {
        if (array == null) return emptyList<String>() to emptyList()
        val values = mutableListOf<String>()
        val files = mutableListOf<Boolean>()
        for (i in 0 until array.length()) {
            val source = array.optJSONObject(i) ?: throw IllegalArgumentException("USER_SCRIPTS_INVALID_JS_SOURCE")
            val file = source.optString("file", "").trim()
            val code = source.optString("code", "")
            val hasFile = file.isNotBlank()
            val hasCode = code.isNotBlank()
            if (hasFile == hasCode) throw IllegalArgumentException("USER_SCRIPTS_INVALID_JS_SOURCE")
            if (hasFile) {
                validateScriptFile(extId, file)
                values += file
                files += true
            } else {
                values += code
                files += false
            }
        }
        return values to files
    }

    fun unregister(sender: ExtensionSender, filter: JSONObject?): JSONObject {
        verifyUserScriptsPermission(sender)
        val extId = sender.extensionId
        val extMap = inMemoryScripts[extId] ?: return JSONObject().put("status", "success")

        val idsArray = filter?.optJSONArray("ids")
        if (idsArray != null && idsArray.length() > 0) {
            for (i in 0 until idsArray.length()) {
                extMap.remove(idsArray.getString(i))
            }
        } else {
            extMap.clear()
        }

        savePersistedScripts(extId)
        onRegistrationsChanged?.invoke()
        return JSONObject().put("status", "success")
    }

    fun update(sender: ExtensionSender, scripts: JSONArray): JSONObject {
        verifyUserScriptsPermission(sender)
        val extId = sender.extensionId
        val extMap = inMemoryScripts[extId] ?: throw IllegalArgumentException("USER_SCRIPTS_NOT_REGISTERED")
        val staged = LinkedHashMap<String, RegisteredUserScript>()

        for (i in 0 until scripts.length()) {
            val scriptObj = scripts.getJSONObject(i)
            val id = scriptObj.getString("id").trim()
            val existing = extMap[id] ?: throw IllegalArgumentException("UserScript with ID '$id' is not registered.")

            val matches = if (scriptObj.has("matches")) readStringArray(scriptObj.getJSONArray("matches")) else existing.matches
            val excludeMatches = if (scriptObj.has("excludeMatches")) readStringArray(scriptObj.getJSONArray("excludeMatches")) else existing.excludeMatches
            val includeGlobs = if (scriptObj.has("includeGlobs")) readStringArray(scriptObj.getJSONArray("includeGlobs")) else existing.includeGlobs
            val excludeGlobs = if (scriptObj.has("excludeGlobs")) readStringArray(scriptObj.getJSONArray("excludeGlobs")) else existing.excludeGlobs
            val jsPair = if (scriptObj.has("js")) parseScriptSources(extId, scriptObj.getJSONArray("js")) else existing.js to existing.jsFiles
            val updatedWorld = scriptObj.optString("world", existing.world).trim().uppercase()
            if (updatedWorld != "USER_SCRIPT" && updatedWorld != "MAIN") throw IllegalArgumentException("USER_SCRIPTS_INVALID_WORLD")
            val updatedRunAt = scriptObj.optString("runAt", existing.runAt).trim().lowercase()
            if (updatedRunAt !in setOf("document_start", "document_end", "document_idle")) throw IllegalArgumentException("USER_SCRIPTS_INVALID_RUN_AT")
            val updatedWorldId = scriptObj.optString("worldId", existing.worldId).trim().ifBlank { "default" }
            val updatedPersistAcrossSessions = if (scriptObj.has("persistAcrossSessions")) {
                scriptObj.optBoolean("persistAcrossSessions", existing.persistAcrossSessions)
            } else {
                existing.persistAcrossSessions
            }
            validateWorldId(updatedWorldId)
            if (updatedWorld == "MAIN" && updatedWorldId != "default") throw IllegalArgumentException("USER_SCRIPTS_WORLD_ID_MAIN_INVALID")
            if (matches.isEmpty()) throw IllegalArgumentException("USER_SCRIPTS_MATCHES_REQUIRED")
            if (jsPair.first.isEmpty()) throw IllegalArgumentException("USER_SCRIPTS_JS_REQUIRED")

            staged[id] = existing.copy(
                matches = matches,
                excludeMatches = excludeMatches,
                includeGlobs = includeGlobs,
                excludeGlobs = excludeGlobs,
                js = jsPair.first,
                jsFiles = jsPair.second,
                allFrames = scriptObj.optBoolean("allFrames", existing.allFrames),
                runAt = updatedRunAt,
                world = updatedWorld,
                worldId = updatedWorldId,
                persistAcrossSessions = updatedPersistAcrossSessions
            )
        }

        extMap.putAll(staged)
        synchronized(registeredWorldLock) {
            val ids = registeredWorldIds.getOrPut(extId) { linkedSetOf() }
            staged.values.filter { it.world.equals("USER_SCRIPT", true) }.forEach { ids += it.worldId }
        }
        savePersistedScripts(extId)
        onRegistrationsChanged?.invoke()
        return JSONObject().put("status", "success")
    }

    fun execute(
        sender: ExtensionSender,
        details: JSONObject,
        delegate: BrowserDelegate?,
        callback: (JSONArray?, String?) -> Unit
    ) {
        verifyUserScriptsPermission(sender)
        val target = details.optJSONObject("target") ?: run {
            callback(null, "USER_SCRIPTS_INVALID_TARGET")
            return
        }
        val targetContract = try {
            ScriptTargetContract.parse(target, "USER_SCRIPTS_INVALID_TARGET")
        } catch (e: IllegalArgumentException) {
            callback(null, e.message ?: "USER_SCRIPTS_INVALID_TARGET")
            return
        }
        val tabId = targetContract.canonicalTabId
        val allFrames = targetContract.allFrames
        val frameIds = targetContract.frameIds
        val documentIds = targetContract.documentIds

        val jsArray = details.optJSONArray("js") ?: run {
            callback(null, "USER_SCRIPTS_JS_REQUIRED")
            return
        }
        if (jsArray.length() == 0) { callback(null, "USER_SCRIPTS_JS_REQUIRED"); return }
        val codeBuilder = StringBuilder()
        for (i in 0 until jsArray.length()) {
            val entry = jsArray.getJSONObject(i)
            val code = entry.optString("code", "")
            val file = entry.optString("file", "")
            when {
                code.isNotBlank() && file.isBlank() -> codeBuilder.append(code).append("\n")
                file.isNotBlank() && code.isBlank() -> {
                    val resolved = resolveScriptCode(sender.extensionId, RegisteredUserScript(
                        id = "__execute__",
                        matches = listOf("*://*/*"),
                        js = listOf(file),
                        jsFiles = listOf(true)
                    ))
                    if (resolved.isBlank()) { callback(null, "USER_SCRIPTS_RESOURCE_NOT_FOUND"); return }
                    codeBuilder.append(resolved).append("\n")
                }
                else -> { callback(null, "USER_SCRIPTS_INVALID_JS_SOURCE"); return }
            }
        }
        val world = details.optString("world", "USER_SCRIPT").uppercase()
        if (world != "USER_SCRIPT" && world != "MAIN") { callback(null, "USER_SCRIPTS_INVALID_WORLD"); return }
        val worldId = details.optString("worldId", "default").ifBlank { "default" }
        if (world == "MAIN" && worldId != "default") { callback(null, "USER_SCRIPTS_WORLD_ID_MAIN_INVALID"); return }
        val targetModel = ScriptExecutionTarget(
            tabId = tabId,
            allFrames = allFrames,
            frameIds = if (allFrames || documentIds.isNotEmpty()) emptyList() else frameIds.ifEmpty { listOf(0) },
            documentIds = documentIds.takeIf { it.isNotEmpty() },
            world = ContentScriptWorld.fromString(world),
            worldId = worldId,
            extensionId = sender.extensionId
        )
        val webView = delegate?.getWebView(tabId)
        if (webView == null) { callback(null, "USER_SCRIPTS_TAB_NOT_FOUND"); return }
        if (world == "USER_SCRIPT") {
            val config = getWorldConfig(sender.extensionId, worldId)
            WebViewUserScriptWorldBridge.prepareWorld(
                webView = webView,
                tabId = tabId,
                extensionId = sender.extensionId,
                worldId = worldId,
                messaging = config.second,
                isPrivate = sender.isPrivate,
                csp = config.third
            )
        }
        webView.post {
            worldAwareScriptExecutor.execute(
                delegate = delegate,
                target = targetModel,
                code = codeBuilder.toString(),
                isFunction = false,
                argsJson = JSONArray().toString(),
                documentId = documentIds.firstOrNull().orEmpty(),
                callback = { results, error ->
                    if (results == null) {
                        callback(null, error)
                    } else {
                        val array = JSONArray()
                        results.forEach { r ->
                            val obj = JSONObject()
                            obj.put("frameId", r.frameId)
                            obj.put("documentId", r.documentId)
                            obj.put("result", r.result)
                            if (r.error != null) obj.put("error", r.error)
                            array.put(obj)
                        }
                        callback(array, error)
                    }
                }
            )
        }
    }


    fun getScripts(sender: ExtensionSender, filter: JSONObject?): JSONArray {
        verifyUserScriptsPermission(sender)
        val extId = sender.extensionId
        val extMap = inMemoryScripts[extId] ?: return JSONArray()
        val result = JSONArray()

        val filterIds = filter?.optJSONArray("ids")?.let { arr ->
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        }

        for ((id, script) in extMap) {
            if (filterIds == null || id in filterIds) {
                result.put(JSONObject().apply {
                    put("id", script.id)
                    put("matches", JSONArray(script.matches))
                    put("excludeMatches", JSONArray(script.excludeMatches))
                    put("includeGlobs", JSONArray(script.includeGlobs))
                    put("excludeGlobs", JSONArray(script.excludeGlobs))
                    put("js", JSONArray(script.js.mapIndexed { index, item ->
                        val isFile = script.jsFiles.getOrNull(index) == true
                        if (isFile) JSONObject().put("file", item) else JSONObject().put("code", item)
                    }))
                    put("allFrames", script.allFrames)
                    put("runAt", script.runAt)
                    put("world", script.world)
                    put("worldId", script.worldId)
                    put("persistAcrossSessions", script.persistAcrossSessions)
                })
            }
        }

        return result
    }

    fun configureWorld(sender: ExtensionSender, properties: JSONObject): JSONObject {
        verifyUserScriptsPermission(sender)
        val extId = sender.extensionId
        val csp = properties.optString("csp", null)
        if (!csp.isNullOrBlank()) {
            throw UnsupportedOperationException("USER_SCRIPTS_CSP_UNSUPPORTED_BY_WEBVIEW")
        }
        val messaging = properties.optBoolean("messaging", false)
        val worldId = properties.optString("worldId", "default").trim().ifBlank { "default" }
        validateWorldId(worldId)
        val key = worldPrefsKey(extId, worldId)
        prefs.edit()
            .putString("${key}_csp", csp)
            .putBoolean("${key}_messaging", messaging)
            .apply()
        onRegistrationsChanged?.invoke()
        return JSONObject().put("status", "success")
    }

    fun getWorldConfigurations(sender: ExtensionSender): JSONArray {
        verifyUserScriptsPermission(sender)
        return WebViewUserScriptWorldBridge.getWorldConfigurations(sender.extensionId, prefs)
    }

    fun resetWorldConfiguration(sender: ExtensionSender, worldIdValue: String?): JSONObject {
        verifyUserScriptsPermission(sender)
        val worldId = worldIdValue?.trim()?.ifBlank { "default" } ?: "default"
        validateWorldId(worldId)
        val key = worldPrefsKey(sender.extensionId, worldId)
        prefs.edit()
            .remove("${key}_csp")
            .remove("${key}_messaging")
            .apply()
        onRegistrationsChanged?.invoke()
        return JSONObject().put("status", "success")
    }

    private fun validateWorldId(worldId: String) {
        if (worldId.length > 64 || worldId.startsWith("_") || !worldId.matches(Regex("[A-Za-z0-9_.-]+"))) {
            throw IllegalArgumentException("USER_SCRIPTS_INVALID_WORLD_ID")
        }
    }

    private fun worldPrefsKey(extensionId: String, worldId: String): String =
        "${extensionId}_world_${worldId}"

    fun isProviderWorldSupported(): Boolean = WebViewUserScriptWorldBridge.isScriptInjectionSupported()

    fun getWorldConfig(extensionId: String, requestedWorldId: String = "default"): Triple<String, Boolean, String?> {
        val id = requestedWorldId.trim().ifBlank { "default" }
        val key = worldPrefsKey(extensionId, id)
        val messaging = prefs.getBoolean("${key}_messaging", false)
        val csp = prefs.getString("${key}_csp", null)
        return Triple(id, messaging, csp)
    }

    fun installScriptOnWebView(
        webView: WebView,
        tabId: String,
        extensionId: String,
        script: RegisteredUserScript,
        code: String,
        originRules: Set<String>,
        isPrivate: Boolean,
        urlGuard: String = ExtensionMatchGuardJs.build(
            matches = script.matches,
            excludeMatches = script.excludeMatches,
            includeGlobs = script.includeGlobs,
            excludeGlobs = script.excludeGlobs,
            requireTopFrame = !script.allFrames
        )
    ): androidx.webkit.ScriptHandler? {
        val (configuredWorldId, messaging, csp) = getWorldConfig(extensionId, script.worldId)
        val effectiveWorldId = if (script.world.equals("USER_SCRIPT", ignoreCase = true) &&
            script.worldId == "default") configuredWorldId else script.worldId
        if (script.world.equals("USER_SCRIPT", ignoreCase = true)) {
            WebViewUserScriptWorldBridge.prepareWorld(
                webView = webView,
                tabId = tabId,
                extensionId = extensionId,
                worldId = effectiveWorldId,
                messaging = messaging,
                isPrivate = isPrivate,
                csp = csp
            )
        }
        val effectiveScript = if (effectiveWorldId == script.worldId) script
        else script.copy(worldId = effectiveWorldId)
        return WebViewUserScriptWorldBridge.install(
            webView = webView,
            tabId = tabId,
            extensionId = extensionId,
            script = effectiveScript,
            code = code,
            originRules = originRules,
            urlGuard = urlGuard,
            isPrivate = isPrivate
        )
    }

    /**
     * Chrome clears dynamic user-script registrations when an extension package is updated.
     * The service worker can explicitly register them again from its onInstalled(update) handler.
     */
    fun clearForExtension(extensionId: String) {
        inMemoryScripts.remove(extensionId)
        val editor = prefs.edit().remove("${extensionId}_scripts")
        val prefix = "${extensionId}_world_"
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
        synchronized(registeredWorldLock) { registeredWorldIds.remove(extensionId) }
        onRegistrationsChanged?.invoke()
    }

    fun getAllRegisteredScripts(): List<Pair<String, RegisteredUserScript>> = buildList {
        inMemoryScripts.forEach { (extensionId, scripts) ->
            if (!registry.isExtensionEnabled(extensionId)) return@forEach
            scripts.values.forEach { add(extensionId to it) }
        }
    }

    fun getMatchingUserScripts(url: String, runAt: String): List<Pair<String, RegisteredUserScript>> {
        val result = mutableListOf<Pair<String, RegisteredUserScript>>()
        for ((extId, scripts) in inMemoryScripts) {
            if (!registry.isExtensionEnabled(extId)) continue
            for ((_, script) in scripts) {
                if (script.runAt.equals(runAt, ignoreCase = true) || runAt == "all") {
                    if (ExtensionGlobMatcher.matchesUrl(
                            urlStr = url,
                            targetOrigin = null,
                            matches = script.matches,
                            excludeMatches = script.excludeMatches,
                            includeGlobs = script.includeGlobs,
                            excludeGlobs = script.excludeGlobs,
                            matchAboutBlank = false,
                            matchOriginAsFallback = false
                        )) {
                        result.add(extId to script)
                    }
                }
            }
        }
        return result
    }

    fun resolveScriptCode(extId: String, script: RegisteredUserScript): String {
        val sb = StringBuilder()
        script.js.forEachIndexed { index, item ->
            val isFile = script.jsFiles.getOrNull(index) == true
            if (isFile) {
                val extDir = ExtensionDirectoryResolver.getExtensionDir(context, extId)
                val relative = item.removePrefix("/")
                val requested = File(extDir, relative)
                val root = extDir.canonicalFile
                val canonical = runCatching { requested.canonicalFile }.getOrNull()
                if (canonical != null && canonical.path.startsWith(root.path + File.separator) &&
                    canonical.extension.equals("js", ignoreCase = true)) {
                    val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extDir, relative)
                        ?: throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_NOT_FOUND")
                    if (!file.isFile) throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_NOT_FOUND")
                    sb.append(file.readText()).append("\n")
                } else throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_PATH_INVALID")
            } else {
                sb.append(item).append("\n")
            }
        }
        return sb.toString()
    }


    private fun validateScriptFile(extId: String, rawPath: String) {
        val value = rawPath.trim()
        if (value.isBlank() || value.startsWith("/") || value.contains('\\') || value.contains("..")) {
            throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_PATH_INVALID")
        }
        val root = ExtensionDirectoryResolver.getExtensionDir(context, extId).canonicalFile
        val file = runCatching { File(root, value).canonicalFile }.getOrNull()
            ?: throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_PATH_INVALID")
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || !file.extension.equals("js", true)) {
            throw IllegalArgumentException("USER_SCRIPTS_RESOURCE_NOT_FOUND")
        }
    }

    private fun loadPersistedScripts() {
        try {
            for ((key, value) in prefs.all) {
                if (key.endsWith("_scripts") && value is String) {
                    val extId = key.removeSuffix("_scripts")
                    val arr = JSONArray(value)
                    val map = inMemoryScripts.getOrPut(extId) { ConcurrentHashMap() }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.getString("id")
                        val matchesArr = obj.optJSONArray("matches") ?: JSONArray()
                        val matches = (0 until matchesArr.length()).map { matchesArr.getString(it) }
                        val excludesArr = obj.optJSONArray("excludeMatches") ?: JSONArray()
                        val excludes = (0 until excludesArr.length()).map { excludesArr.getString(it) }
                        val includeGlobsArr = obj.optJSONArray("includeGlobs") ?: JSONArray()
                        val includeGlobs = (0 until includeGlobsArr.length()).map { includeGlobsArr.getString(it) }
                        val excludeGlobsArr = obj.optJSONArray("excludeGlobs") ?: JSONArray()
                        val excludeGlobs = (0 until excludeGlobsArr.length()).map { excludeGlobsArr.getString(it) }
                        val jsArr = obj.optJSONArray("js") ?: JSONArray()
                        val js = mutableListOf<String>()
                        val jsFiles = mutableListOf<Boolean>()
                        for (idx in 0 until jsArr.length()) {
                            val source = jsArr.optJSONObject(idx)
                                ?: throw IllegalArgumentException("USER_SCRIPTS_INVALID_PERSISTED_JS")
                            val file = source.optString("file", "").trim()
                            val code = source.optString("code", "")
                            val hasFile = file.isNotBlank()
                            val hasCode = code.isNotBlank()
                            if (hasFile == hasCode) throw IllegalArgumentException("USER_SCRIPTS_INVALID_PERSISTED_JS")
                            if (hasFile) {
                                validateScriptFile(extId, file)
                                js += file
                                jsFiles += true
                            } else {
                                js += code
                                jsFiles += false
                            }
                        }
                        map[id] = RegisteredUserScript(
                            id = id,
                            matches = matches,
                            excludeMatches = excludes,
                            includeGlobs = includeGlobs,
                            excludeGlobs = excludeGlobs,
                            js = js,
                            jsFiles = jsFiles,
                            allFrames = obj.optBoolean("allFrames", false),
                            runAt = obj.optString("runAt", "document_idle"),
                            world = obj.optString("world", "USER_SCRIPT"),
                            worldId = obj.optString("worldId", "default"),
                            persistAcrossSessions = obj.optBoolean("persistAcrossSessions", true)
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun savePersistedScripts(extId: String) {
        val map = inMemoryScripts[extId] ?: emptyMap<String, RegisteredUserScript>()
        val persistentScripts = map.values.filter { it.persistAcrossSessions }
        if (persistentScripts.isEmpty()) {
            prefs.edit().remove("${extId}_scripts").apply()
            return
        }
        val arr = JSONArray()
        for (script in persistentScripts) {
            arr.put(JSONObject().apply {
                put("id", script.id)
                put("matches", JSONArray(script.matches))
                put("excludeMatches", JSONArray(script.excludeMatches))
                put("includeGlobs", JSONArray(script.includeGlobs))
                put("excludeGlobs", JSONArray(script.excludeGlobs))
                put("js", JSONArray(script.js.mapIndexed { index, item ->
                    if (script.jsFiles.getOrNull(index) == true) JSONObject().put("file", item)
                    else JSONObject().put("code", item)
                }))
                put("allFrames", script.allFrames)
                put("runAt", script.runAt)
                put("world", script.world)
                put("worldId", script.worldId)
                put("persistAcrossSessions", script.persistAcrossSessions)
            })
        }
        prefs.edit().putString("${extId}_scripts", arr.toString()).apply()
    }
}
