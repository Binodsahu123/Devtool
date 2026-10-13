package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import java.util.HashSet

data class ContentScriptSpec(
    val matches: List<String>,
    val js: List<String>,
    val css: List<String>,
    val runAt: String = "document_idle",
    val allFrames: Boolean = false,
    val matchAboutBlank: Boolean = false,
    val excludeMatches: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val matchOriginAsFallback: Boolean = false,
    val world: String = "ISOLATED"
) {
    fun matchesUrl(urlStr: String, targetOrigin: String? = null): Boolean =
        ExtensionGlobMatcher.matchesUrl(
            urlStr = urlStr,
            targetOrigin = targetOrigin,
            matches = matches,
            excludeMatches = excludeMatches,
            includeGlobs = includeGlobs,
            excludeGlobs = excludeGlobs,
            matchAboutBlank = matchAboutBlank,
            matchOriginAsFallback = matchOriginAsFallback
        )
}

data class ActionSpec(
    val defaultPopup: String = "",
    val defaultTitle: String = "",
    val defaultIconMap: Map<String, String> = emptyMap(),
    val hasAction: Boolean = false,
    val actionType: String = ""
)

data class BackgroundSpec(
    val scripts: List<String> = emptyList(),
    val page: String = "",
    val serviceWorker: String = "",
    val type: String = "",
    val persistent: Boolean = true
)

data class WebAccessibleResourceSpec(
    val resources: List<String> = emptyList(),
    val matches: List<String> = emptyList(),
    val extensionIds: List<String> = emptyList(),
    val useDynamicUrl: Boolean = false
)

data class ContentSecurityPolicySpec(
    val extensionPages: String = "",
    val sandbox: String = ""
)

data class DeclarativeNetRequestRuleset(
    val id: String,
    val path: String,
    val enabled: Boolean
)

data class DeclarativeNetRequestSpec(
    val rulesets: List<DeclarativeNetRequestRuleset> = emptyList()
)

data class ParsedExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val manifestVersion: Int = 3,
    val permissions: List<String> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
    val backgroundScripts: List<String> = emptyList(),
    val isServiceWorker: Boolean = false,
    val contentScripts: List<ContentScriptSpec> = emptyList(),
    val actionPopup: String = "",
    val optionsPage: String = "",
    val manifestJson: String = "{}",
    val shortName: String = "",
    val iconPath: String = "",
    val installPath: String = "",
    val popupPath: String = "",
    val manifestPath: String = "",
    val backgroundPath: String = "",
    val isEnabled: Boolean = true,
    val allowedInPrivate: Boolean = false,
    val identity: ExtensionIdentity = ExtensionIdentity(id, IdentityType.ORION_LOCAL_IDENTITY),
    val key: String? = null,
    val defaultLocale: String = "",
    val minimumChromeVersion: String = "",
    val actionSpec: ActionSpec = ActionSpec(),
    val backgroundSpec: BackgroundSpec = BackgroundSpec(),
    val webAccessibleResources: List<WebAccessibleResourceSpec> = emptyList(),
    val contentSecurityPolicy: ContentSecurityPolicySpec = ContentSecurityPolicySpec(),
    val optionalPermissions: List<String> = emptyList(),
    val optionalHostPermissions: List<String> = emptyList(),
    val externallyConnectable: ExternallyConnectableSpec = ExternallyConnectableSpec(),
    val sidePanelPath: String = "",
    val devtoolsPagePath: String = "",
    val optionsInTab: Boolean = false,
    val urlOverrides: Map<String, String> = emptyMap(),
    val declarativeNetRequest: DeclarativeNetRequestSpec = DeclarativeNetRequestSpec()
) {
    @Deprecated("Use allowedInPrivate instead", ReplaceWith("allowedInPrivate"))
    val allowedInIncognito: Boolean get() = allowedInPrivate
}

sealed class ManifestParseResult {
    data class Success(val parsedExtension: ParsedExtension) : ManifestParseResult()
    data class Failure(val error: ExtensionError.ManifestError) : ManifestParseResult()
}

class ManifestParser {

    /**
     * Parses manifest JSON strictly. Throws [ExtensionError.ManifestError] on validation failure.
     */
    fun parse(manifestJsonStr: String, sourceSeed: String = "local_install"): ParsedExtension {
        return when (val result = validateAndParse(manifestJsonStr, sourceSeed)) {
            is ManifestParseResult.Success -> result.parsedExtension
            is ManifestParseResult.Failure -> throw result.error
        }
    }

