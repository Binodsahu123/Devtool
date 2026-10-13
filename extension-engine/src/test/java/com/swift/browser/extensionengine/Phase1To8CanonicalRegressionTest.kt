package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1To8CanonicalRegressionTest {
    private val events = mutableListOf<String>()
    private val machine = WebNavigationStateMachine { name, _, _, _ -> events += name }

    @Test
    fun browserOwnsDocumentIdentityAndPreservesFrameIdAcrossNavigation() {
        val top = machine.registerProviderFrame("tab-1", "top-token", null, "https://example.test/", true, false, "doc-token-1")
        val child = machine.registerProviderFrame("tab-1", "child-token", "top-token", "https://example.test/frame", false, false, "child-doc-1")

        assertEquals(0, top.frameId)
        assertEquals(1, child.frameId)
        assertEquals(0, child.parentFrameId)
        assertTrue(top.documentId!!.startsWith("swift-document-"))
        assertNotEquals("doc-token-1", top.documentId)

        val firstDocument = child.documentId
        val navigated = machine.registerProviderFrame("tab-1", "child-token", "top-token", "https://example.test/frame-2", false, false, "child-doc-2")
        assertEquals(child.frameId, navigated.frameId)
        assertNotEquals(firstDocument, navigated.documentId)
        assertTrue(machine.getFrameIdentity("tab-1", navigated.frameId)?.documentId != firstDocument)
    }

    @Test
    fun staleChildDestroyCannotRemoveReplacementDocument() {
        val first = machine.registerProviderFrame("tab-2", "top", null, "https://example.test/", true, false, "top-1")
        val child1 = machine.registerProviderFrame("tab-2", "child", "top", "https://example.test/a", false, false, "child-1")
        val child2 = machine.registerProviderFrame("tab-2", "child", "top", "https://example.test/b", false, false, "child-2")

        assertEquals(child1.frameId, child2.frameId)
        assertTrue(!machine.removeFrameByProviderToken("tab-2", "child", "child-1"))
        assertEquals(child2.documentId, machine.getFrameIdentity("tab-2", child2.frameId)?.documentId)
        assertEquals(first.frameId, 0)
    }

    @Test
    fun targetContractRejectsChromeConflicts() {
        val allFramesAndFrameIds = JSONObject().put("tabId", "tab-3").put("allFrames", true).put("frameIds", JSONArray().put(1))
        val allFramesAndDocs = JSONObject().put("tabId", "tab-3").put("allFrames", true).put("documentIds", JSONArray().put("d1"))
        val frameAndDocs = JSONObject().put("tabId", "tab-3").put("frameIds", JSONArray().put(1)).put("documentIds", JSONArray().put("d1"))

        assertThrows(IllegalArgumentException::class.java) { ScriptTargetContract.parse(allFramesAndFrameIds) }
        assertThrows(IllegalArgumentException::class.java) { ScriptTargetContract.parse(allFramesAndDocs) }
        assertThrows(IllegalArgumentException::class.java) { ScriptTargetContract.parse(frameAndDocs) }
    }
}
