package com.swift.browser.extensionengine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.JavaScriptExecutionWorld
import com.swift.browser.extensionengine.resources.ExtensionResourceResolver
import com.swift.browser.extensionengine.resources.ExtensionResourceResult
import com.swift.browser.extensionengine.security.ExtensionWebMessageBridge
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Error Model Constants
const val CONTENT_SCRIPT_RESOURCE_NOT_FOUND = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND"
const val CONTENT_SCRIPT_PERMISSION_DENIED = "CONTENT_SCRIPT_PERMISSION_DENIED"
const val CONTENT_SCRIPT_HOST_DENIED = "CONTENT_SCRIPT_HOST_DENIED"
const val CONTENT_SCRIPT_DISABLED = "CONTENT_SCRIPT_DISABLED"
const val CONTENT_SCRIPT_PRIVATE_BLOCKED = "CONTENT_SCRIPT_PRIVATE_BLOCKED"
const val CONTENT_SCRIPT_UNSUPPORTED_WORLD = "CONTENT_SCRIPT_UNSUPPORTED_WORLD"
const val CONTENT_SCRIPT_UNSUPPORTED_TIMING = "CONTENT_SCRIPT_UNSUPPORTED_TIMING"
const val CONTENT_SCRIPT_FRAME_UNAVAILABLE = "CONTENT_SCRIPT_FRAME_UNAVAILABLE"
const val CONTENT_SCRIPT_DOCUMENT_STALE = "CONTENT_SCRIPT_DOCUMENT_STALE"
const val CONTENT_SCRIPT_WEBVIEW_DESTROYED = "CONTENT_SCRIPT_WEBVIEW_DESTROYED"
const val CONTENT_SCRIPT_EXECUTION_FAILED = "CONTENT_SCRIPT_EXECUTION_FAILED"
const val CONTENT_SCRIPT_FRAME_TARGETING_UNSUPPORTED = "FRAME_TARGETING_UNSUPPORTED"
const val CONTENT_SCRIPT_DOCUMENT_TARGETING_UNSUPPORTED = "DOCUMENT_TARGETING_UNSUPPORTED"

data class ContentScriptErrorReport(
    val extensionId: String,
    val scriptId: String,
    val errorType: String,
    val errorMsg: String,
    val timestamp: Long,
    val webViewInstanceId: Int,
    val uri: String,
    val targetFrameId: Int,
    val failedStep: String
)

data class ContentScriptInjectionState(
    val webViewInstanceId: Int,
    val extensionId: String,
    val scriptId: String,
    val navigationGeneration: Int,
    val registered: Boolean,
    val injectedFrames: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>(),
    val failedFrames: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
)

data class ContentScriptRegistrationRecord(
    val extensionId: String,
    val tabId: String?,
    val frameId: Int,
    val documentId: String?,
    val scriptId: String,
    val runAt: String,
    val world: String,
    val resourcePaths: List<String>,
    val webViewInstanceId: Int
) {
    val stableKey: String = listOf(extensionId, tabId ?: "", frameId, documentId ?: "", scriptId, world).joinToString("|")
}

data class DynamicContentScriptSpec(
    val id: String,
    val matches: List<String>,
    val excludeMatches: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val js: List<String> = emptyList(),
    val css: List<String> = emptyList(),
    val runAt: String = "document_idle",
    val allFrames: Boolean = false,
    val matchAboutBlank: Boolean = false,
    val matchOriginAsFallback: Boolean = false,
    val world: String = "ISOLATED",
    val persistAcrossSessions: Boolean = true
)

enum class InjectionMode {
    NATIVE_DOCUMENT_START,
    NATIVE_EVENT_DOCUMENT_START,
    NATIVE_EVENT_DOCUMENT_END,
    FALLBACK_APPROXIMATION
}

