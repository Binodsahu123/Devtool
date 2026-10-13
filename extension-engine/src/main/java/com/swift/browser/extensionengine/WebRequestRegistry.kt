package com.swift.browser.extensionengine

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks in-flight network requests and lifecycle stages.
 */
class WebRequestRegistry {
    enum class RequestStage {
        BEFORE_REQUEST,
        BEFORE_SEND_HEADERS,
        SEND_HEADERS,
        HEADERS_RECEIVED,
        RESPONSE_STARTED,
        BEFORE_REDIRECT,
        COMPLETED,
        ERROR_OCCURRED
    }

    data class TrackedRequest(
        val context: ExtensionNetworkRequestContext,
        var currentStage: RequestStage = RequestStage.BEFORE_REQUEST,
        val startTime: Long = System.currentTimeMillis(),
        var finishTime: Long? = null,
        var error: String? = null,
        var redirectUrl: String? = null
    )

    private val inFlightRequests = ConcurrentHashMap<String, TrackedRequest>()
    private val terminalRequestIds = ConcurrentHashMap.newKeySet<String>()

    private fun stageRank(stage: RequestStage): Int = when (stage) {
        RequestStage.BEFORE_REQUEST -> 0
        RequestStage.BEFORE_SEND_HEADERS -> 1
        RequestStage.SEND_HEADERS -> 2
        RequestStage.HEADERS_RECEIVED -> 3
        RequestStage.RESPONSE_STARTED -> 4
        RequestStage.BEFORE_REDIRECT -> 5
        RequestStage.COMPLETED, RequestStage.ERROR_OCCURRED -> 6
    }

    fun trackRequest(context: ExtensionNetworkRequestContext): TrackedRequest {
        terminalRequestIds.remove(context.requestId)
        val tracked = TrackedRequest(context)
        inFlightRequests[context.requestId] = tracked
        return tracked
    }

    fun getRequest(requestId: String): TrackedRequest? {
        return inFlightRequests[requestId]
    }

    fun updateStage(requestId: String, stage: RequestStage) {
        inFlightRequests[requestId]?.let { tracked ->
            if (stageRank(stage) >= stageRank(tracked.currentStage)) tracked.currentStage = stage
        }
    }

    fun completeRequest(requestId: String): Boolean {
        if (!terminalRequestIds.add(requestId)) return false
        val req = inFlightRequests.remove(requestId)
        req?.finishTime = System.currentTimeMillis()
        req?.currentStage = RequestStage.COMPLETED
        return true
    }

    fun errorRequest(requestId: String, error: String): Boolean {
        if (!terminalRequestIds.add(requestId)) return false
        val req = inFlightRequests.remove(requestId)
        req?.finishTime = System.currentTimeMillis()
        req?.error = error
        req?.currentStage = RequestStage.ERROR_OCCURRED
        return true
    }

    fun getActiveRequestsCount(): Int {
        return inFlightRequests.size
    }

    fun clear() {
        inFlightRequests.clear()
        terminalRequestIds.clear()
    }
}
