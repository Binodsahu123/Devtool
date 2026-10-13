package com.swift.browser.extensionv8

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoggingV8RuntimeDecoratorTest {

    private class FakeV8Runtime(
        var initResult: Boolean = true,
        var readyResult: Boolean = true,
        var initShouldThrow: Boolean = false,
        var readyShouldThrow: Boolean = false
    ) : V8RuntimeInterface {
        var shutdownCalled = false
        override val workerManager: V8WorkerManager
            get() = throw UnsupportedOperationException("Fake runtime workerManager not needed")
        override val config: V8RuntimeConfig = V8RuntimeConfig()

        override fun ensureInitialized(): Boolean {
            if (initShouldThrow) error("Simulated JNI Init Failure")
            return initResult
        }

        override fun ensureReadyForWorker(): Boolean {
            if (readyShouldThrow) error("Simulated Native Ready Failure")
            return readyResult
        }

        override fun hasOrionNativeV8(): Boolean = initResult
        override fun isNativeV8Available(): Boolean = initResult
        override fun backendStatus(): String = if (initResult) "NATIVE_V8_READY" else "NATIVE_V8_FAILED"
        override fun isV8Supported(): Boolean = true
        override fun isV8Ready(): Boolean = initResult
        override fun isV8BackendAvailable(): Boolean = initResult
        override fun getV8Version(): String = "12.0.0.0"
        override fun getRuntimeVersion(): String = "1.0.0"
        override fun getHeaderVersion(): String = "12.0.0.0"
        override fun getV8GitRevision(): String = "abc1234"
        override fun getV8BuildInfo(): String = "FakeBuild"
        override fun shutdown() {
            shutdownCalled = true
        }
    }

    @Test
    fun testInitializationSuccess_LogsAndTracksMetrics() {
        val fake = FakeV8Runtime(initResult = true, readyResult = true)
        val decorator = fake.withLogging("TestV8Log")

        assertTrue("ensureInitialized should return true", decorator.ensureInitialized())
        assertEquals(1L, decorator.totalInitAttempts)
        assertEquals(1L, decorator.successfulInits)
        assertEquals(0L, decorator.failedInits)

        assertTrue("ensureReadyForWorker should return true", decorator.ensureReadyForWorker())
        assertEquals(1L, decorator.totalWorkerReadyAttempts)
        assertEquals(1L, decorator.successfulWorkerReadyChecks)
        assertEquals(0L, decorator.failedWorkerReadyChecks)

        val metrics = decorator.getStabilityMetrics()
        assertEquals(1L, metrics["initAttempts"])
        assertEquals(1L, metrics["initSuccesses"])
        assertEquals(0L, metrics["initFailures"])
        assertEquals("NATIVE_V8_READY", metrics["backendStatus"])
    }

    @Test
    fun testInitializationFailure_LogsAndTracksFailureMetrics() {
        val fake = FakeV8Runtime(initResult = false, readyResult = false)
        val decorator = LoggingV8RuntimeDecorator(fake)

        assertFalse("ensureInitialized should return false", decorator.ensureInitialized())
        assertEquals(1L, decorator.totalInitAttempts)
        assertEquals(0L, decorator.successfulInits)
        assertEquals(1L, decorator.failedInits)

        assertFalse("ensureReadyForWorker should return false", decorator.ensureReadyForWorker())
        assertEquals(1L, decorator.totalWorkerReadyAttempts)
        assertEquals(0L, decorator.successfulWorkerReadyChecks)
        assertEquals(1L, decorator.failedWorkerReadyChecks)

        val metrics = decorator.getStabilityMetrics()
        assertEquals(1L, metrics["initFailures"])
        assertEquals(1L, metrics["workerReadyFailures"])
        assertEquals("NATIVE_V8_FAILED", metrics["backendStatus"])
    }

    @Test
    fun testInitializationException_CaughtAndTrackedAsFailure() {
        val fake = FakeV8Runtime(initShouldThrow = true, readyShouldThrow = true)
        val decorator = LoggingV8RuntimeDecorator(fake)

        assertFalse("Exception during init should safely return false", decorator.ensureInitialized())
        assertEquals(1L, decorator.failedInits)

        assertFalse("Exception during readiness check should safely return false", decorator.ensureReadyForWorker())
        assertEquals(1L, decorator.failedWorkerReadyChecks)
    }

    @Test
    fun testShutdownDelegation() {
        val fake = FakeV8Runtime()
        val decorator = LoggingV8RuntimeDecorator(fake)

        decorator.shutdown()
        assertTrue("Shutdown must be delegated to fake runtime", fake.shutdownCalled)
    }
}
