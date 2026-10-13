package com.swift.browser.extensionengine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtensionPart7Part8PortAndWorkerHardeningTest {

    private var activePortListener: PortConnectionListener? = null

    @Test
    fun portMessageRoutesOnlyToOppositeEndpointMetadata() {
        val bus = MessageBus()
        val manager = PortManager(bus)
        val received = mutableListOf<List<String?>>()

        val listener = object : PortConnectionListener {
            override fun onPortConnect(
                extensionId: String,
                channelId: String,
                portName: String,
                senderId: String,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) = Unit

            override fun onPortMessage(
                channelId: String,
                message: JSONObject,
                targetExtensionId: String?,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) {
                received += listOf(
                    channelId,
                    targetExtensionId,
                    targetContextType?.name,
                    targetTabId,
                    targetFrameId?.toString()
                )
            }

            override fun onPortDisconnect(
                channelId: String,
                targetExtensionId: String?,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) = Unit
        }
        activePortListener = listener
        bus.registerPortListener(listener)

        val sender = ExtensionSender(
            extensionId = "sender_ext",
            tabId = "tab-1",
            frameId = 2,
            contextType = ExtensionContextType.CONTENT_SCRIPT
        )

        manager.connect(
            extensionId = "target_ext",
            channelId = "channel-1",
            portName = "test",
            senderId = sender.extensionId,
            sender = sender,
            targetContextType = ExtensionContextType.SERVICE_WORKER
        )

        manager.postMessage("channel-1", JSONObject().put("hello", "world"), "sender_ext")

        assertEquals("Expected 1 received message but got size ${received.size}: $received", 1, received.size)
        assertEquals("target_ext", received[0][1])
        assertEquals("SERVICE_WORKER", received[0][2])
    }

    @Test
    fun portMessageRejectsUnknownSender() {
        val bus = MessageBus()
        val manager = PortManager(bus)
        var delivered = false

        bus.registerPortListener(object : PortConnectionListener {
            override fun onPortConnect(
                extensionId: String,
                channelId: String,
                portName: String,
                senderId: String,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) = Unit

            override fun onPortMessage(
                channelId: String,
                message: JSONObject,
                targetExtensionId: String?,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) {
                delivered = true
            }

            override fun onPortDisconnect(
                channelId: String,
                targetExtensionId: String?,
                targetContextType: ExtensionContextType?,
                targetTabId: String?,
                targetFrameId: Int?,
                targetDocumentId: String?
            ) = Unit
        })

        manager.connect(
            extensionId = "target_ext",
            channelId = "channel-2",
            portName = "test",
            senderId = "sender_ext",
            sender = ExtensionSender(extensionId = "sender_ext")
        )

        manager.postMessage("channel-2", JSONObject(), "unknown_ext")
        assertFalse(delivered)
    }

    @Test
    fun serviceWorkerGenerationSurvivesUnregisterAndReregister() {
        val registry = ServiceWorkerRegistry()
        val first = registry.register("abcdefghijklmnopqrstuvwxzyabcdef", "worker.js", true)
        assertEquals(1, first.workerGenerationId)

        registry.unregister(first.extensionId)

        val second = registry.register(first.extensionId, "worker.js", true)
        assertEquals(2, second.workerGenerationId)
    }
}
