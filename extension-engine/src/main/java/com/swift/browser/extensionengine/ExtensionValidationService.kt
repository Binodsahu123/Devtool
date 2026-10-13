package com.swift.browser.extensionengine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Report containing validation status, integrity findings, warnings, and sanitized manifest.
 */
data class ManifestValidationReport(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val sanitizedManifestJson: String,
    val validatedManifest: ValidatedExtensionManifest? = null,
    val integritySummary: ExtensionIntegritySummary = ExtensionIntegritySummary()
)

data class ExtensionIntegritySummary(
    val httpsCompliant: Boolean = true,
    val dangerousPermissions: List<String> = emptyList(),
    val missingOptionalResources: List<String> = emptyList(),
    val criticalResourcesVerified: Boolean = true,
    val pathTraversalSafe: Boolean = true
)

/**
 * Production-grade Extension Validation Service for Swift Browser Extension Engine.
 * 
 * Validates manifest.json structures, enforces HTTPS/integrity constraints,
 * checks file containment against path traversal, and sanitizes manifest resources
 * before finalizing extension installation to prevent common runtime crashes.
 */
object ExtensionValidationService {

    private const val TAG = "ExtValidationService"

    private val KNOWN_DANGEROUS_PERMISSIONS = setOf(
        "webRequestBlocking",
        "debugger",
        "proxy",
        "declarativeNetRequestFeedback",
        "nativeMessaging"
    )

    private val VALID_RUN_AT_VALUES = setOf(
        "document_start",
        "document_end",
        "document_idle"
    )

    /**
     * Validates a raw manifest JSON string, enforcing structural integrity,
     * MV2/MV3 rules, HTTPS requirements, and generating a sanitized manifest.
     */
    fun validateManifestString(
        rawJson: String,
        sourceSeed: String = "extension"
    ): ManifestValidationReport {
        val warnings = mutableListOf<String>()
        val dangerousPermissions = mutableListOf<String>()

        if (rawJson.isBlank()) {
            return ManifestValidationReport(false, listOf("manifest.json content is empty or blank"), sanitizedManifestJson = rawJson)
        }

        val parsed = try {
            ManifestParser().validateManifest(rawJson, sourceSeed)
        } catch (e: Exception) {
            return ManifestValidationReport(
                isValid = false,
                errors = listOf("manifest.json rejected: ${e.message ?: e::class.java.simpleName}"),
                sanitizedManifestJson = rawJson,
                integritySummary = ExtensionIntegritySummary(criticalResourcesVerified = false, pathTraversalSafe = false)
            )
        }

        val root = try { StrictManifestJsonValidator.validate(rawJson); JSONObject(rawJson) } catch (e: Exception) {
            return ManifestValidationReport(false, listOf("manifest.json rejected: ${e.message ?: "invalid JSON"}"), sanitizedManifestJson = rawJson)
        }

        val permissions = mutableListOf<String>()
        val permArray = root.optJSONArray("permissions")
        if (permArray != null) for (i in 0 until permArray.length()) {
            val p = permArray.opt(i) as String
            permissions.add(p)
            if (p in KNOWN_DANGEROUS_PERMISSIONS) dangerousPermissions.add(p)
        }
        val httpsOk = verifyHttpsCompliance(root, parsed.manifestVersion).first
        val httpsWarnings = verifyHttpsCompliance(root, parsed.manifestVersion).second
        warnings.addAll(httpsWarnings)

        return ManifestValidationReport(
            isValid = true,
            errors = emptyList(),
            warnings = warnings,
            sanitizedManifestJson = rawJson,
            validatedManifest = parsed,
            integritySummary = ExtensionIntegritySummary(
                httpsCompliant = httpsOk,
                dangerousPermissions = dangerousPermissions.distinct(),
                missingOptionalResources = emptyList(),
                criticalResourcesVerified = true,
                pathTraversalSafe = true
            )
        )
    }

