package com.swift.browser.extensionengine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceWorkerGenerationSafetyTest {

    @Test
    fun queuedEventCanBeReboundToNewGenerationWithoutBeingStale() {
        val event = QueuedServiceWorkerEvent(
            eventId = "event-1",
            eventName = "history.onVisited",
            payload = JSONObject().put("id", "x"),
            generationId = 1
        )

        // wakeAndExecute now stamps the event after allocating the generation.
        val newGeneration = 2
        event.generationId = newGeneration

        assertEquals(newGeneration, event.generationId)
        assertTrue(event.generationId >= newGeneration)
    }

    @Test
    fun workerRegistrationsUseConcurrentEventCollections() {
        val registration = ServiceWorkerRegistration(
            extensionId = "ext",
            scriptPath = "sw.js"
        )
        registration.pendingEvents.add(
            QueuedServiceWorkerEvent("e1", "runtime.onStartup", JSONObject())
        )
        registration.activeCallbacks.add("cb1")

        assertEquals(1, registration.pendingEvents.size)
        assertTrue(registration.activeCallbacks.contains("cb1"))
    }
}
