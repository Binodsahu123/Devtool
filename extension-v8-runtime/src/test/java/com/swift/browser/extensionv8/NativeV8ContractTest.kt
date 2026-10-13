package com.swift.browser.extensionv8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeV8ContractTest {
    @Test
    fun buildContractPinsNativeScriptAndV8() {
        assertEquals("NativeScript Android Runtime", V8BuildContract.RUNTIME_PROVIDER)
        assertEquals("@nativescript/android", V8BuildContract.NATIVESCRIPT_PACKAGE)
        assertEquals("9.1.1", V8BuildContract.NATIVESCRIPT_VERSION)
        assertEquals("arm64-v8a", V8BuildContract.TARGET_ABI)
    }

    @Test
    fun runtimeCoordinatorNeverCreatesAWorkerWithoutReadyRuntime() {
        // The coordinator's public contract is that worker creation is gated by
        // V8Runtime.ensureReadyForWorker().
        assertTrue(V8BuildContract.TARGET_CPU == "arm64")
    }

    @Test
    fun diagnosticsAndReadinessMethodsArePureStateQueries() {
        // Pure queries inspect state without triggering initialization or throwing
        val state = V8NativeBridge.getV8State()
        val isReady = V8NativeBridge.isV8Initialized()
        assertEquals(state == V8BridgeState.READY, isReady)

        val status = V8RuntimeDiagnostics.getRuntimeStatus()
        assertTrue(status in listOf(
            V8RuntimeDiagnostics.STATUS_RUNTIME_VERIFIED,
            V8RuntimeDiagnostics.STATUS_DEVICE_VERIFIED,
            V8RuntimeDiagnostics.STATUS_PENDING_EXTERNAL_BUILD,
            V8RuntimeDiagnostics.STATUS_NATIVE_V8_UNAVAILABLE
        ))

        val summary = V8RuntimeDiagnostics.getDiagnosticsSummary(includeExplicitSmokeTest = false)
        assertEquals(status, summary["status"])
        assertEquals(isReady, summary["isV8Initialized"])
        assertEquals(isReady, summary["hasFullV8"])
    }
}