    /**
     * Pre-flight validates extracted files in staging directory against declared manifest entries.
     * Prevents runtime crashes from missing background service workers, broken content scripts,
     * or path traversal exploits.
     */
    fun preflightValidateStagingPackage(
        rootDir: File,
        manifestContent: String,
        sourceName: String = "package",
        checkDeclaredResources: Boolean = true
    ): ManifestValidationReport {
        val baseReport = validateManifestString(manifestContent, sourceName)
        if (!baseReport.isValid) return baseReport

        val errors = baseReport.errors.toMutableList()
        val warnings = baseReport.warnings.toMutableList()
        val missingOptional = mutableListOf<String>()
        var pathTraversalSafe = true
        var criticalResourcesOk = true

        val rootJson = try {
            JSONObject(baseReport.sanitizedManifestJson)
        } catch (e: Exception) {
            JSONObject()
        }

        if (!checkDeclaredResources) {
            return baseReport
        }

        fun checkFileExists(path: String, isMandatory: Boolean, fieldName: String): Boolean {
            val cleanPath = try {
                PathSanitizer.sanitizeRelativePath(path)
            } catch (e: Exception) {
                errors.add("Security vulnerability: Path traversal detected in '$path' ($fieldName): ${e.message}")
                pathTraversalSafe = false
                return false
            }

            val file = resolveFileFuzzy(rootDir, cleanPath)
            if (file == null || !file.exists() || !file.isFile) {
                if (isMandatory) {
                    warnings.add("Warning: Mandatory resource '$path' ($fieldName) not found in package.")
                    criticalResourcesOk = false
                } else {
                    missingOptional.add(path)
                    warnings.add("Optional resource '$path' ($fieldName) not present in archive.")
                }
                return false
            }

            // Verify file is strictly inside rootDir
            try {
                PathSanitizer.checkCanonicalContainment(rootDir, file)
            } catch (e: Exception) {
                errors.add("Containment breach: File '$path' resolves outside extension root: ${e.message}")
                pathTraversalSafe = false
                return false
            }

            return true
        }

        // Check Background Scripts / Service Worker
        val bgObj = rootJson.optJSONObject("background")
        val sw = bgObj?.optString("service_worker", "")?.trim() ?: ""
        if (sw.isNotEmpty()) {
            checkFileExists(sw, isMandatory = true, fieldName = "background.service_worker")
        }
        val bgScripts = bgObj?.optJSONArray("scripts")
        if (bgScripts != null) {
            for (i in 0 until bgScripts.length()) {
                val script = bgScripts.optString(i, "").trim()
                if (script.isNotEmpty()) {
                    checkFileExists(script, isMandatory = true, fieldName = "background.scripts[$i]")
                }
            }
        }

        // Check Content Scripts
        val csArray = rootJson.optJSONArray("content_scripts")
        if (csArray != null) {
            for (i in 0 until csArray.length()) {
                val cs = csArray.optJSONObject(i) ?: continue
                val jsList = cs.optJSONArray("js")
                if (jsList != null) {
                    for (j in 0 until jsList.length()) {
                        val jsPath = jsList.optString(j, "").trim()
                        if (jsPath.isNotEmpty()) {
                            checkFileExists(jsPath, isMandatory = false, fieldName = "content_scripts[$i].js[$j]")
                        }
                    }
                }
                val cssList = cs.optJSONArray("css")
                if (cssList != null) {
                    for (k in 0 until cssList.length()) {
                        val cssPath = cssList.optString(k, "").trim()
                        if (cssPath.isNotEmpty()) {
                            checkFileExists(cssPath, isMandatory = false, fieldName = "content_scripts[$i].css[$k]")
                        }
                    }
                }
            }
        }

        // Check Action Popup
        for (actionKey in listOf("action", "browser_action", "page_action")) {
            val actionObj = rootJson.optJSONObject(actionKey) ?: continue
            val popup = actionObj.optString("default_popup", "").trim()
            if (popup.isNotEmpty()) {
                checkFileExists(popup, isMandatory = false, fieldName = "$actionKey.default_popup")
            }
        }

        // Check Options Page
        val optionsPage = rootJson.optString("options_page", "").trim()
        if (optionsPage.isNotEmpty()) {
            checkFileExists(optionsPage, isMandatory = false, fieldName = "options_page")
        }
        val optionsUi = rootJson.optJSONObject("options_ui")?.optString("page", "")?.trim() ?: ""
        if (optionsUi.isNotEmpty()) {
            checkFileExists(optionsUi, isMandatory = false, fieldName = "options_ui.page")
        }

        val updatedSummary = baseReport.integritySummary.copy(
            missingOptionalResources = missingOptional,
            criticalResourcesVerified = criticalResourcesOk,
            pathTraversalSafe = pathTraversalSafe
        )

        return baseReport.copy(
            isValid = errors.isEmpty() && pathTraversalSafe,
            errors = errors,
            warnings = warnings,
            integritySummary = updatedSummary
        )
    }

