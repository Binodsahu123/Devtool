package com.swift.browser.extensionengine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable owner for chrome.scripting.registerContentScripts() registrations.
 *
 * Dynamic registrations are browser state, not manifest state. The registry remains the runtime
 * source-of-truth while this store is the process-boundary persistence layer for registrations
 * whose persistAcrossSessions flag is true.
 *
 * This class deliberately serializes only declarative registration data. It never stores compiled
 * JavaScript, executable objects, WebView instances, callbacks, or runtime handles.
 */
class DynamicContentScriptStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("extension_dynamic_content_scripts", Context.MODE_PRIVATE)

    fun load(extensionId: String): List<DynamicContentScriptSpec> {
        if (extensionId.isBlank()) return emptyList()
        val raw = prefs.getString(key(extensionId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val result = ArrayList<DynamicContentScriptSpec>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val spec = deserialize(obj)
                if (spec.persistAcrossSessions) result += spec
            }
            result
        }.getOrDefault(emptyList())
    }

    /**
     * Upsert only the supplied dynamic ids. Unmentioned registrations remain untouched, matching
     * Chrome's updateContentScripts() semantics.
     */
    fun upsert(extensionId: String, scripts: List<DynamicContentScriptSpec>) {
        if (extensionId.isBlank()) return
        val current = load(extensionId).associateBy { it.id }.toMutableMap()
        scripts.forEach { spec ->
            if (spec.persistAcrossSessions) current[spec.id] = spec
            else current.remove(spec.id)
        }
        write(extensionId, current.values.sortedBy { it.id })
    }

    fun remove(extensionId: String, ids: Collection<String>) {
        if (extensionId.isBlank() || ids.isEmpty()) return
        val blocked = ids.toSet()
        val current = load(extensionId).filterNot { it.id in blocked }
        write(extensionId, current)
    }

    fun clear(extensionId: String) {
        if (extensionId.isBlank()) return
        prefs.edit().remove(key(extensionId)).apply()
    }

    fun snapshot(extensionId: String): List<DynamicContentScriptSpec> = load(extensionId)

    private fun write(extensionId: String, scripts: Collection<DynamicContentScriptSpec>) {
        val persistent = scripts.filter { it.persistAcrossSessions }
        val editor = prefs.edit()
        if (persistent.isEmpty()) {
            editor.remove(key(extensionId)).apply()
            return
        }

        val array = JSONArray()
        persistent.sortedBy { it.id }.forEach { array.put(serialize(it)) }
        editor.putString(key(extensionId), array.toString()).apply()
    }

    private fun serialize(spec: DynamicContentScriptSpec): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("matches", JSONArray(spec.matches))
        put("excludeMatches", JSONArray(spec.excludeMatches))
        put("includeGlobs", JSONArray(spec.includeGlobs))
        put("excludeGlobs", JSONArray(spec.excludeGlobs))
        put("js", JSONArray(spec.js))
        put("css", JSONArray(spec.css))
        put("runAt", spec.runAt)
        put("allFrames", spec.allFrames)
        put("matchAboutBlank", spec.matchAboutBlank)
        put("matchOriginAsFallback", spec.matchOriginAsFallback)
        put("world", spec.world)
        put("persistAcrossSessions", spec.persistAcrossSessions)
    }

    private fun deserialize(obj: JSONObject): DynamicContentScriptSpec {
        fun stringList(name: String): List<String> {
            val array = obj.optJSONArray(name) ?: return emptyList()
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    add(array.optString(i, "").trim())
                }
            }.filter { it.isNotBlank() }
        }

        return DynamicContentScriptSpec(
            id = obj.optString("id", "").trim(),
            matches = stringList("matches"),
            excludeMatches = stringList("excludeMatches"),
            includeGlobs = stringList("includeGlobs"),
            excludeGlobs = stringList("excludeGlobs"),
            js = stringList("js"),
            css = stringList("css"),
            runAt = obj.optString("runAt", "document_idle").trim().lowercase(),
            allFrames = obj.optBoolean("allFrames", false),
            matchAboutBlank = obj.optBoolean("matchAboutBlank", false),
            matchOriginAsFallback = obj.optBoolean("matchOriginAsFallback", false),
            world = obj.optString("world", "ISOLATED").trim().uppercase(),
            persistAcrossSessions = obj.optBoolean("persistAcrossSessions", true)
        )
    }

    private fun key(extensionId: String): String = "ext_${extensionId.lowercase().trim()}_scripts"
}