    /**
     * Validates and parses manifest JSON, returning a structured [ManifestParseResult].
     */
    fun validateAndParse(manifestJsonStr: String, sourceSeed: String = "local_install"): ManifestParseResult {
        if (manifestJsonStr.isBlank()) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidJson(manifestJsonStr, IllegalArgumentException("Manifest content is blank"))
            )
        }

        val root = try {
            try { StrictManifestJsonValidator.validate(manifestJsonStr) } catch (_: Throwable) {}
            JSONObject(manifestJsonStr)
        } catch (e: Exception) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.InvalidJson(manifestJsonStr, e))
        }

        // 1. Validate manifest_version
        if (!root.has("manifest_version")) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.MissingRequiredField("manifest_version"))
        }

        val manifestVersion = try {
            val rawManifestVersion = root.opt("manifest_version")
            if (rawManifestVersion !is Int) {
                root.optString("manifest_version", "3").toIntOrNull() ?: 3
            } else rawManifestVersion
        } catch (e: Exception) {
            3
        }

        if (manifestVersion != 2 && manifestVersion != 3) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.UnsupportedVersion(manifestVersion))
        }

        // 2. Validate name
        if (!root.has("name")) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.MissingRequiredField("name"))
        }
        val nameRaw = root.optString("name", "").trim()
        if (nameRaw.isBlank()) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.MissingRequiredField("name"))
        }
        val name = if (nameRaw.length > 75) nameRaw.take(75) else nameRaw
        val sanitizedName = name

        // 3. Validate version
        if (!root.has("version")) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.MissingRequiredField("version"))
        }
        val versionRaw = root.optString("version", "").trim()
        val version = if (versionRaw.isBlank()) "1.0.0" else versionRaw

        val rawShortName = root.optString("short_name", "").trim()
        val shortName = if (rawShortName.length > 12) rawShortName.take(12) else rawShortName
        val rawDescription = root.optString("description", "").trim()
        val description = if (rawDescription.length > 132) rawDescription.take(132) else rawDescription
        val defaultLocale = root.optString("default_locale", "").trim()
        val minimumChromeVersion = root.optString("minimum_chrome_version", "").trim()
        val rawKey = root.optString("key", "").trim().takeIf { it.isNotBlank() }

        // 4. Stable identity resolution
        val identity = try {
            if (rawKey != null) {
                ExtensionIdGenerator.generateFromPublicKey(rawKey)
            } else {
                ExtensionIdGenerator.generateOrionLocalIdentity(sourceSeed.ifBlank { sanitizedName }, manifestJsonStr)
            }
        } catch (e: Exception) {
            ExtensionIdGenerator.generateOrionLocalIdentity(sourceSeed.ifBlank { sanitizedName }, manifestJsonStr)
        }

        // 5. Parse permissions with resilient parsing
        val permissions: List<String> = parseStringList(root, "permissions")
        val optionalPermissions: List<String> = parseStringList(root, "optional_permissions")
        val rawHostPermissions: List<String> = parseStringList(root, "host_permissions")
        val rawOptionalHostPermissions: List<String> = parseStringList(root, "optional_host_permissions")

        val hostPermissions = rawHostPermissions
        val optionalHostPermissions = rawOptionalHostPermissions

        // 6. Background spec
        val backgroundSpec = parseBackgroundSpec(root, manifestVersion)

        // 7. Action spec. Keep MV2 aliases and MV3 action semantics resilient.
        val actionSpec = parseActionSpec(root)
        validateActionSpec(actionSpec)?.let {
            return ManifestParseResult.Failure(it)
        }

        // 8. Content scripts
        validateContentScriptsJson(root)?.let { return ManifestParseResult.Failure(it) }
        val contentScripts = parseContentScripts(root)
        validateContentScripts(contentScripts)?.let {
            return ManifestParseResult.Failure(it)
        }

        // 9. Options page / UI
        if (root.has("options_page") && root.opt("options_page") !is String) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType("options_page", "String", root.opt("options_page")?.toString() ?: "null")
            )
        }
        if (root.has("options_ui") && root.opt("options_ui") !is JSONObject) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.InvalidFieldType("options_ui", "Object", root.opt("options_ui")?.toString() ?: "null"))
        }
        var optionsPage = ""
        var optionsInTab = false
        if (root.has("options_page")) {
            optionsPage = root.optString("options_page", "").trim()
            optionsInTab = true
        } else if (root.has("options_ui")) {
            val uiObj = root.optJSONObject("options_ui")
            if (uiObj != null) {
                optionsPage = uiObj.optString("page", "").trim()
                optionsInTab = uiObj.optBoolean("open_in_tab", false)
            }
        }

        // 9b. Side panel spec
        if (root.has("side_panel") && root.opt("side_panel") !is JSONObject) {
            return ManifestParseResult.Failure(ExtensionError.ManifestError.InvalidFieldType("side_panel", "Object", root.opt("side_panel")?.toString() ?: "null"))
        }
        val sidePanelPath = when {
            root.has("side_panel") -> {
                val spObj = root.optJSONObject("side_panel")
                spObj?.optString("default_path", "")?.trim() ?: ""
            }
            else -> ""
        }

        // 9c. DevTools page
        if (root.has("devtools_page") && root.opt("devtools_page") !is String) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType("devtools_page", "String", root.opt("devtools_page")?.toString() ?: "null")
            )
        }
        val devtoolsPagePath = root.optString("devtools_page", "").trim()
        if (devtoolsPagePath.isNotBlank() && !isSafeRelativePath(devtoolsPagePath)) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.SecurityPolicyViolation("devtools_page contains an unsafe path")
            )
        }
        if (sidePanelPath.isNotBlank() && !isSafeRelativePath(sidePanelPath)) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.SecurityPolicyViolation("side_panel.default_path contains an unsafe path")
            )
        }

        // 9d. Chrome URL Overrides
        val urlOverrides = mutableMapOf<String, String>()
        val overridesObj = root.optJSONObject("chrome_url_overrides")
        if (overridesObj != null) {
            val keys = overridesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k !in setOf("newtab", "bookmarks", "history")) {
                    return ManifestParseResult.Failure(
                        ExtensionError.ManifestError.InvalidFieldValue("chrome_url_overrides", "unsupported override key '$k'")
                    )
                }
                val v = overridesObj.opt(k)
                if (v !is String) {
                    return ManifestParseResult.Failure(
                        ExtensionError.ManifestError.InvalidFieldType("chrome_url_overrides.$k", "String", v?.toString() ?: "null")
                    )
                }
                val trimmed = v.trim()
                if (trimmed.isNotBlank()) {
                    if (!isSafeRelativePath(trimmed)) {
                        return ManifestParseResult.Failure(
                            ExtensionError.ManifestError.SecurityPolicyViolation("chrome_url_overrides.$k contains an unsafe path")
                        )
                    }
                    urlOverrides[k] = trimmed
                }
            }
        }

        // 10. Declarative Net Request manifest declaration
        if (root.has("declarative_net_request") && root.opt("declarative_net_request") !is JSONObject) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType(
                    "declarative_net_request",
                    "Object",
                    root.opt("declarative_net_request")?.toString() ?: "null"
                )
            )
        }
        val dnrObjForTypeCheck = root.optJSONObject("declarative_net_request")
        if (dnrObjForTypeCheck?.has("rule_resources") == true &&
            dnrObjForTypeCheck.opt("rule_resources") !is JSONArray) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType(
                    "declarative_net_request.rule_resources",
                    "Array<Object>",
                    dnrObjForTypeCheck.opt("rule_resources")?.toString() ?: "null"
                )
            )
        }
        val dnrSpec = parseDeclarativeNetRequest(root, manifestVersion)
        validateDeclarativeNetRequest(dnrSpec)?.let {
            return ManifestParseResult.Failure(it)
        }

        // 11. Web accessible resources
        if (root.has("web_accessible_resources") && root.opt("web_accessible_resources") !is JSONArray) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources", "Array", root.opt("web_accessible_resources")?.toString() ?: "null")
            )
        }
        val webAccessibleResources = parseWebAccessibleResources(root, manifestVersion)
        for ((index, spec) in webAccessibleResources.withIndex()) {
            val unsafePath = spec.resources.firstOrNull { !isSafeRelativePath(it) }
            if (unsafePath != null) {
                return ManifestParseResult.Failure(
                    ExtensionError.ManifestError.SecurityPolicyViolation("web_accessible_resources[$index] contains an unsafe resource path '$unsafePath'")
                )
            }
            val invalidMatch = spec.matches.firstOrNull { !isValidMatchPattern(it) && it != "<all_urls>" }
            if (invalidMatch != null) {
                return ManifestParseResult.Failure(
                    ExtensionError.ManifestError.InvalidFieldValue(
                        "web_accessible_resources[$index].matches",
                        "contains invalid match pattern '$invalidMatch'"
                    )
                )
            }
        }

        // 11. Content Security Policy
        if (root.has("content_security_policy") && root.opt("content_security_policy") !is JSONObject && root.opt("content_security_policy") !is String) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType("content_security_policy", "String or Object", root.opt("content_security_policy")?.toString() ?: "null")
            )
        }
        val cspSpec = parseContentSecurityPolicy(root, manifestVersion)
        validateContentSecurityPolicy(cspSpec, manifestVersion)?.let { return ManifestParseResult.Failure(it) }

        // 12. Externally Connectable
        if (root.has("externally_connectable") && root.opt("externally_connectable") !is JSONObject) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldType("externally_connectable", "Object", root.opt("externally_connectable")?.toString() ?: "null")
            )
        }
        val externallyConnectable = parseExternallyConnectable(root)
        val invalidConnectableMatch = externallyConnectable.matches.firstOrNull { !isValidMatchPattern(it) }
        if (invalidConnectableMatch != null) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldValue(
                    "externally_connectable.matches",
                    "contains invalid match pattern '$invalidConnectableMatch'"
                )
            )
        }
        if (externallyConnectable.ids.any { id -> id != "*" && !id.matches(Regex("^[a-z]{32}$")) }) {
            return ManifestParseResult.Failure(
                ExtensionError.ManifestError.InvalidFieldValue("externally_connectable.ids", "contains an invalid extension ID")
            )
        }

        // 13. Allowed in private
        val allowedInPrivate = try {
            parseIncognitoPolicy(root)
        } catch (e: ExtensionError.ManifestError) {
            return ManifestParseResult.Failure(e)
        }

        val validatedManifest = ValidatedExtensionManifest(
            id = identity.id,
            name = name,
            version = version,
            description = description,
            manifestVersion = manifestVersion,
            permissions = permissions,
            hostPermissions = hostPermissions,
            optionalPermissions = optionalPermissions,
            optionalHostPermissions = optionalHostPermissions,
            backgroundSpec = backgroundSpec,
            actionSpec = actionSpec,
            contentScripts = contentScripts,
            optionsPage = optionsPage,
            webAccessibleResources = webAccessibleResources,
            contentSecurityPolicy = cspSpec,
            externallyConnectable = externallyConnectable,
            key = rawKey,
            defaultLocale = defaultLocale,
            minimumChromeVersion = minimumChromeVersion,
            allowedInPrivate = allowedInPrivate,
            rawJson = manifestJsonStr,
            sidePanelPath = sidePanelPath,
            devtoolsPagePath = devtoolsPagePath,
            optionsInTab = optionsInTab,
            urlOverrides = urlOverrides,
            declarativeNetRequest = dnrSpec
        )

        val parsed = validatedManifest.toParsedExtension(identity)

        return ManifestParseResult.Success(parsed)
    }

    fun validateManifest(manifestJsonStr: String, sourceSeed: String = "local_install"): ValidatedExtensionManifest {
        return when (val result = validateAndParse(manifestJsonStr, sourceSeed)) {
            is ManifestParseResult.Success -> {
                val p = result.parsedExtension
                ValidatedExtensionManifest(
                    id = p.id,
                    name = p.name,
                    version = p.version,
                    description = p.description,
                    manifestVersion = p.manifestVersion,
                    permissions = p.permissions,
                    hostPermissions = p.hostPermissions,
                    optionalPermissions = p.optionalPermissions,
                    optionalHostPermissions = p.optionalHostPermissions,
                    backgroundSpec = p.backgroundSpec,
                    actionSpec = p.actionSpec,
                    contentScripts = p.contentScripts,
                    optionsPage = p.optionsPage,
                    webAccessibleResources = p.webAccessibleResources,
                    contentSecurityPolicy = p.contentSecurityPolicy,
                    externallyConnectable = p.externallyConnectable,
                    key = p.key,
                    defaultLocale = p.defaultLocale,
                    minimumChromeVersion = p.minimumChromeVersion,
                    allowedInPrivate = p.allowedInPrivate,
                    rawJson = p.manifestJson,
                    sidePanelPath = p.sidePanelPath,
                    devtoolsPagePath = p.devtoolsPagePath,
                    optionsInTab = p.optionsInTab,
                    urlOverrides = p.urlOverrides,
                    declarativeNetRequest = p.declarativeNetRequest
                )
            }
            is ManifestParseResult.Failure -> throw result.error
        }
    }

    private fun validateTopLevelNestedTypes(root: JSONObject): ExtensionError.ManifestError? {
        fun invalid(key: String, expected: String, ok: Boolean): ExtensionError.ManifestError? =
            if (root.has(key) && !ok) ExtensionError.ManifestError.InvalidFieldType(key, expected, root.opt(key)?.toString() ?: "null") else null
        invalid("background", "Object", root.opt("background") is JSONObject)?.let { return it }
        invalid("content_scripts", "Array<Object>", root.opt("content_scripts") is JSONArray)?.let { return it }
        invalid("action", "Object", root.opt("action") is JSONObject)?.let { return it }
        invalid("browser_action", "Object", root.opt("browser_action") is JSONObject)?.let { return it }
        invalid("page_action", "Object", root.opt("page_action") is JSONObject)?.let { return it }
        invalid("icons", "Object", root.opt("icons") is JSONObject)?.let { return it }
        invalid("commands", "Object", root.opt("commands") is JSONObject)?.let { return it }
        invalid("chrome_url_overrides", "Object", root.opt("chrome_url_overrides") is JSONObject)?.let { return it }
        invalid("declarative_net_request", "Object", root.opt("declarative_net_request") is JSONObject)?.let { return it }
        invalid("externally_connectable", "Object", root.opt("externally_connectable") is JSONObject)?.let { return it }
        invalid("side_panel", "Object", root.opt("side_panel") is JSONObject)?.let { return it }
        invalid("options_ui", "Object", root.opt("options_ui") is JSONObject)?.let { return it }

        val bg = root.optJSONObject("background")
        if (bg != null) {
            if (bg.has("scripts") && bg.opt("scripts") !is JSONArray) return ExtensionError.ManifestError.InvalidFieldType("background.scripts", "Array<String>", bg.opt("scripts").toString())
            if (bg.has("page") && bg.opt("page") !is String) return ExtensionError.ManifestError.InvalidFieldType("background.page", "String", bg.opt("page").toString())
            if (bg.has("service_worker") && bg.opt("service_worker") !is String) return ExtensionError.ManifestError.InvalidFieldType("background.service_worker", "String", bg.opt("service_worker").toString())
            if (bg.has("type") && bg.opt("type") !is String) return ExtensionError.ManifestError.InvalidFieldType("background.type", "String", bg.opt("type").toString())
            if (bg.has("persistent") && bg.opt("persistent") !is Boolean) return ExtensionError.ManifestError.InvalidFieldType("background.persistent", "Boolean", bg.opt("persistent").toString())
            validateArrayOfStrings(bg, "scripts")?.let { return it }
        }
        for (actionKey in listOf("action", "browser_action", "page_action")) {
            val obj = root.optJSONObject(actionKey) ?: continue
            if (obj.has("default_popup") && obj.opt("default_popup") !is String) return ExtensionError.ManifestError.InvalidFieldType("$actionKey.default_popup", "String", obj.opt("default_popup").toString())
            if (obj.has("default_title") && obj.opt("default_title") !is String) return ExtensionError.ManifestError.InvalidFieldType("$actionKey.default_title", "String", obj.opt("default_title").toString())
            if (obj.has("default_icon") && obj.opt("default_icon") !is String && obj.opt("default_icon") !is JSONObject) return ExtensionError.ManifestError.InvalidFieldType("$actionKey.default_icon", "String or Object", obj.opt("default_icon").toString())
            val iconObj = obj.optJSONObject("default_icon")
            if (iconObj != null) {
                val it = iconObj.keys(); while (it.hasNext()) { val k=it.next(); if (iconObj.opt(k) !is String) return ExtensionError.ManifestError.InvalidFieldType("$actionKey.default_icon.$k", "String", iconObj.opt(k).toString()) }
            }
        }
        val opts = root.optJSONObject("options_ui")
        if (opts != null) {
            if (opts.has("page") && opts.opt("page") !is String) return ExtensionError.ManifestError.InvalidFieldType("options_ui.page", "String", opts.opt("page").toString())
            if (opts.has("open_in_tab") && opts.opt("open_in_tab") !is Boolean) return ExtensionError.ManifestError.InvalidFieldType("options_ui.open_in_tab", "Boolean", opts.opt("open_in_tab").toString())
            if (!opts.has("page") || opts.optString("page", "").isBlank()) return ExtensionError.ManifestError.InvalidFieldValue("options_ui.page", "must not be empty")
        }
        val side = root.optJSONObject("side_panel")
        if (side != null) {
            if (side.has("default_path") && side.opt("default_path") !is String) return ExtensionError.ManifestError.InvalidFieldType("side_panel.default_path", "String", side.opt("default_path").toString())
            if (!side.has("default_path") || side.optString("default_path", "").isBlank()) return ExtensionError.ManifestError.InvalidFieldValue("side_panel.default_path", "must not be empty")
        }
        val ec = root.optJSONObject("externally_connectable")
        if (ec != null) {
            for (k in listOf("matches", "ids")) {
                if (ec.has(k)) {
                    if (ec.opt(k) !is JSONArray) return ExtensionError.ManifestError.InvalidFieldType("externally_connectable.$k", "Array<String>", ec.opt(k).toString())
                    validateArrayOfStrings(ec, k)?.let { return it }
                }
            }
            if (ec.has("accepts_tls_channel_id") && ec.opt("accepts_tls_channel_id") !is Boolean) return ExtensionError.ManifestError.InvalidFieldType("externally_connectable.accepts_tls_channel_id", "Boolean", ec.opt("accepts_tls_channel_id").toString())
        }
        val dnr = root.optJSONObject("declarative_net_request")
        if (dnr != null) {
            if (dnr.has("rule_resources") && dnr.opt("rule_resources") !is JSONArray) return ExtensionError.ManifestError.InvalidFieldType("declarative_net_request.rule_resources", "Array<Object>", dnr.opt("rule_resources").toString())
            val rr = dnr.optJSONArray("rule_resources")
            if (rr != null) for (i in 0 until rr.length()) {
                val o = rr.opt(i) as? JSONObject ?: return ExtensionError.ManifestError.InvalidFieldType("declarative_net_request.rule_resources[$i]", "Object", rr.opt(i).toString())
                if (o.has("id") && o.opt("id") !is String) return ExtensionError.ManifestError.InvalidFieldType("declarative_net_request.rule_resources[$i].id", "String", o.opt("id").toString())
                if (o.has("path") && o.opt("path") !is String) return ExtensionError.ManifestError.InvalidFieldType("declarative_net_request.rule_resources[$i].path", "String", o.opt("path").toString())
                if (o.has("enabled") && o.opt("enabled") !is Boolean) return ExtensionError.ManifestError.InvalidFieldType("declarative_net_request.rule_resources[$i].enabled", "Boolean", o.opt("enabled").toString())
            }
        }
        return null
    }

    private fun validateArrayOfStrings(obj: JSONObject, key: String): ExtensionError.ManifestError? {
        val arr = obj.optJSONArray(key) ?: return null
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v !is String || v.isBlank()) return ExtensionError.ManifestError.InvalidFieldType("$key[$i]", "String", v?.toString() ?: "null")
        }
        return null
    }

    private fun validateIcons(root: JSONObject): ExtensionError.ManifestError? {
        val obj = root.optJSONObject("icons") ?: return null
        val keys = obj.keys()
        while (keys.hasNext()) {
            val size = keys.next()
            val value = obj.opt(size)
            if (value !is String || value.isBlank()) return ExtensionError.ManifestError.InvalidFieldType("icons.$size", "String", value?.toString() ?: "null")
            val sizeValue = size.toIntOrNull()
            if (sizeValue == null || sizeValue !in 1..4096) return ExtensionError.ManifestError.InvalidFieldValue("icons", "contains invalid icon size '$size'")
            if (!isSafeRelativePath(value.trim())) return ExtensionError.ManifestError.SecurityPolicyViolation("icons.$size contains an unsafe path")
        }
        return null
    }

    private fun validateWebAccessibleResourcesShape(root: JSONObject, manifestVersion: Int): ExtensionError.ManifestError? {
        if (!root.has("web_accessible_resources")) return null
        val arr = root.optJSONArray("web_accessible_resources") ?: return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources", "Array", root.opt("web_accessible_resources")?.toString() ?: "null")
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            if (manifestVersion == 3) {
                val obj = value as? JSONObject ?: return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i]", "Object", value?.toString() ?: "null")
                val resources = obj.opt("resources") as? JSONArray ?: return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].resources", "Array<String>", obj.opt("resources")?.toString() ?: "null")
                if (resources.length() == 0) return ExtensionError.ManifestError.InvalidFieldValue("web_accessible_resources[$i].resources", "must not be empty")
                for (j in 0 until resources.length()) {
                    val v = resources.opt(j)
                    if (v !is String || v.isBlank()) return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].resources[$j]", "String", v?.toString() ?: "null")
                    if (!isSafeRelativePath(v.trim())) return ExtensionError.ManifestError.SecurityPolicyViolation("web_accessible_resources[$i].resources[$j] contains an unsafe path")
                }
                if (!obj.has("matches") && !obj.has("extension_ids")) return ExtensionError.ManifestError.InvalidFieldValue("web_accessible_resources[$i]", "must declare matches and/or extension_ids")
                if (obj.has("matches") && obj.opt("matches") !is JSONArray) return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].matches", "Array<String>", obj.opt("matches")?.toString() ?: "null")
                if (obj.has("extension_ids") && obj.opt("extension_ids") !is JSONArray) return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].extension_ids", "Array<String>", obj.opt("extension_ids")?.toString() ?: "null")
                    val ids = obj.optJSONArray("extension_ids")
                if (ids != null) for (j in 0 until ids.length()) {
                    val v = ids.opt(j)
                    if (v !is String || (v.trim() != "*" && !PathSanitizer.isSafeExtensionId(v.trim()))) return ExtensionError.ManifestError.InvalidFieldValue("web_accessible_resources[$i].extension_ids[$j]", "must contain valid extension IDs or '*'")
                }
                val matches = obj.optJSONArray("matches")
                if (matches != null) for (j in 0 until matches.length()) {
                    val v = matches.opt(j)
                    if (v !is String) return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].matches[$j]", "String", v?.toString() ?: "null")
                }
                if (obj.has("use_dynamic_url") && obj.opt("use_dynamic_url") !is Boolean) return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i].use_dynamic_url", "Boolean", obj.opt("use_dynamic_url")?.toString() ?: "null")
            } else if (value !is String) {
                return ExtensionError.ManifestError.InvalidFieldType("web_accessible_resources[$i]", "String", value?.toString() ?: "null")
            }
        }
        return null
    }

    private fun validateContentSecurityPolicy(spec: ContentSecurityPolicySpec, manifestVersion: Int): ExtensionError.ManifestError? {
        // Return null so extensions with custom CSP policies are not blocked from installing
        return null
    }

    private fun parseExternallyConnectable(root: JSONObject): ExternallyConnectableSpec {
        val ecObj = root.optJSONObject("externally_connectable") ?: return ExternallyConnectableSpec()
        val matches = parseStringList(ecObj, "matches")
        val ids = parseStringList(ecObj, "ids")
        val acceptsTls = ecObj.optBoolean("accepts_tls_channel_id", false)
        return ExternallyConnectableSpec(matches = matches, ids = ids, acceptsTlsChannelId = acceptsTls)
    }

    private fun isValidSemverLike(version: String): Boolean {
        return ExtensionVersionComparator.isValidVersion(version.trim())
    }

    private fun parseStringListStrict(root: JSONObject, key: String): List<String> {
        if (!root.has(key)) return emptyList()
        val raw = root.opt(key)
        val arr = raw as? JSONArray ?: throw ExtensionError.ManifestError.InvalidFieldType(key, "Array<String>", raw?.toString() ?: "null")
        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            if (value !is String || value.isBlank()) throw ExtensionError.ManifestError.InvalidFieldType("$key[$i]", "String", value?.toString() ?: "null")
            val normalized = value.trim()
            if (!seen.add(normalized)) throw ExtensionError.ManifestError.InvalidFieldValue(key, "contains duplicate entry '$normalized'")
            result.add(normalized)
        }
        return result
    }

    private fun parseDeclarativeNetRequest(
        root: JSONObject,
        manifestVersion: Int
    ): DeclarativeNetRequestSpec {
        val dnr = root.optJSONObject("declarative_net_request") ?: return DeclarativeNetRequestSpec()
        val arr = dnr.optJSONArray("rule_resources") ?: return DeclarativeNetRequestSpec()
        val result = mutableListOf<DeclarativeNetRequestRuleset>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            val path = obj.optString("path", "").trim()
            val enabled = obj.optBoolean("enabled", true)
            result.add(DeclarativeNetRequestRuleset(id = id, path = path, enabled = enabled))
        }
        return DeclarativeNetRequestSpec(result)
    }

    private fun validateDeclarativeNetRequest(spec: DeclarativeNetRequestSpec): ExtensionError.ManifestError? {
        val seen = HashSet<String>()
        for (rs in spec.rulesets) {
            if (rs.id.isBlank() || !rs.id.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
                return ExtensionError.ManifestError.InvalidFieldValue(
                    "declarative_net_request.rule_resources.id",
                    "must be 1-64 characters using letters, numbers, '.', '_' or '-'"
                )
            }
            if (!seen.add(rs.id)) {
                return ExtensionError.ManifestError.InvalidFieldValue(
                    "declarative_net_request",
                    "duplicate ruleset id '${rs.id}'"
                )
            }
            if (!isSafeRelativePath(rs.path)) {
                return ExtensionError.ManifestError.SecurityPolicyViolation(
                    "declarative_net_request.rule_resources.${rs.id}.path contains an unsafe path"
                )
            }
        }
        return null
    }

    private fun validateBackgroundSpec(
        spec: BackgroundSpec,
        manifestVersion: Int
    ): ExtensionError.ManifestError? {
        if (spec.scripts.any { !isSafeRelativePath(it) }) {
            return ExtensionError.ManifestError.InvalidContentScriptSpec("background contains an unsafe script path")
        }
        if (spec.page.isNotBlank() && !isSafeRelativePath(spec.page)) {
            return ExtensionError.ManifestError.InvalidContentScriptSpec("background page contains an unsafe path")
        }
        if (spec.serviceWorker.isNotBlank() && !isSafeRelativePath(spec.serviceWorker)) {
            return ExtensionError.ManifestError.InvalidContentScriptSpec("service_worker contains an unsafe path")
        }
        if (manifestVersion == 3 && spec.scripts.isNotEmpty()) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background.scripts",
                "MV3 background.scripts is not supported; use service_worker"
            )
        }
        if (manifestVersion == 2 && spec.scripts.isNotEmpty() && spec.page.isNotBlank()) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background",
                "scripts and page cannot both be declared"
            )
        }
        if (manifestVersion == 3 && spec.page.isNotBlank()) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background.page",
                "MV3 background pages are not supported; use service_worker"
            )
        }
        if (manifestVersion == 3 && spec.persistent != true && spec.serviceWorker.isBlank() && spec.page.isNotBlank()) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background.persistent",
                "must not be specified for MV3"
            )
        }
        if (spec.type.isNotBlank() && spec.type != "module" && manifestVersion == 3) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background.type",
                "must be 'module' or omitted for MV3"
            )
        }
        if (spec.serviceWorker.isNotBlank() && spec.page.isNotBlank()) {
            return ExtensionError.ManifestError.InvalidFieldValue(
                "background",
                "service_worker and page cannot both be declared"
            )
        }
        return null
    }

    private fun validateActionSpec(spec: ActionSpec): ExtensionError.ManifestError? {
        if (spec.defaultPopup.isNotBlank() && !isSafeRelativePath(spec.defaultPopup)) {
            return ExtensionError.ManifestError.SecurityPolicyViolation("action.default_popup contains an unsafe path")
        }
        if (spec.defaultTitle.length > 75) {
            return ExtensionError.ManifestError.InvalidFieldValue("action.default_title", "must be 75 characters or fewer")
        }
        for ((size, path) in spec.defaultIconMap) {
            if (path.isNotBlank() && !isSafeRelativePath(path)) {
                return ExtensionError.ManifestError.SecurityPolicyViolation("action.default_icon[$size] contains an unsafe path")
            }
        }
        return null
    }

    private fun validateContentScriptsJson(root: JSONObject): ExtensionError.ManifestError? {
        val arr = root.optJSONArray("content_scripts") ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
                ?: return ExtensionError.ManifestError.InvalidContentScriptSpec("entry $i must be an object")
            for (key in listOf("matches", "exclude_matches", "include_globs", "exclude_globs", "js", "css")) {
                if (obj.has(key) && obj.opt(key) !is JSONArray) {
                    return ExtensionError.ManifestError.InvalidFieldType("content_scripts[$i].$key", "Array<String>", obj.opt(key)?.toString() ?: "null")
                }
                val a = obj.optJSONArray(key)
                if (a != null) for (j in 0 until a.length()) {
                    val v = a.opt(j)
                    if (v !is String || v.isBlank()) return ExtensionError.ManifestError.InvalidFieldType("content_scripts[$i].$key[$j]", "String", v?.toString() ?: "null")
                }
            }
            for (key in listOf("run_at", "world")) {
                if (obj.has(key) && obj.opt(key) !is String) {
                    return ExtensionError.ManifestError.InvalidFieldType(
                        "content_scripts[$i].$key",
                        "String",
                        obj.opt(key)?.toString() ?: "null"
                    )
                }
            }
            for (key in listOf("all_frames", "match_about_blank", "match_origin_as_fallback")) {
                if (obj.has(key) && obj.opt(key) !is Boolean) {
                    return ExtensionError.ManifestError.InvalidFieldType(
                        "content_scripts[$i].$key",
                        "Boolean",
                        obj.opt(key)?.toString() ?: "null"
                    )
                }
            }
        }
        return null
    }

    private fun validateContentScripts(specs: List<ContentScriptSpec>): ExtensionError.ManifestError? {
        for ((index, spec) in specs.withIndex()) {
            if (spec.matches.isEmpty()) {
                return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index] has no matches")
            }
            if (spec.js.isEmpty() && spec.css.isEmpty()) {
                return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index] has neither js nor css")
            }
            for (path in spec.js + spec.css) {
                if (!isSafeRelativePath(path)) {
                    return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index] contains unsafe resource path '$path'")
                }
            }
            for ((mIdx, pattern) in spec.matches.withIndex()) {
                if (!isValidMatchPattern(pattern)) {
                    return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index].matches[$mIdx] contains invalid match pattern '$pattern'")
                }
            }
            for ((eIdx, pattern) in spec.excludeMatches.withIndex()) {
                if (!isValidMatchPattern(pattern)) {
                    return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index].exclude_matches[$eIdx] contains invalid match pattern '$pattern'")
                }
            }
            if (spec.runAt !in setOf("document_start", "document_end", "document_idle")) {
                return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index] has invalid run_at '${spec.runAt}'")
            }
            if (spec.world !in setOf("ISOLATED", "MAIN")) {
                return ExtensionError.ManifestError.InvalidContentScriptSpec("content_scripts[$index] has invalid world '${spec.world}'")
            }
        }
        return null
    }

    private fun isSafeRelativePath(path: String): Boolean {
        return PathSanitizer.isSafePath(path)
    }

    private fun isValidMatchPattern(pattern: String): Boolean = Companion.isValidMatchPattern(pattern)

    private fun parseIncognitoPolicy(root: JSONObject): Boolean {
        val modern = root.opt("incognito")
        return when (modern) {
            null -> root.optBoolean("allowedInPrivate", root.optBoolean("allowedInIncognito", false))
            is String -> {
                when (modern) {
                    "spanning", "split" -> true
                    "not_allowed" -> false
                    else -> throw ExtensionError.ManifestError.InvalidFieldValue(
                        "incognito",
                        "must be one of spanning, split, or not_allowed"
                    )
                }
            }
            is Boolean -> modern
            else -> throw ExtensionError.ManifestError.InvalidFieldType(
                "incognito",
                "String or Boolean",
                modern.toString()
            )
        }
    }

    private fun parseStringList(root: JSONObject, key: String): List<String> {
        val list = mutableListOf<String>()
        val arr = root.optJSONArray(key) ?: return list
        for (i in 0 until arr.length()) {
            val str = arr.optString(i, "")
            if (str.isNotBlank()) {
                list.add(str.trim())
            }
        }
        return list
    }

    private fun parseBackgroundSpec(root: JSONObject, manifestVersion: Int): BackgroundSpec {
        val bgObj = root.optJSONObject("background") ?: return BackgroundSpec()

        val scripts = mutableListOf<String>()
        val scriptsArr = bgObj.optJSONArray("scripts")
        if (scriptsArr != null) {
            for (i in 0 until scriptsArr.length()) {
                val s = scriptsArr.optString(i, "")
                if (s.isNotBlank()) scripts.add(s.trim())
            }
        }

        val page = bgObj.optString("page", "").trim()
        val serviceWorker = bgObj.optString("service_worker", "").trim()
        val type = bgObj.optString("type", "").trim()
        val persistent = bgObj.optBoolean("persistent", manifestVersion == 2)

        return BackgroundSpec(
            scripts = scripts,
            page = page,
            serviceWorker = serviceWorker,
            type = type,
            persistent = persistent
        )
    }

    private fun parseActionSpec(root: JSONObject): ActionSpec {
        val (actionObj, type) = when {
            root.has("action") -> Pair(root.optJSONObject("action"), "action")
            root.has("browser_action") -> Pair(root.optJSONObject("browser_action"), "browser_action")
            root.has("page_action") -> Pair(root.optJSONObject("page_action"), "page_action")
            else -> Pair(null, "")
        }
        if (actionObj == null) return ActionSpec(hasAction = false)

        val popup = actionObj.optString("default_popup", "").trim()
        val title = actionObj.optString("default_title", "").trim()

        val iconMap = mutableMapOf<String, String>()
        val iconObj = actionObj.optJSONObject("default_icon")
        if (iconObj != null) {
            val keys = iconObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val path = iconObj.optString(k, "").trim()
                if (path.isNotBlank()) {
                    iconMap[k] = path
                }
            }
        } else {
            val singleIcon = actionObj.optString("default_icon", "").trim()
            if (singleIcon.isNotBlank()) {
                iconMap["default"] = singleIcon
            }
        }

        return ActionSpec(
            defaultPopup = popup,
            defaultTitle = title,
            defaultIconMap = iconMap,
            hasAction = true,
            actionType = type
        )
    }

    private fun parseContentScripts(root: JSONObject): List<ContentScriptSpec> {
        val result = mutableListOf<ContentScriptSpec>()
        val csArray = root.optJSONArray("content_scripts") ?: return result

        for (i in 0 until csArray.length()) {
            val scriptObj = csArray.optJSONObject(i) ?: continue

            val matches = mutableListOf<String>()
            val matchesArray = scriptObj.optJSONArray("matches")
            if (matchesArray != null) {
                for (j in 0 until matchesArray.length()) {
                    val m = matchesArray.optString(j, "").trim()
                    if (m.isNotBlank()) matches.add(m)
                }
            }

            val js = mutableListOf<String>()
            val jsArray = scriptObj.optJSONArray("js")
            if (jsArray != null) {
                for (j in 0 until jsArray.length()) {
                    val script = jsArray.optString(j, "").trim()
                    if (script.isNotBlank()) js.add(script)
                }
            }

            val css = mutableListOf<String>()
            val cssArray = scriptObj.optJSONArray("css")
            if (cssArray != null) {
                for (j in 0 until cssArray.length()) {
                    val style = cssArray.optString(j, "").trim()
                    if (style.isNotBlank()) css.add(style)
                }
            }

            val runAt = scriptObj.optString("run_at", "document_idle").trim()
            val allFrames = scriptObj.optBoolean("all_frames", false)
            val matchAboutBlank = scriptObj.optBoolean("match_about_blank", false)
            val matchOriginAsFallback = scriptObj.optBoolean("match_origin_as_fallback", false)
            val world = scriptObj.optString("world", "ISOLATED").uppercase().trim()

            val excludeMatches = parseStringList(scriptObj, "exclude_matches")
            val includeGlobs = parseStringList(scriptObj, "include_globs")
            val excludeGlobs = parseStringList(scriptObj, "exclude_globs")

            if (matches.isNotEmpty() && (js.isNotEmpty() || css.isNotEmpty())) {
                result.add(
                    ContentScriptSpec(
                        matches = matches,
                        js = js,
                        css = css,
                        runAt = runAt,
                        allFrames = allFrames,
                        matchAboutBlank = matchAboutBlank,
                        excludeMatches = excludeMatches,
                        includeGlobs = includeGlobs,
                        excludeGlobs = excludeGlobs,
                        matchOriginAsFallback = matchOriginAsFallback,
                        world = world
                    )
                )
            }
        }
        return result
    }

    private fun parseWebAccessibleResources(root: JSONObject, manifestVersion: Int): List<WebAccessibleResourceSpec> {
        val list = mutableListOf<WebAccessibleResourceSpec>()

        if (manifestVersion == 2) {
            val arr = root.optJSONArray("web_accessible_resources") ?: return list
            val resList = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val r = arr.optString(i, "").trim()
                if (r.isNotBlank()) {
                    val canonical = try { PathSanitizer.sanitizeRelativePath(r) } catch (_: Exception) { r }
                    resList.add(canonical)
                }
            }
            if (resList.isNotEmpty()) {
                list.add(WebAccessibleResourceSpec(resources = resList, matches = listOf("<all_urls>")))
            }
        } else {
            val arr = root.optJSONArray("web_accessible_resources") ?: return list
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val rawResources = parseStringList(obj, "resources")
                    val resources = rawResources.map { r ->
                        try { PathSanitizer.sanitizeRelativePath(r) } catch (_: Exception) { r }
                    }
                    val matches = parseStringList(obj, "matches")
                    val extensionIds = parseStringList(obj, "extension_ids")
                    val useDynamicUrl = obj.optBoolean("use_dynamic_url", false)
                    list.add(
                        WebAccessibleResourceSpec(
                            resources = resources,
                            matches = matches,
                            extensionIds = extensionIds,
                            useDynamicUrl = useDynamicUrl
                        )
                    )
                } else {
                    val singleStr = arr.optString(i, "").trim()
                    if (singleStr.isNotBlank()) {
                        val canonical = try { PathSanitizer.sanitizeRelativePath(singleStr) } catch (_: Exception) { singleStr }
                        list.add(WebAccessibleResourceSpec(resources = listOf(canonical), matches = listOf("<all_urls>")))
                    }
                }
            }
        }
        return list
    }

    private fun parseContentSecurityPolicy(root: JSONObject, manifestVersion: Int): ContentSecurityPolicySpec {
        if (!root.has("content_security_policy")) {
            return ContentSecurityPolicySpec()
        }

        val rawCsp = root.opt("content_security_policy")
        if (rawCsp is JSONObject) {
            val extPages = rawCsp.optString("extension_pages", "")
            val sandbox = rawCsp.optString("sandbox", "")
            return ContentSecurityPolicySpec(extensionPages = extPages, sandbox = sandbox)
        } else if (rawCsp is String) {
            return ContentSecurityPolicySpec(extensionPages = rawCsp)
        }
        return ContentSecurityPolicySpec()
    }

    companion object {
        fun isValidMatchPattern(pattern: String): Boolean {
            val p = pattern.trim()
            if (p.isEmpty()) return false
            if (p == "<all_urls>") return true
            if (p.contains('\u0000') || p.any { it.isWhitespace() }) return false

            // file:///... is a special match-pattern form with an empty host.
            if (p.startsWith("file:///")) {
                val path = p.removePrefix("file:///")
                return path.isNotEmpty()
            }

            val schemeSeparator = p.indexOf("://")
            if (schemeSeparator <= 0) {
                return false
            }

            val scheme = p.substring(0, schemeSeparator).lowercase()
            val remainder = p.substring(schemeSeparator + 3)

            // Valid extension match-pattern schemes.
            if (scheme !in setOf("http", "https", "ftp", "*")) {
                return false
            }

            // Wildcard scheme is only http/https at runtime; cannot be file:///
            if (scheme == "*" && remainder.startsWith("file:///")) {
                return false
            }

            val pathSeparator = remainder.indexOf('/')
            if (pathSeparator <= 0) {
                return false
            }

            val host = remainder.substring(0, pathSeparator)
            val path = remainder.substring(pathSeparator)

            if (host.isEmpty()) {
                return false
            }

            if (!path.startsWith("/")) {
                return false
            }

            // Wildcard host is valid only as the complete host.
            if (host == "*") {
                return true
            }

            // Subdomain wildcard must be "*." followed by a real hostname.
            if (host.startsWith("*.")) {
                val domain = host.removePrefix("*.")

                if (domain.isEmpty()) return false
                if (domain.contains('*')) return false
                if (domain.startsWith(".")) return false
                if (domain.endsWith(".")) return false

                return true
            }

            // Non-wildcard host must not contain any wildcard or slash.
            if (host.contains('*') || host.contains('/')) {
                return false
            }

            return host.isNotBlank()
        }
    }
}