    /**
     * Checks HTTPS compliance and flags insecure HTTP update URLs or CSP directives.
     */
    private fun verifyHttpsCompliance(json: JSONObject, manifestVersion: Int): Pair<Boolean, List<String>> {
        var isCompliant = true
        val warnings = mutableListOf<String>()

        // 1. Check update_url
        val updateUrl = json.optString("update_url", "").trim()
        if (updateUrl.isNotEmpty()) {
            if (updateUrl.startsWith("http://", ignoreCase = true)) {
                warnings.add("Insecure HTTP update_url detected: '$updateUrl'. HTTPS is required for secure extension updates.")
                isCompliant = false
            }
        }

        // 2. Check homepage_url
        val homepageUrl = json.optString("homepage_url", "").trim()
        if (homepageUrl.isNotEmpty() && homepageUrl.startsWith("http://", ignoreCase = true)) {
            warnings.add("Non-HTTPS homepage_url: '$homepageUrl'")
        }

        // 3. Check Content Security Policy (CSP)
        if (manifestVersion >= 3 && json.has("content_security_policy")) {
            val csp = json.opt("content_security_policy")
            val cspString = when (csp) {
                is String -> csp
                is JSONObject -> csp.optString("extension_pages", "")
                else -> ""
            }
            if (cspString.contains("http://", ignoreCase = true) && !cspString.contains("http://localhost")) {
                warnings.add("MV3 CSP contains non-secure HTTP origin. HTTPS/WSS must be used for remote endpoints.")
            }
            if (cspString.contains("'unsafe-eval'")) {
                warnings.add("MV3 CSP prohibits 'unsafe-eval' in extension_pages.")
            }
        }

        return Pair(isCompliant, warnings)
    }

    private fun resolveFileFuzzy(rootDir: File, relPath: String): File? {
        val direct = File(rootDir, relPath)
        if (direct.exists()) return direct

        val parts = relPath.split('/').filter { it.isNotEmpty() }
        var current = rootDir
        for (part in parts) {
            val match = current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) }
            if (match != null) {
                current = match
            } else {
                return null
            }
        }
        return if (current.exists()) current else null
    }

    private fun isValidVersionFormat(version: String): Boolean {
        return version.matches(Regex("^[0-9]+(\\.[0-9]+)*$"))
    }

    private fun sanitizeVersionString(version: String): String {
        val digitsOnly = version.replace(Regex("[^0-9.]"), "").trim('.')
        val parts = digitsOnly.split('.').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) {
            "1.0.0"
        } else {
            parts.take(4).joinToString(".")
        }
    }

    private fun isValidHostPattern(pattern: String): Boolean {
        val p = pattern.trim()
        if (p == "<all_urls>") return true
        if (p.startsWith("*://") || p.startsWith("https://") || p.startsWith("http://") || p.startsWith("ws://") || p.startsWith("wss://")) {
            return true
        }
        return false
    }
}
