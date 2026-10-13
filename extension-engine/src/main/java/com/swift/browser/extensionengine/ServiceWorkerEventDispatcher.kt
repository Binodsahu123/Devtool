package com.swift.browser.extensionengine

import org.json.JSONObject
import java.util.UUID

/**
 * Browser-originated MV3 event entry point.
 *
 * This class deliberately has no MessageBus/PortListener implementation. MessageBus delivery to
 * active worker/UI contexts is owned by ExtensionMessageRouter/V8ServiceWorkerBackend. Keeping
 * those responsibilities out of this dispatcher prevents double delivery and duplicate lifecycle
 * state transitions.
 */
class ServiceWorkerEventDispatcher(
    private val wakeController: ServiceWorkerWakeController
) {
    fun dispatchRuntimeEvent(
        extensionId: String,
        eventName: String,
        payload: JSONObject = JSONObject()
    ) {
        val event = QueuedServiceWorkerEvent(
            eventId = "${eventName}_${System.currentTimeMillis()}_${UUID.randomUUID()}",
            eventName = eventName,
            payload = payload
        )
        wakeController.wakeAndExecute(extensionId, event)
    }

    fun dispatchPortConnectEvent(
        extensionId: String,
        channelId: String,
        portName: String,
        senderId: String
    ) {
        val payload = JSONObject().apply {
            put("channelId", channelId)
            put("name", portName)
            put("sender", JSONObject().put("id", senderId))
        }
        dispatchRuntimeEvent(extensionId, "runtime.onConnect", payload)
    }

    fun dispatchAlarmEvent(extensionId: String, alarmName: String, scheduledTime: Long) {
        val payload = JSONObject().apply {
            put("name", alarmName)
            put("scheduledTime", scheduledTime)
        }
        dispatchRuntimeEvent(extensionId, "alarms.onAlarm", payload)
    }
}
