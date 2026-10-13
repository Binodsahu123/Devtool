package com.swift.browser.extensionengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Real implementation of Chrome i18n API.
 * Parses and caches extension _locales/<locale>/messages.json catalogs,
 * supporting placeholder substitution ($1, $2, $VAR$), case-insensitive keys,
 * and locale fallbacks.
 */
class ExtensionI18nAdapter(
    private val context: Context,
    private val registry: ExtensionRegistry
) {
    // Cache: Map<ExtensionId, Map<LocaleCode, Map<LowerKey, MessageEntry>>>
    private val localeCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, MessageEntry>>>()

    data class MessageEntry(
        val message: String,
        val placeholders: Map<String, String> = emptyMap()
    )

    fun getMessage(sender: ExtensionSender, messageName: String, substitutions: Any? = null): String {
        if (messageName.isBlank()) return ""
        val ext = registry.getExtension(sender.extensionId) ?: return ""

        val catalog = getExtensionCatalog(sender.extensionId, ext)
        val lowerKey = messageName.lowercase()
        val entry = catalog[lowerKey] ?: return ""

        var result = entry.message

        // 1. Process named placeholders
        for ((placeholderKey, contentVal) in entry.placeholders) {
            val target = "$$placeholderKey$"
            result = result.replace(target, contentVal, ignoreCase = true)
        }

        // 2. Process positional substitutions ($1, $2, etc.)
        when (substitutions) {
            is JSONArray -> {
                for (i in 0 until substitutions.length()) {
                    val subVal = substitutions.optString(i, "")
                    result = result.replace("$${i + 1}", subVal)
                }
            }
            is List<*> -> {
                for ((i, item) in substitutions.withIndex()) {
                    val subVal = item?.toString() ?: ""
                    result = result.replace("$${i + 1}", subVal)
                }
            }
            is String -> {
                result = result.replace("$1", substitutions)
            }
            is Number -> {
                result = result.replace("$1", substitutions.toString())
            }
        }

        // 3. Unescape double dollars ($$ -> $)
        result = result.replace("$$", "\u0000").replace("\u0000", "$")

        return result
    }

    fun getUILanguage(): String {
        return Locale.getDefault().toLanguageTag()
    }

    fun getAcceptLanguages(): JSONArray {
        val list = JSONArray()
        val defaultTag = Locale.getDefault().toLanguageTag()
        list.put(defaultTag)
        val defaultLang = Locale.getDefault().language
        if (defaultLang != defaultTag) {
            list.put(defaultLang)
        }
        if (defaultLang != "en") {
            list.put("en-US")
            list.put("en")
        }
        return list
    }

    fun detectLanguage(text: String): JSONObject {
        val detected = if (text.isBlank()) {
            Locale.getDefault().language
        } else {
            // Heuristic script detection
            when {
                text.any { it in '\u0900'..'\u097F' } -> "hi"
                text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
                text.any { it in '\u3040'..'\u30FF' } -> "ja"
                text.any { it in '\u0400'..'\u04FF' } -> "ru"
                text.any { it in '\u0600'..'\u06FF' } -> "ar"
                else -> Locale.getDefault().language.ifBlank { "en" }
            }
        }

        return JSONObject().apply {
            put("isReliable", true)
            put("languages", JSONArray().apply {
                put(JSONObject().apply {
                    put("language", detected)
                    put("percentage", 100)
                })
            })
        }
    }

    private fun getExtensionCatalog(extensionId: String, ext: ParsedExtension): Map<String, MessageEntry> {
        val extCache = localeCache.getOrPut(extensionId) { ConcurrentHashMap() }
        val currentLocale = Locale.getDefault().toString() // e.g. en_US or hi_IN
        val currentLang = Locale.getDefault().language // e.g. en or hi
        val defaultLocale = ext.defaultLocale.ifBlank { "en" }

        // Candidate priority: en_US -> en -> defaultLocale -> first available
        val candidates = listOf(currentLocale, currentLang, defaultLocale, "en", "en_US").distinct()

        for (cand in candidates) {
            val cached = extCache[cand]
            if (cached != null) return cached

            val loaded = loadLocaleFile(ext, cand)
            if (loaded != null) {
                extCache[cand] = loaded
                return loaded
            }
        }

        return emptyMap()
    }

    private fun loadLocaleFile(ext: ParsedExtension, localeName: String): Map<String, MessageEntry>? {
        val rootPath = ext.installPath.takeIf { it.isNotBlank() } ?: return null
        val localesDir = File(rootPath, "_locales")
        if (!localesDir.exists() || !localesDir.isDirectory) return null

        var targetDir = File(localesDir, localeName)
        if (!targetDir.exists() || !targetDir.isDirectory) {
            targetDir = File(localesDir, localeName.replace("-", "_"))
        }
        if (!targetDir.exists() || !targetDir.isDirectory) {
            targetDir = File(localesDir, localeName.lowercase())
        }
        if (!targetDir.exists() || !targetDir.isDirectory) {
            return null
        }

        val messagesFile = File(targetDir, "messages.json")
        if (!messagesFile.exists() || !messagesFile.isFile) return null

        return try {
            val content = messagesFile.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            val map = mutableMapOf<String, MessageEntry>()

            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entryObj = json.optJSONObject(key) ?: continue
                val msg = entryObj.optString("message", "")
                val placeholdersMap = mutableMapOf<String, String>()

                val phObj = entryObj.optJSONObject("placeholders")
                if (phObj != null) {
                    val phKeys = phObj.keys()
                    while (phKeys.hasNext()) {
                        val phKey = phKeys.next()
                        val phData = phObj.optJSONObject(phKey)
                        val phContent = phData?.optString("content", "") ?: ""
                        placeholdersMap[phKey] = phContent
                    }
                }

                map[key.lowercase()] = MessageEntry(message = msg, placeholders = placeholdersMap)
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache(extensionId: String) {
        localeCache.remove(extensionId)
    }
}
