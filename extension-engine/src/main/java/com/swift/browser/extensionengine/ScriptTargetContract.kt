package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical Chrome scripting/userScripts injection target parser.
 *
 * One owner keeps frameIds/documentIds/allFrames validation identical across APIs. The target
 * remains a browser-owned logical target; it is never treated as a Chromium internal FrameTree ID.
 */
data class ScriptTargetContract(
    val rawTabId: String,
    val canonicalTabId: String,
    val allFrames: Boolean,
    val frameIds: List<Int>,
    val documentIds: List<String>
) {
    val usesFrameIds: Boolean get() = frameIds.isNotEmpty()
    val usesDocumentIds: Boolean get() = documentIds.isNotEmpty()
    val isTopFrameDefault: Boolean get() = !allFrames && !usesFrameIds && !usesDocumentIds

    fun toExecutionTarget(
        world: ContentScriptWorld,
        extensionId: String,
        worldId: String = "default"
    ): ScriptExecutionTarget = ScriptExecutionTarget(
        tabId = canonicalTabId,
        allFrames = allFrames,
        frameIds = if (allFrames || documentIds.isNotEmpty()) emptyList() else frameIds.ifEmpty { listOf(0) },
        documentIds = documentIds.takeIf { it.isNotEmpty() },
        world = world,
        worldId = worldId,
        extensionId = extensionId
    )

    companion object {
        fun parse(target: JSONObject?, invalidCode: String = "SCRIPTING_INVALID_TARGET"): ScriptTargetContract {
            if (target == null) throw IllegalArgumentException(invalidCode)
            val rawTabId = target.opt("tabId")?.toString()?.trim().orEmpty()
            if (rawTabId.isBlank()) throw IllegalArgumentException(invalidCode)

            val allFrames = target.optBoolean("allFrames", false)
            val frameIds = parseFrameIds(target.optJSONArray("frameIds"), invalidCode)
            val documentIds = parseDocumentIds(target.optJSONArray("documentIds"), invalidCode)

            if (allFrames && frameIds.isNotEmpty()) {
                throw IllegalArgumentException("SCRIPTING_TARGET_CONFLICT")
            }
            if (allFrames && documentIds.isNotEmpty()) {
                throw IllegalArgumentException("SCRIPTING_TARGET_CONFLICT")
            }
            if (frameIds.isNotEmpty() && documentIds.isNotEmpty()) {
                throw IllegalArgumentException("SCRIPTING_TARGET_CONFLICT")
            }

            return ScriptTargetContract(
                rawTabId = rawTabId,
                canonicalTabId = TabIdMapper.getUuidFromString(rawTabId).ifBlank { rawTabId },
                allFrames = allFrames,
                frameIds = frameIds,
                documentIds = documentIds
            )
        }

        private fun parseFrameIds(array: JSONArray?, invalidCode: String): List<Int> {
            if (array == null) return emptyList()
            val result = LinkedHashSet<Int>()
            for (i in 0 until array.length()) {
                val value = array.opt(i)
                val id = when (value) {
                    is Number -> value.toInt()
                    else -> value?.toString()?.toIntOrNull()
                } ?: throw IllegalArgumentException("${invalidCode}_FRAME_ID_INVALID")
                if (id < 0) throw IllegalArgumentException("${invalidCode}_FRAME_ID_INVALID")
                result += id
            }
            return result.toList()
        }

        private fun parseDocumentIds(array: JSONArray?, invalidCode: String): List<String> {
            if (array == null) return emptyList()
            val result = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i, "").trim()
                if (value.isBlank()) throw IllegalArgumentException("${invalidCode}_DOCUMENT_ID_INVALID")
                result += value
            }
            return result.toList()
        }
    }
}