data class ContentScriptDiagnosticReport(
    val extensionId: String,
    val scriptId: String,
    val phase: String,
    val mode: InjectionMode,
    val timingNote: String,
    val world: String,
    val isNativeWorld: Boolean,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ContentScriptManager(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val scriptInjector: ScriptInjector = ScriptInjector(),
    private val cssInjector: CssInjector = CssInjector(),
    private val extensionRegistry: ExtensionRegistry,
    private val generationLookup: (String) -> Int = { 0 }
) {
    val registry = ContentScriptRegistry()
    private val dynamicScriptStore = DynamicContentScriptStore(context)

    // Tracks ScriptHandlers registered on WebView objects to clean up on WebView destruction
    private val webViewScriptHandlers = ConcurrentHashMap<Int, MutableList<androidx.webkit.ScriptHandler>>()
    private val webViewContentBridgeListeners = ConcurrentHashMap<Int, MutableSet<String>>()

    // Tracks injected script execution keys per documentId/webView
    private val injectedTracker = ConcurrentHashMap<String, MutableSet<String>>()

    // Tracks current navigation generation per WebView hash
    private val navigationGenerations = ConcurrentHashMap<Int, Int>()

    // Bookkeeping of content script registration/injection states per WebView
    private val scriptInjectionStates = ConcurrentHashMap<Int, MutableList<ContentScriptInjectionState>>()
    private val registrationRecords = ConcurrentHashMap<String, ContentScriptRegistrationRecord>()

    // File I/O and large JS/CSS reads must not happen in the WebView/UI callback.
    // The native WebView registration itself still runs on the main thread, but its
    // inputs are prepared and cached off-main first.
    private val persistentPreparationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preparingPersistentWebViews = ConcurrentHashMap<Int, Boolean>()
    private val persistentResourceCache = ConcurrentHashMap<String, String>()

    // Standardized error report log
    private val errorReports = mutableListOf<ContentScriptErrorReport>()

    // Standardized developer diagnostic log
    private val diagnosticReports = mutableListOf<ContentScriptDiagnosticReport>()

    var onContentScriptsChanged: (() -> Unit)? = null
    var userScriptsAdapter: ExtensionUserScriptsAdapter? = null

    fun recordDiagnostic(report: ContentScriptDiagnosticReport) {
        synchronized(diagnosticReports) {
            diagnosticReports.add(report)
            if (diagnosticReports.size > 200) {
                diagnosticReports.removeAt(0)
            }
        }
    }

    fun getDiagnostics(): List<ContentScriptDiagnosticReport> {
        return synchronized(diagnosticReports) {
            diagnosticReports.toList()
        }
    }

    fun getDiagnosticsForExtension(extensionId: String): List<ContentScriptDiagnosticReport> {
        return synchronized(diagnosticReports) {
            diagnosticReports.filter { it.extensionId.equals(extensionId, ignoreCase = true) }
        }
    }

    fun getLastDiagnostic(): ContentScriptDiagnosticReport? {
        return synchronized(diagnosticReports) {
            diagnosticReports.lastOrNull()
        }
    }

    private val resourceResolver by lazy {
        ExtensionResourceResolver(context, extensionRegistry, permissionManager)
    }

    fun getLastError(): ContentScriptErrorReport? {
        return synchronized(errorReports) {
            errorReports.lastOrNull()
        }
    }

    fun getErrorReports(): List<ContentScriptErrorReport> {
        return synchronized(errorReports) {
            errorReports.toList()
        }
    }

    fun reportError(report: ContentScriptErrorReport) {
        synchronized(errorReports) {
            errorReports.add(report)
            if (errorReports.size > 100) {
                errorReports.removeAt(0)
            }
        }
        android.util.Log.e("ContentScriptManager", "Content script injection failed: $report")
    }

    fun getInjectionStatesForWebView(webViewInstanceId: Int): List<ContentScriptInjectionState> {
        return scriptInjectionStates[webViewInstanceId] ?: emptyList()
    }

    private fun isExtensionActive(extensionId: String): Boolean {
        val reg = extensionRegistry
        return reg.isExtensionEnabled(extensionId)
    }

    private fun getActiveDefinitions(isPrivate: Boolean = false): List<ContentScriptDefinition> {
        return registry.getAllActiveDefinitions().filter { def ->
            if (!isExtensionActive(def.extensionId)) return@filter false
            if (isPrivate) {
                val ext = extensionRegistry?.getExtension(def.extensionId)
                ext?.allowedInPrivate == true || permissionManager.isAllowedInPrivate(def.extensionId)
            } else true
        }
    }

    fun updateInjectionState(
        webViewInstanceId: Int,
        extensionId: String,
        scriptId: String,
        navigationGen: Int,
        registered: Boolean,
        successFrame: Int? = null,
        failedFrame: Int? = null
    ) {
        val list = scriptInjectionStates.getOrPut(webViewInstanceId) { java.util.Collections.synchronizedList(mutableListOf()) }
        val existing = list.find { it.extensionId == extensionId && it.scriptId == scriptId && it.navigationGeneration == navigationGen }
        if (existing != null) {
            if (successFrame != null) {
                existing.injectedFrames.add(successFrame)
            }
            if (failedFrame != null) {
                existing.failedFrames.add(failedFrame)
            }
        } else {
            val newState = ContentScriptInjectionState(
                webViewInstanceId = webViewInstanceId,
                extensionId = extensionId,
                scriptId = scriptId,
                navigationGeneration = navigationGen,
                registered = registered
            )
            if (successFrame != null) {
                newState.injectedFrames.add(successFrame)
            }
            if (failedFrame != null) {
                newState.failedFrames.add(failedFrame)
            }
            list.add(newState)
        }
    }

    fun getRegistrationRecords(): List<ContentScriptRegistrationRecord> = registrationRecords.values.toList()

    private fun recordRegistration(def: ContentScriptDefinition, tabId: String?, frameId: Int, documentId: String?, webViewInstanceId: Int) {
        val record = ContentScriptRegistrationRecord(
            extensionId = def.extensionId,
            tabId = tabId,
            frameId = frameId,
            documentId = documentId,
            scriptId = def.scriptId,
            runAt = def.runAt,
            world = def.world,
            resourcePaths = def.jsFiles + def.cssFiles,
            webViewInstanceId = webViewInstanceId
        )
        registrationRecords["${webViewInstanceId}|${record.stableKey}"] = record
    }

    fun onPageStarted(webView: android.webkit.WebView, url: String) {
        val hash = webView.hashCode()
        val nextGen = (navigationGenerations[hash] ?: 0) + 1
        navigationGenerations[hash] = nextGen
        scriptInjectionStates[hash]?.removeIf { it.navigationGeneration < nextGen }
        injectedTracker.remove(hash.toString())
        registrationRecords.keys.filter { it.startsWith("${hash}|") }.forEach { registrationRecords.remove(it) }

        val isEligible = !url.startsWith("swift:") && !url.startsWith("chrome://") && !url.startsWith("file://")
        if (isEligible) {
            val activeDefinitions = getActiveDefinitions()
            for (def in activeDefinitions) {
                if (def.matchesUrl(url)) {
                    updateInjectionState(
                        webViewInstanceId = hash,
                        extensionId = def.extensionId,
                        scriptId = def.scriptId,
                        navigationGen = nextGen,
                        registered = true
                    )
                }
            }
        }

        clearHistoryForWebView(hash.toString())
    }

    /**
     * Rehydrates dynamic scripting registrations that declared persistAcrossSessions=true.
     * Manifest scripts are registered separately and are never reconstructed from this store.
     */
    fun restorePersistedDynamicContentScripts(extensionId: String) {
        if (extensionId.isBlank()) return
        val persisted = dynamicScriptStore.load(extensionId)
        if (persisted.isEmpty()) return

        val existingDynamicIds = registry.getDefinitionsForExtension(extensionId)
            .filter { it.scriptId.startsWith("${extensionId}_dyn_") }
            .map { it.scriptId }
            .toSet()

        for (spec in persisted) {
            if (spec.id.isBlank()) continue
            val scriptId = generateDynamicScriptId(extensionId, spec.id)
            if (scriptId in existingDynamicIds) continue
            runCatching {
                validateDynamicScriptSpecData(spec)
                registry.register(
                    ContentScriptDefinition(
                        extensionId = extensionId,
                        scriptId = scriptId,
                        jsFiles = spec.js,
                        cssFiles = spec.css,
                        matches = spec.matches,
                        excludeMatches = spec.excludeMatches,
                        includeGlobs = spec.includeGlobs,
                        excludeGlobs = spec.excludeGlobs,
                        runAt = spec.runAt,
                        allFrames = spec.allFrames,
                        matchAboutBlank = spec.matchAboutBlank,
                        matchOriginAsFallback = spec.matchOriginAsFallback,
                        world = spec.world,
                        persist = true,
                        enabled = true
                    )
                )
            }.onFailure {
                // Corrupt or stale registrations are ignored for this process. The persisted
                // store remains untouched so a later package/resource repair can rehydrate them.
            }
        }
        registry.incrementGeneration()
        onContentScriptsChanged?.invoke()
    }

    fun registerContentScripts(extensionId: String, scripts: List<DynamicContentScriptSpec>) {
        require(extensionId.isNotBlank()) { "EXTENSION_ID_REQUIRED" }
        persistentResourceCache.keys
            .filter { it.startsWith("$extensionId|") }
            .forEach { persistentResourceCache.remove(it) }
        val seenIds = HashSet<String>()
        val existingDynamicIds = registry.getDefinitionsForExtension(extensionId)
            .filter { it.scriptId.startsWith("${extensionId}_dyn_") }
            .map { it.scriptId.removePrefix("${extensionId}_dyn_") }
            .toSet()
        for (spec in scripts) {
            if (!seenIds.add(spec.id)) throw IllegalArgumentException("DYNAMIC_SCRIPT_DUPLICATE_ID")
            if (spec.id in existingDynamicIds) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_ID_ALREADY_REGISTERED")
            }
            if (spec.id.isBlank() || spec.id.contains(Regex("[^A-Za-z0-9_-]"))) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_ID")
            }
            if (spec.matches.isEmpty()) throw IllegalArgumentException("DYNAMIC_SCRIPT_MATCHES_REQUIRED")
            val runAt = spec.runAt.lowercase().trim()
            if (runAt !in setOf("document_start", "document_end", "document_idle")) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_RUN_AT")
            }
            val world = spec.world.uppercase().trim()
            if (world !in setOf("ISOLATED", "MAIN")) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_WORLD")
            }
            if (spec.js.isEmpty() && spec.css.isEmpty()) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_EMPTY")
            }
        }
        for (spec in scripts) {
            val scriptId = generateDynamicScriptId(extensionId, spec.id)
            // Chrome rejects a duplicate registration ID; updates are handled exclusively
            // by updateContentScripts() so registration remains atomic and side-effect free.
            registry.register(
                ContentScriptDefinition(
                    extensionId = extensionId,
                    scriptId = scriptId,
                    jsFiles = spec.js,
                    cssFiles = spec.css,
                    matches = spec.matches,
                    excludeMatches = spec.excludeMatches,
                    includeGlobs = spec.includeGlobs,
                    excludeGlobs = spec.excludeGlobs,
                    runAt = spec.runAt,
                    allFrames = spec.allFrames,
                    matchAboutBlank = spec.matchAboutBlank,
                    matchOriginAsFallback = spec.matchOriginAsFallback,
                    world = spec.world,
                    persist = spec.persistAcrossSessions,
                    enabled = true
                )
            )
        }
        dynamicScriptStore.upsert(extensionId, scripts)
        registry.incrementGeneration()
        onContentScriptsChanged?.invoke()
    }

    fun unregisterContentScripts(extensionId: String, ids: List<String>?) {
        persistentResourceCache.keys
            .filter { it.startsWith("$extensionId|") }
            .forEach { persistentResourceCache.remove(it) }
        if (ids == null || ids.isEmpty()) {
            registry.unregisterDynamicForExtension(extensionId)
            dynamicScriptStore.clear(extensionId)
        } else {
            for (id in ids) {
                registry.unregister(generateDynamicScriptId(extensionId, id))
            }
            dynamicScriptStore.remove(extensionId, ids)
        }
        registry.incrementGeneration()
        onContentScriptsChanged?.invoke()
    }

    fun updateContentScripts(extensionId: String, scripts: List<DynamicContentScriptSpec>) {
        require(extensionId.isNotBlank()) { "EXTENSION_ID_REQUIRED" }
        val existing = registry.getDefinitionsForExtension(extensionId)
            .filter { it.scriptId.startsWith("${extensionId}_dyn_") }
            .associateBy { it.scriptId.removePrefix("${extensionId}_dyn_") }

        val seen = HashSet<String>()
        val validated = ArrayList<DynamicContentScriptSpec>(scripts.size)
        for (spec in scripts) {
            if (!seen.add(spec.id)) throw IllegalArgumentException("DYNAMIC_SCRIPT_DUPLICATE_ID")
            val current = existing[spec.id] ?: throw IllegalArgumentException("DYNAMIC_SCRIPT_NOT_REGISTERED")
            validateDynamicScriptSpecData(spec)

            // Update is intentionally all-or-nothing. No registry mutation occurs until every
            // supplied registration validates and every id resolves to a dynamic registration.
            validated += spec.copy(
                persistAcrossSessions = spec.persistAcrossSessions
            )
            require(current.extensionId == extensionId) { "DYNAMIC_SCRIPT_EXTENSION_MISMATCH" }
        }

        for (spec in validated) {
            registry.register(
                ContentScriptDefinition(
                    extensionId = extensionId,
                    scriptId = generateDynamicScriptId(extensionId, spec.id),
                    jsFiles = spec.js,
                    cssFiles = spec.css,
                    matches = spec.matches,
                    excludeMatches = spec.excludeMatches,
                    includeGlobs = spec.includeGlobs,
                    excludeGlobs = spec.excludeGlobs,
                    runAt = spec.runAt,
                    allFrames = spec.allFrames,
                    matchAboutBlank = spec.matchAboutBlank,
                    matchOriginAsFallback = spec.matchOriginAsFallback,
                    world = spec.world,
                    persist = spec.persistAcrossSessions,
                    enabled = true
                )
            )
        }
        dynamicScriptStore.upsert(extensionId, validated)
        registry.incrementGeneration()
        onContentScriptsChanged?.invoke()
    }

    fun getRegisteredContentScripts(extensionId: String): List<DynamicContentScriptSpec> {
        val definitions = registry.getDefinitionsForExtension(extensionId)
        return definitions.map { def ->
            val cleanId = def.scriptId.removePrefix("${extensionId}_dyn_")
            DynamicContentScriptSpec(
                id = cleanId,
                matches = def.matches,
                excludeMatches = def.excludeMatches,
                includeGlobs = def.includeGlobs,
                excludeGlobs = def.excludeGlobs,
                js = def.jsFiles,
                css = def.cssFiles,
                runAt = def.runAt,
                allFrames = def.allFrames,
                matchAboutBlank = def.matchAboutBlank,
                matchOriginAsFallback = def.matchOriginAsFallback,
                world = def.world,
                persistAcrossSessions = def.persist
            )
        }
    }

    fun registerManifestScripts(ext: ParsedExtension) {
        persistentResourceCache.keys
            .filter { it.startsWith("${ext.id}|") }
            .forEach { persistentResourceCache.remove(it) }
        ext.contentScripts.forEachIndexed { index, spec ->
            val scriptId = generateScriptId(ext.id, index, spec)
            val definition = ContentScriptDefinition(
                extensionId = ext.id,
                scriptId = scriptId,
                jsFiles = spec.js,
                cssFiles = spec.css,
                matches = spec.matches,
                excludeMatches = spec.excludeMatches,
                includeGlobs = spec.includeGlobs,
                excludeGlobs = spec.excludeGlobs,
                runAt = spec.runAt,
                allFrames = spec.allFrames,
                matchAboutBlank = spec.matchAboutBlank,
                matchOriginAsFallback = spec.matchOriginAsFallback,
                world = spec.world,
                persist = true,
                enabled = true
            )
            registry.register(definition)
        }
        registry.incrementGeneration()
    }

    fun generateScriptId(extensionId: String, index: Int, spec: ContentScriptSpec): String {
        val specString = "${spec.matches.joinToString()}|${spec.js.joinToString()}|${spec.css.joinToString()}|${spec.runAt}|${spec.allFrames}|${spec.world}"
        val hash = specString.hashCode()
        return "${extensionId}_cs_${index}_$hash"
    }

    private fun validateDynamicScriptSpecData(spec: DynamicContentScriptSpec) {
        if (spec.id.isBlank()) throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_ID")
        if (spec.matches.isEmpty()) throw IllegalArgumentException("DYNAMIC_SCRIPT_MATCHES_REQUIRED")
        spec.matches.forEach { ExtensionMatchPattern(it) }
        spec.excludeMatches.forEach { ExtensionMatchPattern(it) }
        if (spec.runAt.lowercase() !in setOf("document_start", "document_end", "document_idle")) {
            throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_RUN_AT")
        }
        if (spec.world.uppercase() !in setOf("ISOLATED", "MAIN")) {
            throw IllegalArgumentException("DYNAMIC_SCRIPT_INVALID_WORLD")
        }
        spec.js.forEach { path ->
            if (path.isBlank() || path.contains("..") || path.startsWith("/")) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_RESOURCE_UNSAFE")
            }
        }
        spec.css.forEach { path ->
            if (path.isBlank() || path.contains("..") || path.startsWith("/")) {
                throw IllegalArgumentException("DYNAMIC_SCRIPT_RESOURCE_UNSAFE")
            }
        }
        if (spec.js.isEmpty() && spec.css.isEmpty()) {
            throw IllegalArgumentException("DYNAMIC_SCRIPT_EMPTY")
        }
    }

    fun generateDynamicScriptId(extensionId: String, id: String): String {
        return "${extensionId}_dyn_$id"
    }

    fun clearHistoryForWebView(webViewHash: String) {
        injectedTracker.remove(webViewHash)
    }

    fun clearHistoryForDocument(documentId: String) {
        injectedTracker.remove(documentId)
    }

    fun cleanUpWebView(webView: android.webkit.WebView) {
        // Remove the provider-level extension execution worlds and their document-start
        // handlers together with the ordinary content-script handlers.
        runCatching { com.swift.browser.extensionengine.nativecore.WebViewExtensionWorldBridge.clear(webView) }
        runCatching { com.swift.browser.extensionengine.nativecore.WebViewUserScriptWorldBridge.clear(webView) }
        val hash = webView.hashCode()
        webViewContentBridgeListeners.remove(hash)?.forEach { listenerName ->
            try {
                ExtensionWebMessageBridge.removeWebMessageListener(webView, listenerName)
            } catch (_: Throwable) {
            }
        }
        webViewScriptHandlers.remove(hash)?.forEach { handler ->
            try {
                handler.remove()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        injectedTracker.remove(hash.toString())
        registrationRecords.keys.filter { it.startsWith("${hash}|") }.forEach { registrationRecords.remove(it) }
        scriptInjectionStates.remove(hash)
        navigationGenerations.remove(hash)
    }

    /**
     * Manifest-driven Content Script execution engine.
     * Evaluates specs against URL pattern matching, host permissions, frame targets, and lifecycle phases.
     */
    fun matchAndInject(
        evaluator: ScriptEvaluator,
        url: String,
        parsedExtensions: List<ParsedExtension>,
        runAtFilter: String,
        bootstrapScriptProvider: (String) -> String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        tabId: String? = null,
        frameId: Int = 0,
        documentId: String? = null,
        targetOrigin: String? = null
    ): String {
        if (documentId != null) return CONTENT_SCRIPT_DOCUMENT_TARGETING_UNSUPPORTED
        if (frameId != 0) return CONTENT_SCRIPT_FRAME_TARGETING_UNSUPPORTED
        if (url.startsWith("swift:") || url.startsWith("chrome://")) return "SUCCESS"

        // 1. Ensure all enabled extensions have their manifest scripts registered in our registry
        for (ext in parsedExtensions) {
            if (registry.getDefinitionsForExtension(ext.id).isEmpty() && ext.contentScripts.isNotEmpty()) {
                registerManifestScripts(ext)
            }
        }

        // 2. Decide if we use native persistent script injection
        // If native persistent script injection is supported for this phase, we skip manual injection!
        val documentStartSupported = OrionWebViewScriptingCapabilities.isDocumentStartScriptSupported()
        val frameAndWorldSupported = OrionWebViewScriptingCapabilities.isFrameAndWorldInjectionSupported()

        val runAtLower = runAtFilter.lowercase().trim()

        if (runAtLower == "document_start" && (documentStartSupported || frameAndWorldSupported)) {
            // Handled natively by WebViewCompat.addDocumentStartJavaScript / addJavaScriptOnEvent!
            return "NATIVE_INJECTION_SCHEDULED"
        }
        if (runAtLower == "document_end" && frameAndWorldSupported) {
            // Handled natively by WebViewCompat.addJavaScriptOnEvent!
            return "NATIVE_INJECTION_SCHEDULED"
        }
        if (runAtLower == "document_idle" && frameAndWorldSupported) {
            // Handled natively by WebViewCompat.addJavaScriptOnEvent!
            return "NATIVE_INJECTION_SCHEDULED"
        }

        val status = when (runAtLower) {
            "document_end" -> "DOCUMENT_END_APPROXIMATION"
            "document_idle" -> "DOCUMENT_IDLE_APPROXIMATION"
            "document_start" -> "DOCUMENT_START_APPROXIMATION"
            else -> "SUCCESS"
        }

        val targetScripts = registry.getAllActiveDefinitions().filter { def ->
            def.runAt.lowercase().trim() == runAtLower
        }

        for (def in targetScripts) {
            val ext = parsedExtensions.find { it.id == def.extensionId }
                ?: extensionRegistry?.getExtension(def.extensionId)
                ?: continue

            // Pre-injection Check 1: Extension enabled
            if (extensionRegistry != null && !extensionRegistry.isExtensionEnabled(ext.id)) {
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_DISABLED",
                    errorMsg = "Extension is disabled: ${ext.id}",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "pre_injection_check_enabled"
                ))
                continue
            }

            // Pre-injection Check 2: Manifest validity
            if (ext.id.isBlank() || ext.manifestVersion < 2) {
                continue
            }

            // Pre-injection Check 3: Private mode policy
            if (isPrivate && !ext.allowedInPrivate && !permissionManager.isAllowedInPrivate(ext.id)) {
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_PRIVATE_BLOCKED",
                    errorMsg = "Extension is not allowed in private mode: ${ext.id}",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "pre_injection_check_private"
                ))
                continue
            }

            // Check all_frames targeting filter
            if (frameId > 0 && !def.allFrames) {
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_FRAME_UNAVAILABLE",
                    errorMsg = "Script does not target subframes: ${def.scriptId}",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "pre_injection_check_frame"
                ))
                continue
            }

            // Pre-injection Check 4: URL match pattern, globs, exclude matches, match_about_blank
            if (!def.matchesUrl(url, targetOrigin)) {
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_HOST_DENIED",
                    errorMsg = "URL does not match script patterns: $url",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "pre_injection_check_url"
                ))
                continue
            }

            // Pre-injection Check 5: Host permission validation
            val hasPermission = permissionManager.hasHostPermission(ext.id, ext.hostPermissions, ext.permissions, url) ||
                    permissionManager.hasHostPermission(ext.id, def.matches, emptyList(), url)

            if (!hasPermission) {
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_PERMISSION_DENIED",
                    errorMsg = "Missing host permission for URL: $url",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "pre_injection_check_permission"
                ))
                continue
            }

            val generation = generationLookup(ext.id)
            val docToken = documentId ?: "default"
            recordRegistration(def, tabId, frameId, documentId, evaluator.hashCode())

            // 1. CSS injection is unavailable in this WebView provider. Do not emulate it.
            if (def.cssFiles.isNotEmpty() && !OrionWebViewScriptingCapabilities.isCssInjectionSupported()) {
                reportError(ContentScriptErrorReport(ext.id, def.scriptId, CONTENT_SCRIPT_CSS_UNSUPPORTED,
                    "Native CSS injection is unavailable; CSS was not evaluated as JavaScript", System.currentTimeMillis(),
                    evaluator.hashCode(), url, frameId, "css_capability"))
            }
            // CSS injection is unsupported by this provider. The diagnostic above is
            // the only result; CSS is never converted into JavaScript.

            // 2. JS Fallback Injection
            // For document_idle, implement controlled post-load scheduling strategy
            if (runAtLower == "document_idle") {
                evaluator.post {
                    injectJsFallback(evaluator, def, ext, url, frameId, isPrivate, generation, docToken, bootstrapScriptProvider)
                }
            } else {
                injectJsFallback(evaluator, def, ext, url, frameId, isPrivate, generation, docToken, bootstrapScriptProvider)
            }
        }

        // 3. USER_SCRIPT pipeline. When execution-world transport exists, persistent native
        // handlers own the injection and this path must not run a second copy.
        if (userScriptsAdapter?.isProviderWorldSupported() != true) {
            val matchedUserScripts = userScriptsAdapter?.getMatchingUserScripts(url, runAtLower) ?: emptyList()
            for ((extId, script) in matchedUserScripts) {
                val ext = parsedExtensions.find { it.id == extId }
                    ?: extensionRegistry?.getExtension(extId)
                    ?: continue
                if (extensionRegistry != null && !extensionRegistry.isExtensionEnabled(extId)) continue
                if (isPrivate && !ext.allowedInPrivate && !permissionManager.isAllowedInPrivate(extId)) continue
                if (frameId > 0 && !script.allFrames) continue
                val userScriptCode = userScriptsAdapter?.resolveScriptCode(extId, script) ?: ""
                if (userScriptCode.isBlank()) continue
                val generation = generationLookup(extId)
                val docToken = documentId ?: "default"
                val scriptKey = "userscript_${extId}_${script.id}_${generation}_$docToken"
                val wrappedScript = """
                    (function() {
                        window._swiftUserScripts = window._swiftUserScripts || {};
                        if (window._swiftUserScripts["$scriptKey"]) return;
                        window._swiftUserScripts["$scriptKey"] = true;
                        try { (function(){ $userScriptCode })(); }
                        catch (e) {
                            try { console.error("[UserScript Execution Error: ${script.id}]", e); } catch (_) {}
                        }
                    })();
                """.trimIndent()
                if (runAtLower == "document_idle") {
                    evaluator.post { scriptInjector.injectScript(evaluator, wrappedScript) }
                } else {
                    scriptInjector.injectScript(evaluator, wrappedScript)
                }
            }
        }

        return status
    }

    private fun injectJsFallback(
        evaluator: ScriptEvaluator,
        def: ContentScriptDefinition,
        ext: ParsedExtension,
        url: String,
        frameId: Int,
        isPrivate: Boolean,
        generation: Int,
        docToken: String,
        bootstrapScriptProvider: (String) -> String
    ) {
        val isMainWorld = def.world.equals("MAIN", ignoreCase = true)

        recordDiagnostic(
            ContentScriptDiagnosticReport(
                extensionId = ext.id,
                scriptId = def.scriptId,
                phase = def.runAt.lowercase().trim(),
                mode = InjectionMode.FALLBACK_APPROXIMATION,
                timingNote = when (def.runAt.lowercase().trim()) {
                    "document_start" -> "Degraded fallback: evaluated at earliest available callback; WebView provider lacks document-start API"
                    "document_end" -> "Degraded fallback: evaluated post-DOM ready; WebView provider lacks native frame-and-world injection"
                    "document_idle" -> "Degraded fallback: scheduled via post/idle; exact Chrome timing is not supported by this WebView provider"
                    else -> "Fallback evaluateJavascript execution"
                },
                world = def.world,
                isNativeWorld = false,
                url = url
            )
        )

        for (cssPath in def.cssFiles) {
            try {
                val cssContent = readExtensionFile(
                    extensionId = ext.id,
                    relativePath = cssPath,
                    isPrivate = isPrivate,
                    webViewHash = evaluator.hashCode(),
                    url = url,
                    frameId = frameId,
                    scriptId = def.scriptId
                )
                if (cssContent.isNotBlank()) {
                    val styleKey = "ext_${ext.id}_${cssPath.hashCode()}"
                    cssInjector.injectCssWithKey(evaluator, styleKey, cssContent)
                }
            } catch (_: Throwable) {}
        }

        for (jsPath in def.jsFiles) {
            try {
                val jsContent = readExtensionFile(
                    extensionId = ext.id,
                    relativePath = jsPath,
                    isPrivate = isPrivate,
                    webViewHash = evaluator.hashCode(),
                    url = url,
                    frameId = frameId,
                    scriptId = def.scriptId
                )
                if (jsContent.isNotBlank()) {
                    val jsKey = "script_${ext.id}_${jsPath.hashCode()}_${generation}_$docToken"
                    val bootScript = bootstrapScriptProvider(ext.id)

                    val selfContainedScopedScript = if (isMainWorld) {
                        """
                            (function() {
                                window._swiftScriptsInjected = window._swiftScriptsInjected || {};
                                if (window._swiftScriptsInjected["$jsKey"]) return;
                                window._swiftScriptsInjected["$jsKey"] = true;
                                try {
                                    $bootScript
                                    $jsContent
                                } catch (e) {
                                    console.error("MAIN World Content Script Exec Error in $jsPath: ", e);
                                }
                            })();
                        """.trimIndent()
                    } else {
                        """
                            (function() {
                                "use strict";
                                window._swiftScriptsInjected = window._swiftScriptsInjected || {};
                                if (window._swiftScriptsInjected["$jsKey"]) return;
                                window._swiftScriptsInjected["$jsKey"] = true;
                                try {
                                    $bootScript
                                    (function(chrome, browser) {
                                        $jsContent
                                    })(window.chrome, window.browser || window.chrome);
                                } catch (e) {
                                    console.error("[Swift Virtual Isolated World Content Script Error in $jsPath]: ", e);
                                }
                            })();
                        """.trimIndent()
                    }

                    val startGen = navigationGenerations[evaluator.hashCode()] ?: 0
                    scriptInjector.injectScript(evaluator, selfContainedScopedScript) { value ->
                        val endGen = navigationGenerations[evaluator.hashCode()] ?: 0
                        if (startGen != endGen) {
                            reportError(ContentScriptErrorReport(
                                extensionId = ext.id,
                                scriptId = def.scriptId,
                                errorType = "CONTENT_SCRIPT_DOCUMENT_STALE",
                                errorMsg = "Navigation occurred during injection. Generation changed from $startGen to $endGen",
                                timestamp = System.currentTimeMillis(),
                                webViewInstanceId = evaluator.hashCode(),
                                uri = url ?: "",
                                targetFrameId = frameId,
                                failedStep = "callback_received"
                            ))
                            updateInjectionState(
                                webViewInstanceId = evaluator.hashCode(),
                                extensionId = ext.id,
                                scriptId = def.scriptId,
                                navigationGen = startGen,
                                registered = true,
                                failedFrame = frameId
                            )
                        } else {
                            updateInjectionState(
                                webViewInstanceId = evaluator.hashCode(),
                                extensionId = ext.id,
                                scriptId = def.scriptId,
                                navigationGen = startGen,
                                registered = true,
                                successFrame = frameId
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                reportError(ContentScriptErrorReport(
                    extensionId = ext.id,
                    scriptId = def.scriptId,
                    errorType = "CONTENT_SCRIPT_EXECUTION_FAILED",
                    errorMsg = e.message ?: "JS Injection failed",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = evaluator.hashCode(),
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "inject_javascript"
                ))
            }
        }
    }

    /**
     * Register all currently active manifest/dynamic content scripts on a WebView.
     * This method is idempotent for an already-registered WebView. Use
     * refreshPersistentScriptsForWebView() when the extension set changes.
     */
    fun registerPersistentScriptsForWebView(
        webView: android.webkit.WebView,
        isPrivate: Boolean = false
    ) {
        val hash = webView.hashCode()
        if (webViewScriptHandlers.containsKey(hash)) return

        // Never read extension files synchronously from setupWebView(). Prepare them
        // on Dispatchers.IO, then post only the WebViewCompat registration work back
        // to the UI thread. This removes the install/navigation freeze caused by
        // reading and concatenating every JS/CSS file on Main.
        if (preparingPersistentWebViews.putIfAbsent(hash, true) == null) {
            persistentPreparationScope.launch {
                try {
                    warmPersistentScriptResources(isPrivate)
                } finally {
                    preparingPersistentWebViews.remove(hash)
                    webView.post {
                        if (!webViewScriptHandlers.containsKey(hash)) {
                            registerPersistentScriptsForWebViewInternal(webView, hash, isPrivate)
                        }
                    }
                }
            }
        }
    }

    /**
     * Replace the native ScriptHandlers for a WebView with the current active
     * extension set. This is required after install/enable/disable/uninstall
     * so future navigations always see the latest extension registry.
     */
    fun refreshPersistentScriptsForWebView(
        webView: android.webkit.WebView,
        isPrivate: Boolean = false
    ) {
        val hash = webView.hashCode()
        val refresh = {
            webViewScriptHandlers.remove(hash)?.forEach { handler ->
                try { handler.remove() } catch (_: Throwable) {}
            }
            registerPersistentScriptsForWebView(webView, isPrivate)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh()
        } else {
            webView.post(refresh)
        }
    }

    private fun warmPersistentScriptResources(isPrivate: Boolean) {
        for (def in getActiveDefinitions(isPrivate)) {
            for (path in def.cssFiles + def.jsFiles) {
                val key = persistentResourceKey(def.extensionId, path, isPrivate)
                if (persistentResourceCache.containsKey(key)) continue
                // A null initiator explicitly marks this as an internal extension
                // read; it must not be checked as a web_accessible_resources request.
                readExtensionFile(
                    extensionId = def.extensionId,
                    relativePath = path,
                    isPrivate = isPrivate,
                    webViewHash = 0,
                    url = null,
                    frameId = 0,
                    scriptId = def.scriptId
                )
            }
        }
    }

    private fun persistentResourceKey(extensionId: String, path: String, isPrivate: Boolean): String =
        "$extensionId|$isPrivate|${path.removePrefix("./").removePrefix("/")}"

    private fun buildNativeUrlGuard(def: ContentScriptDefinition): String =
        ExtensionMatchGuardJs.build(
            matches = def.matches,
            excludeMatches = def.excludeMatches,
            includeGlobs = def.includeGlobs,
            excludeGlobs = def.excludeGlobs,
            matchAboutBlank = def.matchAboutBlank,
            matchOriginAsFallback = def.matchOriginAsFallback,
            requireTopFrame = !def.allFrames
        )

    private fun registerPersistentScriptsForWebViewInternal(
        webView: android.webkit.WebView,
        hash: Int,
        isPrivate: Boolean = false
    ) {
        val handlers = mutableListOf<androidx.webkit.ScriptHandler>()
        val activeDefinitions = getActiveDefinitions(isPrivate)

        // Remove listeners belonging to an older extension set before installing
        // the current set. This prevents stale bridges surviving disable/uninstall.
        com.swift.browser.extensionengine.nativecore.WebViewUserScriptWorldBridge.clear(webView)
        webViewContentBridgeListeners.remove(hash)?.forEach { listenerName ->
            try {
                ExtensionWebMessageBridge.removeWebMessageListener(webView, listenerName)
            } catch (_: Throwable) {
            }
        }

        // A content script may execute in an AndroidX isolated world. The ordinary
        // JavascriptInterface is not a reliable transport into that world, so give
        // each active extension a world-scoped WebMessage listener on the tab WebView.
        // The RuntimeBridge still performs the authoritative extension/permission
        // validation after the message arrives.
        val listenerNames = linkedSetOf<String>()
        for (extensionId in activeDefinitions.map { it.extensionId }.distinct()) {
            // Ensure the extension-specific real isolated world exists before document_start.
            com.swift.browser.extensionengine.nativecore.WebViewExtensionWorldBridge.prepareExtensionWorld(
                webView, extensionId = extensionId, tabId = tabIdForWebView(webView)
            )
            val listenerName = contentBridgeListenerName(extensionId)
            val extensionDefinitions = activeDefinitions.filter {
                it.extensionId.equals(extensionId, ignoreCase = true)
            }
            // WebMessageListener is a transport boundary, not an authorization
            // boundary. Keep its allow-list no broader than the extension's
            // manifest match patterns and re-check the origin after delivery.
            val allowedOrigins = extensionDefinitions
                .flatMap { getOriginRules(it.matches) }
                .toSet()
            if (allowedOrigins.isEmpty()) continue
            try {
                ExtensionWebMessageBridge.removeWebMessageListener(webView, listenerName)
                val world = if (ExtensionWebMessageBridge.isWorldScopedWebMessageSupported()) {
                    JavaScriptExecutionWorld("isolated_world_$extensionId", webView)
                } else {
                    null
                }
                val status = ExtensionWebMessageBridge.setupWebMessageListener(
                    webView = webView,
                    listenerName = listenerName,
                    allowedOrigins = allowedOrigins,
                    world = world,
                    requireWorldScoped = true
                ) { payload, sourceOrigin, isMainFrame, replyProxy ->
                    // Child frames are valid extension contexts. Keep the origin authorization
                    // check, but do not discard them merely because WebView reports isMainFrame=false.
                    val normalizedOrigin = sourceOrigin.trim().removeSuffix("/")
                    val originAllowed = normalizedOrigin.isNotBlank() &&
                        normalizedOrigin != "null" &&
                        extensionDefinitions.any { definition ->
                            definition.matchesUrl(normalizedOrigin, normalizedOrigin)
                        }
                    if (!originAllowed) {
                        android.util.Log.w(
                            "ContentScriptManager",
                            "Rejected content-script bridge origin=$sourceOrigin extension=$extensionId"
                        )
                        return@setupWebMessageListener
                    }
                    try {
                        val root = JSONObject(payload)
                        val callbackId = root.optString("callbackId", "")
                        val runtimeBridge = webView.tag as? RuntimeBridge
                        if (runtimeBridge == null) {
                            replyProxy?.postMessage(JSONObject().put("error", "EXTENSION_CONTEXT_UNAVAILABLE").toString())
                            return@setupWebMessageListener
                        }
                        runtimeBridge.postWebMessage(payload, callbackId, replyProxy)
                    } catch (e: Throwable) {
                        android.util.Log.w("ContentScriptManager", "Invalid content-script bridge payload", e)
                    }
                }
                if (status == ExtensionWebMessageBridge.BridgeFeatureStatus.SUPPORTED) {
                    listenerNames.add(listenerName)
                }
            } catch (e: Throwable) {
                android.util.Log.w("ContentScriptManager", "Content bridge registration failed for $extensionId", e)
            }
        }
        if (listenerNames.isEmpty()) {
            webViewContentBridgeListeners.remove(hash)
        } else {
            webViewContentBridgeListeners[hash] = listenerNames
        }

        for (def in activeDefinitions) {
            val isMain = def.world.uppercase().trim() == "MAIN"
            val runAtLower = def.runAt.lowercase().trim()
            val originRules = getOriginRules(def.matches)
            recordRegistration(def, null, 0, null, hash)

            if (def.cssFiles.isNotEmpty() && !OrionWebViewScriptingCapabilities.isCssInjectionSupported()) {
                recordDiagnostic(ContentScriptDiagnosticReport(def.extensionId, def.scriptId, runAtLower,
                    InjectionMode.FALLBACK_APPROXIMATION,
                    "CSS injection unsupported by provider; CSS was not injected", def.world, false,
                    def.matches.joinToString(",")))
            }
            if (runAtLower !in setOf("document_start", "document_end", "document_idle")) continue
            if (def.matches.isEmpty()) continue
            if (!OrionWebViewScriptingCapabilities.isFrameAndWorldInjectionSupported()) {
                if (runAtLower != "document_start" || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    continue
                }
            }

            val jsContentBuilder = StringBuilder()
            val urlGuard = buildNativeUrlGuard(def)
            // Keep the entire payload behind a conditional block; a top-level `return` would be
            // a syntax error in WebView's script evaluator.
            jsContentBuilder.append("if (").append(urlGuard).append(") {\n")
            val bootScript = ExtensionDirectoryResolver.bootstrapProvider?.invoke(def.extensionId) ?: ""
            jsContentBuilder.append(bootScript).append("\n")

            for (cssPath in def.cssFiles) {
                try {
                    val cssContent = readExtensionFile(
                        extensionId = def.extensionId,
                        relativePath = cssPath,
                        isPrivate = isPrivate,
                        webViewHash = hash,
                        url = null,
                        frameId = 0,
                        scriptId = def.scriptId
                    )
                    if (cssContent.isNotBlank()) {
                        val escapedCss = org.json.JSONObject.quote(cssContent)
                        jsContentBuilder.append("""
                            (function() {
                                try {
                                    var style = document.createElement('style');
                                    style.type = 'text/css';
                                    style.appendChild(document.createTextNode($escapedCss));
                                    (document.head || document.documentElement || document.body).appendChild(style);
                                } catch(e) {}
                            })();
                        """.trimIndent()).append("\n")
                    }
                } catch (e: Throwable) {
                    recordDiagnostic(ContentScriptDiagnosticReport(def.extensionId, def.scriptId, runAtLower,
                        InjectionMode.FALLBACK_APPROXIMATION, "CSS resource preparation failed: ${e.message ?: e::class.java.simpleName}",
                        def.world, false, def.matches.joinToString(",")))
                }
            }

            for (jsPath in def.jsFiles) {
                try {
                    val fileContent = readExtensionFile(
                        extensionId = def.extensionId,
                        relativePath = jsPath,
                        isPrivate = isPrivate,
                        webViewHash = hash,
                        // This is an internal extension read, not a web-page request.
                        // A synthetic about:blank initiator is treated as an external page
                        // by the web_accessible_resources policy and can deny valid files.
                        url = null,
                        frameId = 0,
                        scriptId = def.scriptId
                    )
                    if (fileContent.isNotBlank()) {
                        jsContentBuilder.append(fileContent).append("\n")
                    }
                } catch (e: Throwable) {
                    recordDiagnostic(ContentScriptDiagnosticReport(def.extensionId, def.scriptId, runAtLower,
                        InjectionMode.FALLBACK_APPROXIMATION, "JS resource preparation failed: ${e.message ?: e::class.java.simpleName}",
                        def.world, false, def.matches.joinToString(",")))
                }
            }

            jsContentBuilder.append("\n}\n")
            var compiledJs = jsContentBuilder.toString()
            if (compiledJs.isBlank()) continue

            // document_idle is native on WebView via DOCUMENT_END event combined with idle scheduling.
            // In Chrome, document_idle executes between document_end and shortly after window.onload.
            // Android WebView approximates this using native DOCUMENT_END event plus requestIdleCallback / load listener.
            if (runAtLower == "document_idle") {
                compiledJs = """
                    (function(__runScript){
                        function __execIdle() {
                            if (typeof window.requestIdleCallback === 'function') {
                                window.requestIdleCallback(__runScript, { timeout: 200 });
                            } else {
                                setTimeout(__runScript, 0);
                            }
                        }
                        if (document.readyState === 'complete') {
                            __execIdle();
                        } else {
                            var __fired = false;
                            function __onLoad() {
                                if (__fired) return;
                                __fired = true;
                                window.removeEventListener('load', __onLoad);
                                __execIdle();
                            }
                            window.addEventListener('load', __onLoad);
                        }
                    })(function() {
                        $compiledJs
                    });
                """.trimIndent()
            }

            val topFrameGuard = if (!def.allFrames) {
                "if (window.top !== window) return;\n"
            } else {
                ""
            }
            val wrappedJs = if (!isMain && !WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                """
                (function() {
                    "use strict";
                    $topFrameGuard
                    (function(chrome, browser) {
                        $compiledJs
                    })(window.chrome, window.browser || window.chrome);
                })();
                """.trimIndent()
            } else {
                if (topFrameGuard.isBlank()) compiledJs else """(function(){
                    $topFrameGuard
                    $compiledJs
                })();""".trimIndent()
            }

            try {
                if (runAtLower == "document_start") {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                        val worldObj = if (isMain) {
                            JavaScriptExecutionWorld(JavaScriptExecutionWorld.PAGE_WORLD_NAME, webView)
                        } else {
                            JavaScriptExecutionWorld("isolated_world_${def.extensionId}", webView)
                        }
                        val handler = WebViewCompat.addJavaScriptOnEvent(
                            webView,
                            wrappedJs,
                            WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                            originRules,
                            worldObj
                        )
                        handlers.add(handler)
                        recordDiagnostic(
                            ContentScriptDiagnosticReport(
                                extensionId = def.extensionId,
                                scriptId = def.scriptId,
                                phase = runAtLower,
                                mode = InjectionMode.NATIVE_EVENT_DOCUMENT_START,
                                timingNote = "Native WebViewCompat document-start injection in ${if (isMain) "MAIN" else "ISOLATED"} world",
                                world = def.world,
                                isNativeWorld = true,
                                url = def.matches.joinToString(",")
                            )
                        )
                    } else if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        // Older WebView: document-start is still native, but cannot select an execution world.
                        val handler = WebViewCompat.addDocumentStartJavaScript(
                            webView,
                            wrappedJs,
                            originRules
                        )
                        handlers.add(handler)
                        recordDiagnostic(
                            ContentScriptDiagnosticReport(
                                extensionId = def.extensionId,
                                scriptId = def.scriptId,
                                phase = runAtLower,
                                mode = InjectionMode.NATIVE_DOCUMENT_START,
                                timingNote = "Native WebViewCompat document-start injection (global execution world only; provider lacks JS_INJECTION_IN_FRAME_AND_WORLD)",
                                world = def.world,
                                isNativeWorld = false,
                                url = def.matches.joinToString(",")
                            )
                        )
                    }
                } else if (runAtLower == "document_end" || runAtLower == "document_idle") {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                        val worldObj = if (isMain) {
                            JavaScriptExecutionWorld(JavaScriptExecutionWorld.PAGE_WORLD_NAME, webView)
                        } else {
                            JavaScriptExecutionWorld("isolated_world_${def.extensionId}", webView)
                        }

                        val handler = WebViewCompat.addJavaScriptOnEvent(
                            webView,
                            wrappedJs,
                            WebViewCompat.INJECTION_EVENT_DOCUMENT_END,
                            originRules,
                            worldObj
                        )
                        handlers.add(handler)
                        recordDiagnostic(
                            ContentScriptDiagnosticReport(
                                extensionId = def.extensionId,
                                scriptId = def.scriptId,
                                phase = runAtLower,
                                mode = InjectionMode.NATIVE_EVENT_DOCUMENT_END,
                                timingNote = if (runAtLower == "document_idle") {
                                    "Native WebViewCompat document-end with idle scheduling (requestIdleCallback / onload fallback)"
                                } else {
                                    "Native WebViewCompat document-end injection (DOMContentLoaded)"
                                },
                                world = def.world,
                                isNativeWorld = true,
                                url = def.matches.joinToString(",")
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        
        // USER_SCRIPT has its own provider world. Install persistent event handlers once per
        // WebView refresh; match evaluation remains shared with the existing adapter/guard logic.
        if (userScriptsAdapter?.isProviderWorldSupported() == true) {
            userScriptsAdapter?.getAllRegisteredScripts()?.forEach { (extId, script) ->
                val ext = extensionRegistry.getExtension(extId) ?: return@forEach
                if (!extensionRegistry.isExtensionEnabled(extId)) return@forEach
                if (isPrivate && !ext.allowedInPrivate && !permissionManager.isAllowedInPrivate(extId)) return@forEach
                val runAt = script.runAt.lowercase().trim()
                if (runAt !in setOf("document_start", "document_end", "document_idle")) return@forEach
                if (script.matches.isEmpty()) return@forEach
                val code = userScriptsAdapter?.resolveScriptCode(extId, script).orEmpty()
                if (code.isBlank()) return@forEach
                val originRules = getOriginRules(script.matches)
                val guard = ExtensionMatchGuardJs.build(
                    matches = script.matches,
                    excludeMatches = script.excludeMatches,
                    requireTopFrame = !script.allFrames
                )
                val handler = userScriptsAdapter?.installScriptOnWebView(
                    webView = webView,
                    tabId = tabIdForWebView(webView),
                    extensionId = extId,
                    script = script,
                    code = code,
                    originRules = originRules,
                    isPrivate = isPrivate,
                    urlGuard = guard
                )
                if (handler != null) {
                    handlers.add(handler)
                    recordDiagnostic(ContentScriptDiagnosticReport(
                        extensionId = extId,
                        scriptId = script.id,
                        phase = runAt,
                        mode = InjectionMode.NATIVE_EVENT_DOCUMENT_END,
                        timingNote = "Native AndroidX USER_SCRIPT world; ${if (script.allFrames) "all frames" else "top frame"}",
                        world = "USER_SCRIPT",
                        isNativeWorld = true,
                        url = script.matches.joinToString(",")
                    ))
                } else {
                    recordDiagnostic(ContentScriptDiagnosticReport(
                        extensionId = extId,
                        scriptId = script.id,
                        phase = runAt,
                        mode = InjectionMode.FALLBACK_APPROXIMATION,
                        timingNote = "USER_SCRIPT world unavailable on this WebView provider",
                        world = "USER_SCRIPT",
                        isNativeWorld = false,
                        url = script.matches.joinToString(",")
                    ))
                }
            }
        }

        webViewScriptHandlers[hash] = handlers
    }

    private fun tabIdForWebView(webView: android.webkit.WebView): String =
        (webView.tag as? RuntimeBridge)?.tabId ?: webView.hashCode().toString()

    private fun contentBridgeListenerName(extensionId: String): String =
        "__orionContentBridge_" + extensionId.replace(Regex("[^A-Za-z0-9_]"), "_")

    fun convertPatternToOriginRule(pattern: String): String {
        val clean = pattern.trim()
        if (clean == "<all_urls>" || clean == "*" || clean == "*://*/*") return "*"

        // WebView allowedOriginRules do not accept a scheme wildcard.
        // A Chrome pattern such as *://*.example.com/* therefore becomes
        // two explicit origin rules. getOriginRules() handles this expansion.
        val withoutPath = clean.substringBefore('/', clean).trimEnd('/')
        val scheme = withoutPath.substringBefore("://", "").lowercase()
        val authority = withoutPath.substringAfter("://", "")
        if (scheme !in setOf("http", "https")) {
            val custom = scheme.ifBlank { clean.substringBefore(":", "") }
            return if (custom.isNotBlank()) "$custom://" else "*"
        }

        val hostPort = authority.substringBefore('/')
        val host = hostPort.substringBefore(':').trim()
        val port = hostPort.substringAfter(':', "")
        if (host.isBlank()) return "*"

        val normalizedHost = when {
            host == "*" -> "*"
            host.startsWith("*.") -> host
            else -> host.lowercase()
        }
        return buildString {
            append(scheme)
            append("://")
            append(normalizedHost)
            if (port.isNotBlank()) append(":").append(port)
        }
    }

    fun getOriginRules(matches: List<String>): Set<String> {
        val rules = linkedSetOf<String>()
        for (pattern in matches) {
            val clean = pattern.trim()
            if (clean == "<all_urls>" || clean == "*" || clean == "*://*/*") {
                rules.clear()
                rules.add("*")
                break
            }
            if (clean.startsWith("*://")) {
                val withoutPath = clean.substringBefore('/', clean).trimEnd('/')
                val authority = withoutPath.removePrefix("*://")
                val hostPort = authority.substringBefore('/')
                val host = hostPort.substringBefore(':')
                val port = hostPort.substringAfter(':', "")
                if (host.isBlank()) {
                    rules.add("*")
                    continue
                }
                val normalizedHost = host.lowercase()
                rules.add("https://$normalizedHost" + if (port.isNotBlank()) ":$port" else "")
                rules.add("http://$normalizedHost" + if (port.isNotBlank()) ":$port" else "")
            } else {
                rules.add(convertPatternToOriginRule(clean))
            }
        }
        return rules
    }

    private fun readExtensionFile(
        extensionId: String,
        relativePath: String,
        isPrivate: Boolean,
        webViewHash: Int,
        url: String?,
        frameId: Int,
        scriptId: String
    ): String {
        try {
            val cacheKey = persistentResourceKey(extensionId, relativePath, isPrivate)
            if (url == null) {
                persistentResourceCache[cacheKey]?.let { return it }
            }
            val resourceUrl = "chrome-extension://$extensionId/${relativePath.removePrefix("./").removePrefix("/")}"
            val result = resourceResolver.resolveResource(
                requestUrlStr = resourceUrl,
                initiatorUrlStr = url,
                isPrivate = isPrivate
            )
            
            val extLower = relativePath.substringAfterLast(".").lowercase()
            if (extLower != "js" && extLower != "mjs" && extLower != "css") {
                reportError(ContentScriptErrorReport(
                    extensionId = extensionId,
                    scriptId = scriptId,
                    errorType = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND",
                    errorMsg = "Resource is not JS/CSS: $relativePath",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = webViewHash,
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "file_resolution"
                ))
                return ""
            }

            val content = result.inputStreamProvider?.invoke()?.bufferedReader()?.use { it.readText() } ?: ""
            if (content.isBlank()) {
                reportError(ContentScriptErrorReport(
                    extensionId = extensionId,
                    scriptId = scriptId,
                    errorType = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND",
                    errorMsg = "Resolved resource is empty: $relativePath",
                    timestamp = System.currentTimeMillis(),
                    webViewInstanceId = webViewHash,
                    uri = url ?: "",
                    targetFrameId = frameId,
                    failedStep = "file_resolution"
                ))
                return ""
            }
            if (url == null) {
                persistentResourceCache[cacheKey] = content
            }
            return content
        } catch (e: com.swift.browser.extensionengine.ExtensionError.SecurityError.ExtensionNotFound) {
            reportError(ContentScriptErrorReport(
                extensionId = extensionId,
                scriptId = scriptId,
                errorType = "CONTENT_SCRIPT_DISABLED",
                errorMsg = e.message ?: "Extension not found",
                timestamp = System.currentTimeMillis(),
                webViewInstanceId = webViewHash,
                uri = url ?: "",
                targetFrameId = frameId,
                failedStep = "file_resolution"
            ))
        } catch (e: com.swift.browser.extensionengine.ExtensionError.SecurityError.AccessDenied) {
            val errType = if (isPrivate) "CONTENT_SCRIPT_PRIVATE_BLOCKED" else "CONTENT_SCRIPT_DISABLED"
            reportError(ContentScriptErrorReport(
                extensionId = extensionId,
                scriptId = scriptId,
                errorType = errType,
                errorMsg = e.message ?: "Access Denied",
                timestamp = System.currentTimeMillis(),
                webViewInstanceId = webViewHash,
                uri = url ?: "",
                targetFrameId = frameId,
                failedStep = "file_resolution"
            ))
        } catch (e: com.swift.browser.extensionengine.ExtensionError.SecurityError.ResourceNotAccessible) {
            reportError(ContentScriptErrorReport(
                extensionId = extensionId,
                scriptId = scriptId,
                errorType = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND",
                errorMsg = e.message ?: "Resource not found on disk",
                timestamp = System.currentTimeMillis(),
                webViewInstanceId = webViewHash,
                uri = url ?: "",
                targetFrameId = frameId,
                failedStep = "file_resolution"
            ))
        } catch (e: com.swift.browser.extensionengine.ExtensionError.SecurityError.PathTraversalDetected) {
            reportError(ContentScriptErrorReport(
                extensionId = extensionId,
                scriptId = scriptId,
                errorType = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND",
                errorMsg = e.message ?: "Path Traversal Detected",
                timestamp = System.currentTimeMillis(),
                webViewInstanceId = webViewHash,
                uri = url ?: "",
                targetFrameId = frameId,
                failedStep = "file_resolution"
            ))
        } catch (e: Exception) {
            reportError(ContentScriptErrorReport(
                extensionId = extensionId,
                scriptId = scriptId,
                errorType = "CONTENT_SCRIPT_RESOURCE_NOT_FOUND",
                errorMsg = e.message ?: "File resolution failed",
                timestamp = System.currentTimeMillis(),
                webViewInstanceId = webViewHash,
                uri = url ?: "",
                targetFrameId = frameId,
                failedStep = "file_resolution"
            ))
        }
        return ""
    }
}
