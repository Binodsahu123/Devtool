package com.swift.browser.extensionengine

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

data class ScriptExecutionTarget(
    val tabId: String,
    val allFrames: Boolean = false,
    val frameIds: List<Int> = listOf(0),
    val documentIds: List<String>? = null,
    val world: ContentScriptWorld = ContentScriptWorld.ISOLATED,
    val worldId: String = "default",
    val extensionId: String = ""
)

data class FrameExecutionResult(
    val frameId: Int,
    val documentId: String,
    val result: Any?,
    val error: String? = null
)

/**
 * Provider-aware script execution. Both ISOLATED and MAIN world execution use the canonical
 * WebView frame/document transport when the provider supports frame/world injection. No
 * JavaScript-level isolation emulation is used.
 */
class WorldAwareScriptExecutor {
    fun execute(
        delegate: BrowserDelegate,
        target: ScriptExecutionTarget,
        code: String,
        isFunction: Boolean,
        argsJson: String,
        documentId: String,
        callback: (List<FrameExecutionResult>?, String?) -> Unit
    ) {
        if (target.world == ContentScriptWorld.USER_SCRIPT) {
            val args = runCatching { JSONArray(argsJson) }.getOrElse {
                callback(null, "SCRIPTING_INVALID_ARGS")
                return
            }
            val webView = delegate.getWebView(target.tabId)
            if (webView == null) {
                callback(null, "SCRIPTING_TAB_NOT_FOUND")
                return
            }
            webView.post {
                com.swift.browser.extensionengine.nativecore.WebViewUserScriptWorldBridge.execute(
                    webView = webView,
                    tabId = target.tabId,
                    extensionId = target.extensionId,
                    worldId = target.worldId,
                    code = code,
                    isFunction = isFunction,
                    args = args,
                    allFrames = target.allFrames,
                    frameIds = target.frameIds,
                    documentIds = target.documentIds,
                    callback = { jsonArray, error ->
                        if (jsonArray == null) {
                            callback(null, error)
                        } else {
                            val list = mutableListOf<FrameExecutionResult>()
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.optJSONObject(i)
                                if (obj != null) {
                                    list += FrameExecutionResult(
                                        frameId = obj.optInt("frameId", 0),
                                        documentId = obj.optString("documentId", ""),
                                        result = obj.opt("result"),
                                        error = obj.optString("error").takeIf { !it.isNullOrBlank() }
                                    )
                                }
                            }
                            callback(list, error)
                        }
                    }
                )
            }
            return
        }

        if (target.world == ContentScriptWorld.ISOLATED) {
            val result = runCatching { JSONArray(argsJson) }.getOrElse {
                callback(null, "SCRIPTING_INVALID_ARGS")
                return
            }
            delegate.executeExtensionScript(
                extensionId = target.extensionId,
                target = target,
                code = code,
                isFunction = isFunction,
                argsJson = result.toString()
            ) { results, error ->
                if (error != null) callback(null, error)
                else {
                    val list = mutableListOf<FrameExecutionResult>()
                    for (i in 0 until (results?.length() ?: 0)) {
                        val obj = results?.optJSONObject(i) ?: continue
                        list.add(
                            FrameExecutionResult(
                                frameId = obj.optInt("frameId", 0),
                                documentId = obj.optString("documentId", ""),
                                result = if (obj.isNull("result")) null else obj.opt("result"),
                                error = obj.optString("error", "").ifBlank { null }
                            )
                        )
                    }
                    callback(list, null)
                }
            }
            return
        }

        if (!com.swift.browser.extensionengine.nativecore.NativeExtensionCoreBridge.validateMainWorldScript(code)) {
            callback(null, "SCRIPTING_MAIN_WORLD_CODE_REJECTED")
            return
        }

        val args = runCatching { JSONArray(argsJson) }.getOrElse {
            callback(null, "SCRIPTING_INVALID_ARGS")
            return
        }

        // BrowserDelegate routes MAIN-world execution to the canonical WebView page-world
        // frame bridge. This keeps frameIds/documentIds consistent with ISOLATED execution.
        delegate.executeExtensionScript(
            extensionId = target.extensionId,
            target = target,
            code = code,
            isFunction = isFunction,
            argsJson = args.toString()
        ) { results, error ->
            if (error != null) {
                callback(null, error)
                return@executeExtensionScript
            }

            val list = mutableListOf<FrameExecutionResult>()
            for (i in 0 until (results?.length() ?: 0)) {
                val item = results?.optJSONObject(i) ?: continue
                list.add(
                    FrameExecutionResult(
                        frameId = item.optInt("frameId", 0),
                        documentId = item.optString("documentId", documentId),
                        result = if (item.isNull("result")) null else item.opt("result"),
                        error = item.optString("error", "").ifBlank { null }
                    )
                )
            }
            callback(list, null)
        }

    }
}
