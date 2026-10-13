package com.swift.browser.extensionengine

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.InputStreamReader

/**
 * Adapter implementing the Chrome declarativeNetRequest (DNR) API.
 * Uses the canonical ExtensionPermissionAdapter and the unified ExtensionDnrRuleStore.
 */
class ExtensionDnrAdapter(
    val permissionAdapter: ExtensionPermissionAdapter,
    val registry: ExtensionRegistry,
    val ruleStore: ExtensionDnrRuleStore = ExtensionDnrRuleStore()
) {
    fun deactivateExtension(extensionId: String) {
        ruleStore.deactivateExtension(extensionId)
    }

    fun reactivateExtension(extensionId: String) {
        ruleStore.reactivateExtension(extensionId)
    }

    private val staticRulesetIndexLock = ReentrantLock()
    private val matchedRulesHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<DnrMatchResult>>()
    private data class DecisionKey(
        val url: String,
        val resourceType: String,
        val method: String,
        val initiator: String?,
        val isPrivate: Boolean,
        val snapshotGeneration: Long
    )
    private val decisionCache = object : java.util.LinkedHashMap<DecisionKey, DnrMatchResult?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DecisionKey, DnrMatchResult?>?): Boolean = size > 2048
    }
    private val decisionCacheLock = Any()

    fun updateDynamicRules(sender: ExtensionSender, options: JSONObject): JSONObject {
        val extId = sender.extensionId
        validateDnrPermission(sender)

        val removeIds = mutableSetOf<Int>()
        val removeArray = options.optJSONArray("removeRuleIds")
        if (removeArray != null) {
            for (i in 0 until removeArray.length()) {
                removeIds.add(removeArray.getInt(i))
            }
        }

        val addList = mutableListOf<DnrRule>()
        val addArray = options.optJSONArray("addRules")
        if (addArray != null) {
            for (i in 0 until addArray.length()) {
                val ruleObj = addArray.getJSONObject(i)
                addList.add(parseRule(ruleObj))
            }
        }

        ruleStore.updateDynamicRules(extId, removeIds, addList)
        clearDecisionCache()
        return JSONObject().put("status", "success")
    }

    fun getDynamicRules(sender: ExtensionSender): JSONArray {
        validateDnrPermission(sender)
        val rules = ruleStore.getDynamicRules(sender.extensionId)
        val array = JSONArray()
        rules.forEach { array.put(ruleToJson(it)) }
        return array
    }

    fun updateSessionRules(sender: ExtensionSender, options: JSONObject): JSONObject {
        val extId = sender.extensionId
        validateDnrPermission(sender)

        val removeIds = mutableSetOf<Int>()
        val removeArray = options.optJSONArray("removeRuleIds")
        if (removeArray != null) {
            for (i in 0 until removeArray.length()) {
                removeIds.add(removeArray.getInt(i))
            }
        }

        val addList = mutableListOf<DnrRule>()
        val addArray = options.optJSONArray("addRules")
        if (addArray != null) {
            for (i in 0 until addArray.length()) {
                val ruleObj = addArray.getJSONObject(i)
                addList.add(parseRule(ruleObj))
            }
        }

        ruleStore.updateSessionRules(extId, removeIds, addList)
        clearDecisionCache()
        return JSONObject().put("status", "success")
    }

    fun getSessionRules(sender: ExtensionSender): JSONArray {
        validateDnrPermission(sender)
        val rules = ruleStore.getSessionRules(sender.extensionId)
        val array = JSONArray()
        rules.forEach { array.put(ruleToJson(it)) }
        return array
    }

    fun getMatchedRules(sender: ExtensionSender, options: JSONObject? = null): JSONObject {
        validateDnrPermission(sender)
        val extId = sender.extensionId
        val list = matchedRulesHistory[extId] ?: emptyList()
        val rulesArr = JSONArray()
        for (match in list) {
            rulesArr.put(JSONObject().apply {
                put("ruleId", match.matchedRuleId)
                put("timeStamp", match.timeStamp.toDouble())
            })
        }
        return JSONObject().apply {
            put("rulesMatchedInfo", rulesArr)
        }
    }

    fun isRegexSupported(sender: ExtensionSender, regexOptions: JSONObject): JSONObject {
        validateDnrPermission(sender)
        val regex = regexOptions.optString("regex", "")
        val isCaseSensitive = regexOptions.optBoolean("isCaseSensitive", false)
        return try {
            if (regex.length > 500) {
                JSONObject().put("isSupported", false).put("reason", "regex_too_long")
            } else {
                val nestedQuantifier = Regex("(\\([^)]+\\)[*+?])[*+?]")
                if (nestedQuantifier.containsMatchIn(regex) || regex.contains("*+") || regex.contains("++")) {
                    JSONObject().put("isSupported", false).put("reason", "catastrophic_backtracking")
                } else {
                    Regex(regex, if (isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                    JSONObject().put("isSupported", true)
                }
            }
        } catch (e: Exception) {
            JSONObject().put("isSupported", false).put("reason", "syntax_error")
        }
    }

    fun setExtensionActionOptions(sender: ExtensionSender, options: JSONObject): JSONObject {
        validateDnrPermission(sender)
        return JSONObject().put("status", "success")
    }

    fun getEnabledRulesets(sender: ExtensionSender): JSONArray {
        validateDnrPermission(sender)
        val extId = sender.extensionId
        val ext = registry.getExtension(extId)
        val dynamicEnabled = ruleStore.getEnabledRulesetIds(extId)
        val array = JSONArray()
        if (ruleStore.hasEnabledRulesetOverride(extId)) {
            dynamicEnabled.forEach { array.put(it) }
        } else {
            val rulesets = ext?.declarativeNetRequest?.rulesets ?: emptyList()
            rulesets.filter { it.enabled }.forEach { array.put(it.id) }
        }
        return array
    }

    fun updateEnabledRulesets(sender: ExtensionSender, options: JSONObject): JSONObject {
        validateDnrPermission(sender)
        val extId = sender.extensionId
        val enableIds = mutableSetOf<String>()
        val disableIds = mutableSetOf<String>()

        val enableArr = options.optJSONArray("enableRulesetIds")
        if (enableArr != null) {
            for (i in 0 until enableArr.length()) enableIds.add(enableArr.getString(i))
        }
        val disableArr = options.optJSONArray("disableRulesetIds")
        if (disableArr != null) {
            for (i in 0 until disableArr.length()) disableIds.add(disableArr.getString(i))
        }

        ruleStore.updateEnabledRulesets(extId, enableIds, disableIds)
        clearDecisionCache()
        return JSONObject().put("status", "success")
    }

    fun testMatchOutcome(sender: ExtensionSender, options: JSONObject): JSONObject {
        validateDnrPermission(sender)
        val url = options.optString("url", "")
        val type = options.optString("type", "main_frame")
        val method = options.optString("method", "GET")
        val match = evaluateRequest(url = url, resourceType = type, method = method, isPrivate = sender.isPrivate)
        return JSONObject().apply {
            if (match != null) {
                put("matchedRule", JSONObject().apply {
                    put("ruleId", match.matchedRuleId)
                    put("rulesetId", when (match.source) {
                        DnrRuleSource.DYNAMIC -> "_dynamic"
                        DnrRuleSource.SESSION -> "_session"
                        DnrRuleSource.STATIC -> "_static"
                    })
                })
            }
        }
    }

    fun registerStaticRuleset(extensionId: String, rulesetId: String, enabledByDefault: Boolean, rules: List<DnrRule>) {
        ruleStore.registerStaticRuleset(extensionId, rulesetId, enabledByDefault, rules)
        clearDecisionCache()
    }

    /**
     * Removes all in-memory and persisted DNR state for an extension.
     * Must be called from disable/uninstall cleanup so a disabled extension
     * cannot leave stale rules or cached decisions behind.
     */
    fun clearExtension(extensionId: String) {
        ruleStore.clearExtension(extensionId)
        matchedRulesHistory.remove(extensionId.lowercase().trim())
        clearDecisionCache()
    }

    private fun clearDecisionCache() {
        synchronized(decisionCacheLock) { decisionCache.clear() }
    }

    fun evaluateRequest(
        url: String,
        resourceType: String,
        requestHeaders: Map<String, String> = emptyMap(),
        isPrivate: Boolean = false,
        initiator: String? = null,
        method: String = "GET"
    ): DnrMatchResult? {
        val snapshots = ruleStore.getAllSnapshots()
        if (snapshots.isEmpty()) return null

        val generation = snapshots.fold(17L) { acc, s -> 31L * acc + s.generationId }
        val key = DecisionKey(url, resourceType, method.uppercase(), initiator, isPrivate, generation)
        synchronized(decisionCacheLock) {
            if (decisionCache.containsKey(key)) return decisionCache[key]
        }

        val candidateMatches = mutableListOf<MatchedCandidate>()

        val host = try {
            val uri = Uri.parse(url)
            uri.host?.lowercase() ?: ""
        } catch (e: Exception) { "" }

        val initHost = if (initiator != null) {
            try {
                val uri = Uri.parse(initiator)
                uri.host?.lowercase() ?: ""
            } catch (e: Exception) { "" }
        } else null

        for (snapshot in snapshots) {
            val extId = snapshot.extensionId
            if (!registry.isExtensionEnabled(extId)) continue
            if (isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) continue

            // Canonical Permission Check via ExtensionPermissionAdapter ONLY
            val hasDnrPermission = permissionAdapter.hasApiPermission(extId, "declarativeNetRequest", isPrivate) ||
                    permissionAdapter.hasApiPermission(extId, "declarativeNetRequestWithHostAccess", isPrivate) ||
                    permissionAdapter.hasApiPermission(extId, "declarativeNetRequestFeedback", isPrivate)

            if (!hasDnrPermission) continue

            // Keep candidate evaluation bounded: global rules plus only domain buckets that can match.
            // De-duplicate rules because domain-indexed rules are not necessarily absent from other buckets.
            val candidates = LinkedHashMap<String, CompiledDnrRule>(snapshot.globalRules.size + 32)
            snapshot.globalRules.forEach { compiled ->
                candidates.putIfAbsent("${compiled.source.name}:${compiled.rule.id}", compiled)
            }
            if (host.isNotEmpty()) {
                var currentDomain = host
                while (currentDomain.isNotEmpty()) {
                    snapshot.domainIndexedRules[currentDomain]?.forEach { compiled ->
                        candidates.putIfAbsent("${compiled.source.name}:${compiled.rule.id}", compiled)
                    }
                    val dotIdx = currentDomain.indexOf('.')
                    if (dotIdx == -1) break
                    currentDomain = currentDomain.substring(dotIdx + 1)
                }
            }

            for (compiled in candidates.values) {
                if (matchesCompiledRule(compiled, url, host, resourceType, method, initiator, initHost)) {
                    val actionType = compiled.rule.action.type.lowercase()
                    // DNR is host-access controlled: both the requested URL and a non-empty initiator
                    // must be visible to the extension before a rule can affect the request.
                    if (!permissionAdapter.hasHostPermission(extId, url, isPrivate)) continue
                    if (!initiator.isNullOrBlank() && !permissionAdapter.hasHostPermission(extId, initiator, isPrivate)) continue
                    candidateMatches.add(
                        MatchedCandidate(
                            extensionId = extId,
                            rule = compiled.rule,
                            source = compiled.source,
                            priority = compiled.rule.priority
                        )
                    )
                }
            }
        }

        if (candidateMatches.isEmpty()) {
            synchronized(decisionCacheLock) { decisionCache[key] = null }
            return null
        }

        // Chromium resolves requests in two distinct stages: within each extension, pick the
        // highest-priority matching rule (ties: allow > allowAllRequests > block > upgrade > redirect).
        // Across extensions, request-modifying actions are ranked block > redirect/upgrade > allow,
        // with the most recently installed extension winning same-rank ties. modifyHeaders are handled
        // separately after this stage. See Chromium RulesetManager rule-matching contract.
        val inExtensionActionPreference = mapOf(
            "allow" to 5,
            "allowallrequests" to 4,
            "block" to 3,
            "upgradescheme" to 2,
            "redirect" to 1
        )
        val perExtension = candidateMatches
            .asSequence()
            .filter { !it.rule.action.type.equals("modifyHeaders", ignoreCase = true) }
            .groupBy { it.extensionId }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<MatchedCandidate> { it.priority }
                        .thenBy { inExtensionActionPreference[it.rule.action.type.lowercase()] ?: 0 }
                )
            }
            .toList()

        val extensionActionRank = mapOf(
            "block" to 3,
            "upgradescheme" to 2,
            "redirect" to 2,
            "allow" to 1,
            "allowallrequests" to 1
        )

        fun installTimestamp(extensionId: String): Long =
            registry.getRegisteredInfo(extensionId)?.installedTimestamp ?: Long.MIN_VALUE

        val best = perExtension.maxWithOrNull(
            compareBy<MatchedCandidate> { extensionActionRank[it.rule.action.type.lowercase()] ?: 0 }
                .thenBy { installTimestamp(it.extensionId) }
        ) ?: candidateMatches
            .filter { it.rule.action.type.equals("modifyHeaders", ignoreCase = true) }
            .maxWithOrNull(
                compareBy<MatchedCandidate> { it.priority }
                    .thenBy { installTimestamp(it.extensionId) }
            ) ?: return null

        val resolvedRedirectUrl = if (best.rule.action.type.equals("upgradeScheme", ignoreCase = true)) {
            if (url.startsWith("http://", ignoreCase = true)) {
                "https://" + url.substring(7)
            } else url
        } else {
            best.rule.action.redirectUrl
        }

        val result = DnrMatchResult(
            matchedRuleId = best.rule.id,
            extensionId = best.extensionId,
            actionType = best.rule.action.type,
            source = best.source,
            redirectUrl = resolvedRedirectUrl,
            requestHeaders = best.rule.action.requestHeaders,
            responseHeaders = best.rule.action.responseHeaders
        )

        // Record matched rule history for declarativeNetRequestFeedback
        val history = matchedRulesHistory.getOrPut(best.extensionId) { CopyOnWriteArrayList() }
        if (history.size > 200) history.removeAt(0)
        history.add(result)

        synchronized(decisionCacheLock) { decisionCache[key] = result }
        return result
    }

    private fun matchesCompiledRule(
        compiled: CompiledDnrRule,
        url: String,
        host: String,
        resourceType: String,
        method: String,
        initiator: String?,
        initHost: String?
    ): Boolean {
        val cond = compiled.rule.condition

        // Resource types
        val lowerResType = resourceType.lowercase()
        if (compiled.resourceTypeSet.isNotEmpty() && lowerResType !in compiled.resourceTypeSet) return false
        if (compiled.excludedResourceTypeSet.isNotEmpty() && lowerResType in compiled.excludedResourceTypeSet) return false

        // Request methods
        val upperMethod = method.uppercase()
        if (compiled.requestMethodSet.isNotEmpty() && upperMethod !in compiled.requestMethodSet) return false
        if (compiled.excludedRequestMethodSet.isNotEmpty() && upperMethod in compiled.excludedRequestMethodSet) return false

        // Request domains with strict boundary check
        if (compiled.cleanDomains.isNotEmpty()) {
            val match = compiled.cleanDomains.any { d -> host == d || host.endsWith(".$d") }
            if (!match) return false
        }
        if (compiled.cleanExcludedDomains.isNotEmpty()) {
            val exc = compiled.cleanExcludedDomains.any { d -> host == d || host.endsWith(".$d") }
            if (exc) return false
        }

        // Initiator domains with strict boundary check
        if (initHost != null) {
            if (compiled.cleanInitiatorDomains.isNotEmpty()) {
                val match = compiled.cleanInitiatorDomains.any { d -> initHost == d || initHost.endsWith(".$d") }
                if (!match) return false
            }
            if (compiled.cleanExcludedInitiatorDomains.isNotEmpty()) {
                val exc = compiled.cleanExcludedInitiatorDomains.any { d -> initHost == d || initHost.endsWith(".$d") }
                if (exc) return false
            }

            // Domain type (firstParty / thirdParty)
            if (cond.domainType != null && host.isNotEmpty() && initHost.isNotEmpty()) {
                val isFirstParty = host == initHost || host.endsWith(".$initHost") || initHost.endsWith(".$host")
                if (cond.domainType.equals("firstParty", ignoreCase = true) && !isFirstParty) return false
                if (cond.domainType.equals("thirdParty", ignoreCase = true) && isFirstParty) return false
            }
        }

        // URL filter matching
        if (compiled.compiledUrlFilterPattern != null) {
            if (!compiled.compiledUrlFilterPattern.matcher(url).find()) {
                return false
            }
        } else if (cond.urlFilter != null) {
            val targetUrl = if (cond.isUrlFilterCaseSensitive) url else url.lowercase()
            val targetFilter = if (cond.isUrlFilterCaseSensitive) cond.urlFilter else cond.urlFilter.lowercase()
            if (!targetUrl.contains(targetFilter)) return false
        }

        // Regex filter matching
        if (compiled.compiledRegex != null) {
            if (!compiled.compiledRegex.matcher(url).find()) {
                return false
            }
        }

        return true
    }

    private data class MatchedCandidate(
        val extensionId: String,
        val rule: DnrRule,
        val source: DnrRuleSource,
        val priority: Int
    )

    private fun validateDnrPermission(sender: ExtensionSender) {
        val extId = sender.extensionId
        if (!registry.isExtensionEnabled(extId)) {
            throw SecurityException("Extension is disabled: $extId")
        }
        if (sender.isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) {
            throw SecurityException("SecurityError: Extension not allowed in private mode")
        }
        val hasPerm = permissionAdapter.hasApiPermission(extId, "declarativeNetRequest", sender.isPrivate) ||
                permissionAdapter.hasApiPermission(extId, "declarativeNetRequestWithHostAccess", sender.isPrivate) ||
                permissionAdapter.hasApiPermission(extId, "declarativeNetRequestFeedback", sender.isPrivate)

        if (!hasPerm) {
            throw SecurityException("SecurityError: Missing declarativeNetRequest permission")
        }
    }

    fun loadStaticRulesets(
        extensionId: String,
        rulesets: List<DeclarativeNetRequestRuleset>,
        extensionDir: java.io.File,
        expectedRegistryGeneration: Int? = null
    ) {
        if (rulesets.isEmpty() || !extensionDir.exists()) return

        staticRulesetIndexLock.withLock {
            if (!isCurrentRegistryGeneration(extensionId, expectedRegistryGeneration)) {
                android.util.Log.i(
                    "ExtensionDnrAdapter",
                    "DNR_STATIC_INDEX_STALE_SKIP id=$extensionId generation=$expectedRegistryGeneration"
                )
                return@withLock
            }

            val allRulesets = mutableMapOf<String, Pair<Boolean, List<DnrRule>>>()
            for (ruleset in rulesets) {
                try {
                    val file = ExtensionDirectoryResolver.findFileCaseInsensitive(extensionDir, ruleset.path)
                    if (file == null || !file.exists() || !file.isFile) continue

                    val fileBytes = file.length()
                    android.util.Log.i(
                        "ExtensionDnrAdapter",
                        "DNR_STATIC_INDEX_BEGIN id=$extensionId ruleset=${ruleset.id} bytes=$fileBytes"
                    )

                    // A large ruleset is a memory-amplifier because Kotlin's parsed rule model is
                    // retained for matching. Refuse only when the current process is already under
                    // severe pressure; ordinary large lists still use the streaming parser below.
                    if (isUnderSevereMemoryPressure(fileBytes)) {
                        android.util.Log.w(
                            "ExtensionDnrAdapter",
                            "DNR_STATIC_INDEX_DEFERRED id=$extensionId ruleset=${ruleset.id} bytes=$fileBytes reason=memory_pressure"
                        )
                        continue
                    }

                    val rulesList = readRuleArrayStreaming(file)
                    allRulesets[ruleset.id] = ruleset.enabled to rulesList
                    android.util.Log.i(
                        "ExtensionDnrAdapter",
                        "DNR_STATIC_INDEX_PARSED id=$extensionId ruleset=${ruleset.id} rules=${rulesList.size}"
                    )
                } catch (e: Throwable) {
                    // Parsing failures must not tear down the browser process or invalidate the
                    // already-committed extension package. Chrome treats invalid packed static
                    // rules as an index/load error rather than an installation crash.
                    android.util.Log.w(
                        "ExtensionDnrAdapter",
                        "Failed to load static ruleset ${ruleset.id} for $extensionId",
                        e
                    )
                }
            }
            if (allRulesets.isNotEmpty()) {
                if (!isCurrentRegistryGeneration(extensionId, expectedRegistryGeneration)) {
                    android.util.Log.i(
                        "ExtensionDnrAdapter",
                        "DNR_STATIC_INDEX_STALE_DROP id=$extensionId generation=$expectedRegistryGeneration"
                    )
                    return@withLock
                }
                ruleStore.registerStaticRulesets(extensionId, allRulesets)
                clearDecisionCache()
            }
        }
    }

    private fun isCurrentRegistryGeneration(extensionId: String, expectedGeneration: Int?): Boolean {
        if (expectedGeneration == null) return true
        return registry.getExtensionGeneration(extensionId) == expectedGeneration &&
            registry.getExtension(extensionId) != null
    }

    private fun isUnderSevereMemoryPressure(fileBytes: Long): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            // Keep roughly 2x the source file size available before constructing the retained
            // Kotlin rule graph. This is a guard against process death, not a hard ruleset limit.
            fileBytes >= 32L * 1024L * 1024L && available < fileBytes * 2L
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Streams a static JSON array one rule object at a time. Avoids JSONArray(readText()) which
     * retained the entire source string + JSON tree before the rule model was constructed.
     */
    private fun readRuleArrayStreaming(file: java.io.File): List<DnrRule> {
        val rules = ArrayList<DnrRule>()
        file.inputStream().buffered(64 * 1024).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                    throw IllegalArgumentException("DNR ruleset must be a JSON array: ${file.name}")
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    try {
                        val value = readJsonValue(reader)
                        val obj = value as? JSONObject ?: continue
                        runCatching { rules.add(parseRule(obj)) }
                    } catch (_: Exception) {
                        // Skip a malformed individual static rule; retain the rest of the packed ruleset.
                    }
                }
                reader.endArray()
            }
        }
        return rules
    }

    private fun readJsonValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val obj = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val value = readJsonValue(reader)
                    obj.put(name, value ?: JSONObject.NULL)
                }
                reader.endObject()
                obj
            }
            JsonToken.BEGIN_ARRAY -> {
                val array = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) {
                    val value = readJsonValue(reader)
                    array.put(value ?: JSONObject.NULL)
                }
                reader.endArray()
                array
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> throw IllegalArgumentException("Unexpected JSON token ${reader.peek()}")
        }
    }

    internal fun parseRule(obj: JSONObject): DnrRule {
        val id = obj.getInt("id")
        val priority = obj.optInt("priority", 1)
        val actionObj = obj.getJSONObject("action")
        val actionType = actionObj.getString("type")
        val redirectUrl = if (actionObj.has("redirectUrl")) actionObj.getString("redirectUrl") else null

        val reqHeadersList = mutableListOf<DnrHeader>()
        val reqHeadersArray = actionObj.optJSONArray("requestHeaders")
        if (reqHeadersArray != null) {
            for (i in 0 until reqHeadersArray.length()) {
                val h = reqHeadersArray.getJSONObject(i)
                reqHeadersList.add(
                    DnrHeader(
                        header = h.getString("header"),
                        operation = h.optString("operation", "set"),
                        value = if (h.has("value")) h.getString("value") else null
                    )
                )
            }
        }

        val resHeadersList = mutableListOf<DnrHeader>()
        val resHeadersArray = actionObj.optJSONArray("responseHeaders")
        if (resHeadersArray != null) {
            for (i in 0 until resHeadersArray.length()) {
                val h = resHeadersArray.getJSONObject(i)
                resHeadersList.add(
                    DnrHeader(
                        header = h.getString("header"),
                        operation = h.optString("operation", "set"),
                        value = if (h.has("value")) h.getString("value") else null
                    )
                )
            }
        }

        val action = DnrAction(actionType, redirectUrl, reqHeadersList, resHeadersList)

        val condObj = obj.optJSONObject("condition") ?: JSONObject()
        val urlFilter = if (condObj.has("urlFilter")) condObj.getString("urlFilter") else null
        val regexFilter = if (condObj.has("regexFilter")) condObj.getString("regexFilter") else null
        val caseSensitive = condObj.optBoolean("isUrlFilterCaseSensitive", false)

        val resTypes = parseStringList(condObj.optJSONArray("resourceTypes"))
        val excResTypes = parseStringList(condObj.optJSONArray("excludedResourceTypes"))
        val domains = parseStringList(condObj.optJSONArray("domains"))
        val excDomains = parseStringList(condObj.optJSONArray("excludedDomains"))
        val reqDomains = parseStringList(condObj.optJSONArray("requestDomains"))
        val excReqDomains = parseStringList(condObj.optJSONArray("excludedRequestDomains"))
        val initDomains = parseStringList(condObj.optJSONArray("initiatorDomains"))
        val excInitDomains = parseStringList(condObj.optJSONArray("excludedInitiatorDomains"))
        val reqMethods = parseStringList(condObj.optJSONArray("requestMethods"))
        val excReqMethods = parseStringList(condObj.optJSONArray("excludedRequestMethods"))
        val domainType = if (condObj.has("domainType")) condObj.getString("domainType") else null

        val condition = DnrCondition(
            urlFilter = urlFilter,
            regexFilter = regexFilter,
            isUrlFilterCaseSensitive = caseSensitive,
            resourceTypes = resTypes,
            excludedResourceTypes = excResTypes,
            domains = domains.ifEmpty { reqDomains },
            excludedDomains = excDomains.ifEmpty { excReqDomains },
            requestDomains = reqDomains,
            excludedRequestDomains = excReqDomains,
            initiatorDomains = initDomains,
            excludedInitiatorDomains = excInitDomains,
            requestMethods = reqMethods,
            excludedRequestMethods = excReqMethods,
            domainType = domainType
        )

        return DnrRule(id, priority, action, condition)
    }

    private fun parseStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    private fun ruleToJson(rule: DnrRule): JSONObject {
        val actionObj = JSONObject().apply {
            put("type", rule.action.type)
            if (rule.action.redirectUrl != null) put("redirectUrl", rule.action.redirectUrl)
            if (rule.action.requestHeaders.isNotEmpty()) {
                val arr = JSONArray()
                rule.action.requestHeaders.forEach { h ->
                    arr.put(JSONObject().apply {
                        put("header", h.header)
                        put("operation", h.operation)
                        if (h.value != null) put("value", h.value)
                    })
                }
                put("requestHeaders", arr)
            }
            if (rule.action.responseHeaders.isNotEmpty()) {
                val arr = JSONArray()
                rule.action.responseHeaders.forEach { h ->
                    arr.put(JSONObject().apply {
                        put("header", h.header)
                        put("operation", h.operation)
                        if (h.value != null) put("value", h.value)
                    })
                }
                put("responseHeaders", arr)
            }
        }
        val condObj = JSONObject().apply {
            if (rule.condition.urlFilter != null) put("urlFilter", rule.condition.urlFilter)
            if (rule.condition.regexFilter != null) put("regexFilter", rule.condition.regexFilter)
            put("isUrlFilterCaseSensitive", rule.condition.isUrlFilterCaseSensitive)
            if (rule.condition.resourceTypes.isNotEmpty()) put("resourceTypes", JSONArray(rule.condition.resourceTypes))
            if (rule.condition.excludedResourceTypes.isNotEmpty()) put("excludedResourceTypes", JSONArray(rule.condition.excludedResourceTypes))
            if (rule.condition.domains.isNotEmpty()) put("domains", JSONArray(rule.condition.domains))
            if (rule.condition.excludedDomains.isNotEmpty()) put("excludedDomains", JSONArray(rule.condition.excludedDomains))
            if (rule.condition.requestDomains.isNotEmpty()) put("requestDomains", JSONArray(rule.condition.requestDomains))
            if (rule.condition.excludedRequestDomains.isNotEmpty()) put("excludedRequestDomains", JSONArray(rule.condition.excludedRequestDomains))
            if (rule.condition.initiatorDomains.isNotEmpty()) put("initiatorDomains", JSONArray(rule.condition.initiatorDomains))
            if (rule.condition.excludedInitiatorDomains.isNotEmpty()) put("excludedInitiatorDomains", JSONArray(rule.condition.excludedInitiatorDomains))
            if (rule.condition.requestMethods.isNotEmpty()) put("requestMethods", JSONArray(rule.condition.requestMethods))
            if (rule.condition.excludedRequestMethods.isNotEmpty()) put("excludedRequestMethods", JSONArray(rule.condition.excludedRequestMethods))
            if (rule.condition.domainType != null) put("domainType", rule.condition.domainType)
        }
        return JSONObject().apply {
            put("id", rule.id)
            put("priority", rule.priority)
            put("action", actionObj)
            put("condition", condObj)
        }
    }
}
