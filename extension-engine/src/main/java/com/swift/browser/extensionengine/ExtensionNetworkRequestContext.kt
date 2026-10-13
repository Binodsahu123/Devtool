package com.swift.browser.extensionengine

import org.json.JSONObject

/**
 * Standardized network request context representing a request intercepted by the browser engine
 * and passed through security, adblock, declarativeNetRequest (DNR), and webRequest pipelines.
 */
data class ExtensionNetworkRequestContext(
    val requestId: String,
    val url: String,
    val method: String = "GET",
    val resourceType: String = "other",
    val tabId: Int = -1,
    val frameId: Int = 0,
    val parentFrameId: Int = -1,
    val documentId: String? = null,
    val documentUrl: String? = null,
    val timeStamp: Long = System.currentTimeMillis(),
    val isPrivate: Boolean = false,
    val privateSessionId: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val isForMainFrame: Boolean = false,
    val initiator: String? = null,
    val origin: String? = null,
    val redirectChain: List<String> = emptyList(),
    val windowId: Int? = null
) {
    /**
     * Converts to legacy WebRequestData for compatibility.
     */
    fun toWebRequestData(): WebRequestData {
        return WebRequestData(
            requestId = requestId,
            url = url,
            method = method,
            type = resourceType,
            tabId = tabId,
            frameId = frameId,
            documentId = documentId,
            timeStamp = timeStamp,
            isPrivate = isPrivate,
            windowId = windowId
        )
    }

    /**
     * Converts this context to a JSON payload suitable for Chrome webRequest events.
     */
    fun toEventPayload(
        extraDetails: JSONObject? = null,
        includeRequestHeaders: Boolean = false,
        includeResponseHeaders: Boolean = false
    ): JSONObject {
        val payload = JSONObject().apply {
            put("requestId", requestId)
            put("url", url)
            put("method", method)
            put("type", resourceType)
            put("tabId", tabId)
            put("frameId", frameId)
            put("parentFrameId", parentFrameId)
            if (documentId != null) put("documentId", documentId)
            if (documentUrl != null) put("documentUrl", documentUrl)
            if (initiator != null) put("initiator", initiator)
            if (origin != null) put("origin", origin)
            if (redirectChain.isNotEmpty()) put("redirectChain", org.json.JSONArray(redirectChain))
            if (windowId != null) put("windowId", windowId)
            put("timeStamp", timeStamp.toDouble())

            if (includeRequestHeaders && requestHeaders.isNotEmpty()) {
                val headersArr = org.json.JSONArray()
                for ((name, value) in requestHeaders) {
                    if (isSensitiveHeader(name)) continue
                    headersArr.put(JSONObject().apply {
                        put("name", name)
                        put("value", value)
                    })
                }
                put("requestHeaders", headersArr)
            }

            if (extraDetails != null) {
                val keys = extraDetails.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "requestHeaders" && !includeRequestHeaders) continue
                    if (key == "responseHeaders" && !includeResponseHeaders) continue
                    put(key, extraDetails.get(key))
                }
            }
        }
        return payload
    }

    companion object {
        private fun isSensitiveHeader(name: String): Boolean = when (name.lowercase()) {
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key" -> true
            else -> false
        }

        fun fromWebRequestData(data: WebRequestData, requestHeaders: Map<String, String> = emptyMap()): ExtensionNetworkRequestContext {
            return ExtensionNetworkRequestContext(
                requestId = data.requestId,
                url = data.url,
                method = data.method,
                resourceType = data.type,
                tabId = data.tabId,
                frameId = data.frameId,
                documentId = data.documentId,
                timeStamp = data.timeStamp,
                isPrivate = data.isPrivate,
                requestHeaders = requestHeaders,
                isForMainFrame = data.type.equals("main_frame", ignoreCase = true),
                windowId = data.windowId
            )
        }
    }
}
