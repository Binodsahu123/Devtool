package com.swift.browser.extensionv8

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class V8RuntimeTest {

    private fun assumeNativeV8() {
        assumeTrue(
            "Native V8 runtime library is not loaded in host JVM test environment",
            V8NativeBridge.isLoaded() && V8NativeBridge.isV8Initialized()
        )
    }

    @Test
    fun testV8Smoke_ArithmeticEvaluation() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "smoke_ext", generationId = 1, scriptPath = "sw.js")
        val initOk = worker.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})
        assertTrue("V8Worker initialization must succeed", initOk)
        val (execOk, result) = worker.executeScript("1 + 1")
        assertTrue("1 + 1 arithmetic evaluation must succeed", execOk)
        assertEquals("2", result?.toString()?.trim())
        worker.stop()
    }

    @Test
    fun testV8Worker_ServiceWorkerBootstrapScriptContract() {
        val script = V8Worker.buildDefaultBootstrapScript("test_bootstrap_ext", "{\"version\":\"1.2.3\"}")
        assertTrue("Bootstrap must define self", script.contains("global.self = global;"))
        assertTrue("Bootstrap must define globalThis", script.contains("global.globalThis = global;"))
        assertTrue("Bootstrap must define btoa", script.contains("global.btoa"))
        assertTrue("Bootstrap must define atob", script.contains("global.atob"))
        assertTrue("Bootstrap must define console", script.contains("global.console"))
        assertTrue("Bootstrap must define setTimeout", script.contains("global.setTimeout"))
        assertTrue("Bootstrap must define setInterval", script.contains("global.setInterval"))
        assertTrue("Bootstrap must define queueMicrotask", script.contains("global.queueMicrotask"))
        assertTrue("Bootstrap must define chrome.runtime", script.contains("runtime: {"))
        assertTrue("Bootstrap must define chrome.storage", script.contains("storage: {"))
        assertTrue("Bootstrap must define chrome.tabs", script.contains("tabs: {"))
        assertTrue("Bootstrap must define ExtendableEvent", script.contains("function ExtendableEvent("))
    }

    @Test
    fun testV8Worker_ChromeRuntimeId() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "test_ext_id", generationId = 1, scriptPath = "sw.js")
        val initOk = worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { _, _, _ -> },
            consoleHandler = { _, _ -> },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )
        assertTrue(initOk)

        val (ok, result) = worker.executeScript("chrome.runtime.id")
        assertTrue(ok)
        assertEquals("test_ext_id", result)
        worker.stop()
    }

    @Test
    fun testV8Worker_EventListenerAndDispatch() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "msg_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        val script = """
            globalThis.receivedMessage = null;
            chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
                globalThis.receivedMessage = msg;
            });
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)

        val dispatchOk = worker.dispatchEvent("runtime.onMessage", JSONObject().put("message", "hello_from_content_script").toString())
        assertTrue(dispatchOk)

        val (checkOk, received) = worker.executeScript("globalThis.receivedMessage")
        assertTrue(checkOk)
        assertEquals("hello_from_content_script", received)
        worker.stop()
    }

    @Test
    fun testV8Worker_PromiseResolve() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "promise_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        val script = """
            globalThis.promiseResult = null;
            Promise.resolve(42).then(val => {
                globalThis.promiseResult = val;
            });
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)

        // Drain microtasks
        worker.drainMicrotasks()

        val (checkOk, res) = worker.executeScript("globalThis.promiseResult")
        assertTrue(checkOk)
        assertEquals(42, res)
        worker.stop()
    }

    @Test
    fun testV8Worker_AsyncAwait() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "async_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        val script = """
            globalThis.asyncResult = 0;
            function computeAsync() {
                return Promise.resolve(10).then(function(a) {
                    return Promise.resolve(20).then(function(b) {
                        globalThis.asyncResult = a + b;
                    });
                });
            }
            computeAsync();
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)

        worker.drainMicrotasks()

        val (checkOk, res) = worker.executeScript("globalThis.asyncResult")
        assertTrue(checkOk)
        assertEquals(30, res)
        worker.stop()
    }

    @Test
    fun testV8Worker_SetTimeout() {
        assumeNativeV8()
        var firedTimerId = -1
        val worker = V8Worker(extensionId = "timer_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { _, _, _ -> },
            consoleHandler = { _, _ -> },
            timerSetHandler = { isInterval, delayMs, timerId ->
                firedTimerId = timerId
            },
            timerClearHandler = {}
        )

        val script = """
            globalThis.timerFired = false;
            setTimeout(() => {
                globalThis.timerFired = true;
            }, 100);
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)
        assertTrue(firedTimerId > 0)

        // Trigger the timer in JS
        worker.fireTimer(firedTimerId)

        val (checkOk, res) = worker.executeScript("globalThis.timerFired")
        assertTrue(checkOk)
        assertEquals(true, res)
        worker.stop()
    }

    @Test
    fun testV8Worker_ThrownExceptionWithStack() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "err_ext", generationId = 1, scriptPath = "buggy.js")
        worker.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        val badScript = """
            function fail() {
                throw new Error("Deliberate failure in extension script");
            }
            fail();
        """.trimIndent()

        val (execOk, errorInfo) = worker.executeScript(badScript, "buggy.js")
        assertFalse(execOk)
        assertNotNull(worker.lastException)
        assertTrue(worker.lastException!!.message.contains("Deliberate failure"))
        assertEquals(V8WorkerState.FAILED, worker.state)
        worker.stop()
    }

    @Test
    fun testV8Worker_ConsoleLog() {
        assumeNativeV8()
        val loggedMessages = mutableListOf<Pair<String, String>>()
        val worker = V8Worker(extensionId = "log_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { _, _, _ -> },
            consoleHandler = { level, message ->
                loggedMessages.add(Pair(level, message))
            },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )

        worker.executeScript("console.log('Hello from V8 extension');")
        worker.executeScript("console.warn('Warning test');")
        worker.executeScript("console.error('Error test');")

        assertEquals(3, loggedMessages.size)
        assertEquals("log", loggedMessages[0].first)
        assertEquals("Hello from V8 extension", loggedMessages[0].second)
        assertEquals("warn", loggedMessages[1].first)
        assertEquals("Warning test", loggedMessages[1].second)
        assertEquals("error", loggedMessages[2].first)
        assertEquals("Error test", loggedMessages[2].second)
        worker.stop()
    }

    @Test
    fun testV8Worker_ChromeStorageRoundTrip() {
        assumeNativeV8()
        var hostApiCalled = false
        var lastApiName = ""
        var lastReqId = ""
        var lastPayload = ""

        val worker = V8Worker(extensionId = "storage_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { apiName, reqId, payloadJson ->
                hostApiCalled = true
                lastApiName = apiName
                lastReqId = reqId
                lastPayload = payloadJson
            },
            consoleHandler = { _, _ -> },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )

        val setScript = """
            globalThis.storageSetDone = false;
            chrome.storage.local.set({ key1: 'value1' }, () => {
                globalThis.storageSetDone = true;
            });
        """.trimIndent()
        val (setOk, _) = worker.executeScript(setScript)
        assertTrue(setOk)
        assertTrue(hostApiCalled)
        assertEquals("storage.local.set", lastApiName)
        assertTrue(lastPayload.contains("value1"))

        // Resolve host call
        worker.resolveHostCall(lastReqId, "{\"status\":\"ok\"}", "")

        val (checkSetOk, setDone) = worker.executeScript("globalThis.storageSetDone")
        assertTrue(checkSetOk)
        assertEquals(true, setDone)

        // Now test storage.get
        hostApiCalled = false
        val getScript = """
            globalThis.fetchedValue = null;
            chrome.storage.local.get(['key1'], (res) => {
                globalThis.fetchedValue = res ? res.key1 : null;
            });
        """.trimIndent()
        val (getOk, _) = worker.executeScript(getScript)
        assertTrue(getOk)
        assertTrue(hostApiCalled)
        assertEquals("storage.local.get", lastApiName)

        // Resolve storage.get with data
        worker.resolveHostCall(lastReqId, "{\"key1\":\"value1\"}", "")

        val (checkGetOk, fetched) = worker.executeScript("globalThis.fetchedValue")
        assertTrue(checkGetOk)
        assertEquals("value1", fetched)
        worker.stop()
    }

    @Test
    fun testV8Worker_ChromeRuntimeSendMessageRoundTrip() {
        assumeNativeV8()
        var hostApiCalled = false
        var lastReqId = ""

        val worker = V8Worker(extensionId = "msg_roundtrip_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { apiName, reqId, payloadJson ->
                if (apiName == "runtime.sendMessage") {
                    hostApiCalled = true
                    lastReqId = reqId
                }
            },
            consoleHandler = { _, _ -> },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )

        val script = """
            globalThis.reply = null;
            chrome.runtime.sendMessage({ greeting: "hello" }, (response) => {
                globalThis.reply = response ? response.farewell : null;
            });
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)
        assertTrue(hostApiCalled)

        // Host responds to sendMessage
        worker.resolveHostCall(lastReqId, "{\"farewell\":\"goodbye\"}", "")

        val (checkOk, reply) = worker.executeScript("globalThis.reply")
        assertTrue(checkOk)
        assertEquals("goodbye", reply)
        worker.stop()
    }

    @Test
    fun testV8Worker_ServiceWorkerToContentScriptMessage() {
        assumeNativeV8()
        var tabsSendMessageCalled = false
        var lastPayload = ""

        val worker = V8Worker(extensionId = "tab_msg_ext", generationId = 1, scriptPath = "sw.js")
        worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { apiName, reqId, payloadJson ->
                if (apiName == "tabs.sendMessage") {
                    tabsSendMessageCalled = true
                    lastPayload = payloadJson
                }
            },
            consoleHandler = { _, _ -> },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )

        val script = """
            chrome.tabs.sendMessage(101, { action: "highlight", color: "yellow" });
        """.trimIndent()
        val (execOk, _) = worker.executeScript(script)
        assertTrue(execOk)
        assertTrue(tabsSendMessageCalled)
        assertTrue(lastPayload.contains("highlight"))
        assertTrue(lastPayload.contains("101"))
        worker.stop()
    }

    @Test
    fun testV8Worker_EsModuleBootstrap() {
        assumeNativeV8()
        val worker = V8Worker(extensionId = "module_ext", generationId = 1, scriptPath = "entry.js", isModule = true)
        val initOk = worker.initialize(
            bootstrapScript = "",
            hostApiHandler = { _, _, _ -> },
            consoleHandler = { _, _ -> },
            timerSetHandler = { _, _, _ -> },
            timerClearHandler = {}
        )
        assertTrue("Module worker initialization must succeed", initOk)
        val (execOk, _) = worker.executeScript("globalThis.modReady = true;")
        assertTrue("Module execution must succeed", execOk)
        worker.stop()
    }

    @Test
    fun testV8Worker_CrashIsolation() {
        assumeNativeV8()
        val workerA = V8Worker(extensionId = "crash_isolate_a", generationId = 1, scriptPath = "swA.js")
        val workerB = V8Worker(extensionId = "crash_isolate_b", generationId = 1, scriptPath = "swB.js")
        workerA.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})
        workerB.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        val (execFail, _) = workerA.executeScript("throw new Error('crash');")
        assertFalse(execFail)
        assertEquals(V8WorkerState.FAILED, workerA.state)

        val (execOk, _) = workerB.executeScript("globalThis.healthy = true;")
        assertTrue(execOk)
        assertEquals(V8WorkerState.RUNNING, workerB.state)

        workerA.stop()
        workerB.stop()
    }

    @Test
    fun testV8Worker_MultipleIsolateIndependence() {
        assumeNativeV8()
        val workerA = V8Worker(extensionId = "ext_a", generationId = 1, scriptPath = "swA.js")
        val workerB = V8Worker(extensionId = "ext_b", generationId = 1, scriptPath = "swB.js")

        workerA.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})
        workerB.initialize("", { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, {})

        workerA.executeScript("globalThis.secretKey = 'A_SECRET';")
        workerB.executeScript("globalThis.secretKey = 'B_SECRET';")

        val (_, resA) = workerA.executeScript("globalThis.secretKey")
        val (_, resB) = workerB.executeScript("globalThis.secretKey")

        assertEquals("A_SECRET", resA)
        assertEquals("B_SECRET", resB)

        val (_, idA) = workerA.executeScript("chrome.runtime.id")
        val (_, idB) = workerB.executeScript("chrome.runtime.id")

        assertEquals("ext_a", idA)
        assertEquals("ext_b", idB)

        workerA.stop()
        workerB.stop()
    }

    @Test
    fun testV8WorkerManager_LifecycleAndWorkerThread() {
        assumeNativeV8()
        val context = RuntimeEnvironment.getApplication()
        val manager = V8WorkerManager(context)
        val startupLatch = CountDownLatch(1)
        val startupSuccess = AtomicBoolean(false)

        manager.startWorker(
            extensionId = "worker_mgr_test",
            scriptPath = "background.js",
            generationId = 1,
            bootstrapScript = "",
            scriptContent = "globalThis.isReady = true;",
            isModule = false,
            isPrivate = false,
            onStartupResult = { ok, _ ->
                startupSuccess.set(ok)
                startupLatch.countDown()
            }
        )

        assertTrue(startupLatch.await(5, TimeUnit.SECONDS))
        assertTrue(startupSuccess.get())
        assertTrue(manager.isWorkerRunning("worker_mgr_test"))
        assertEquals(1, manager.getWorkerGeneration("worker_mgr_test"))

        val evalLatch = CountDownLatch(1)
        val evalSuccess = AtomicBoolean(false)
        manager.evaluateEvent(
            extensionId = "worker_mgr_test",
            generationId = 1,
            eventName = "runtime.onInstalled",
            payload = JSONObject().put("reason", "install")
        ) { ok ->
            evalSuccess.set(ok)
            evalLatch.countDown()
        }

        assertTrue(evalLatch.await(5, TimeUnit.SECONDS))
        assertTrue(evalSuccess.get())

        manager.stopWorker("worker_mgr_test")
        assertFalse(manager.isWorkerRunning("worker_mgr_test"))
    }

    @Test
    fun testV8Runtime_LazyInitializationSafety() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = V8Runtime(context)
        // Creating V8Runtime should not throw and should report current bridge state
        assertNotNull(runtime.backendStatus())
        assertNotNull(runtime.getV8Version())
        assertNotNull(runtime.getHeaderVersion())
    }

    @Test
    fun testV8NativeBridge_StateLifecycleConsistency() {
        val state = V8NativeBridge.getV8State()
        assertNotNull("V8 state must not be null", state)
        // Verify state is one of the valid enum values
        assertTrue(
            state in listOf(
                V8BridgeState.NOT_LOADED,
                V8BridgeState.LOADING,
                V8BridgeState.LOADED,
                V8BridgeState.ABI_INVALID,
                V8BridgeState.INITIALIZING,
                V8BridgeState.READY,
                V8BridgeState.FAILED
            )
        )
    }

    @Test
    fun testV8NativeBridge_DiagnosticMethodsSafeWithoutCrash() {
        assertNotNull(V8NativeBridge.getV8Version())
        assertNotNull(V8NativeBridge.getRuntimeVersion())
        assertNotNull(V8NativeBridge.getHeaderVersion())
        assertNotNull(V8NativeBridge.getV8GitRevision())
        assertNotNull(V8NativeBridge.getV8BuildInfo())
        assertFalse(V8NativeBridge.createWorker("dummy", 1, "test.js", "", false, ""))
        assertFalse(V8NativeBridge.executeScript("dummy", 1, "1+1"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", 1, "test", "{}"))
        assertFalse(V8NativeBridge.stopWorker("dummy"))
        assertEquals(0L, V8NativeBridge.getHeapUsage("dummy"))
    }

    @Test
    fun testV8NativeBridge_RejectsOperationsWhenNotReady() {
        // In Robolectric without native libraries loaded, state is NOT_LOADED or FAILED
        assertNotEquals(V8BridgeState.READY, V8NativeBridge.getV8State())
        assertFalse(V8NativeBridge.isAvailable)
        assertFalse(V8NativeBridge.createWorker("dummy", 1, "test.js", "", false, ""))
        assertFalse(V8NativeBridge.executeScript("dummy", 1, "1+1"))
        assertNull(V8NativeBridge.executeScriptResult("dummy", 1, "1+1"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", 1, "onEvent", "{}"))
        assertFalse(V8NativeBridge.fireTimer("dummy", 1, 42))
        assertFalse(V8NativeBridge.resolveHostApiCall("dummy", 1, "req-1", "{}", ""))
        assertFalse(V8NativeBridge.stopWorker("dummy"))
        V8NativeBridge.stopAllWorkers() // must not throw
        assertEquals(0L, V8NativeBridge.getHeapUsage("dummy"))
        assertEquals("UNAVAILABLE", V8NativeBridge.getV8Version())
        assertEquals("UNAVAILABLE", V8NativeBridge.getV8GitRevision())
        assertEquals("UNAVAILABLE", V8NativeBridge.getV8BuildInfo())
    }

    @Test
    fun testV8NativeBridge_RejectsInvalidLifecycleParameters() {
        assertFalse(V8NativeBridge.createWorker("", 1, "test.js", "", false, ""))
        assertFalse(V8NativeBridge.createWorker("   ", 1, "test.js", "", false, ""))
        assertFalse(V8NativeBridge.createWorker("dummy", 0, "test.js", "", false, ""))
        assertFalse(V8NativeBridge.createWorker("dummy", -1, "test.js", "", false, ""))

        assertFalse(V8NativeBridge.executeScript("", 1, "1+1"))
        assertFalse(V8NativeBridge.executeScript("dummy", 0, "1+1"))
        assertFalse(V8NativeBridge.executeScript("dummy", -1, "1+1"))

        assertNull(V8NativeBridge.executeScriptResult("", 1, "1+1"))
        assertNull(V8NativeBridge.executeScriptResult("dummy", 0, "1+1"))
        assertNull(V8NativeBridge.executeScriptResult("dummy", -1, "1+1"))

        assertFalse(V8NativeBridge.dispatchEvent("", 1, "ev", "{}"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", 0, "ev", "{}"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", -1, "ev", "{}"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", 1, "", "{}"))
        assertFalse(V8NativeBridge.dispatchEvent("dummy", 1, "   ", "{}"))

        assertFalse(V8NativeBridge.fireTimer("", 1, 1))
        assertFalse(V8NativeBridge.fireTimer("dummy", 0, 1))
        assertFalse(V8NativeBridge.fireTimer("dummy", -1, 1))
        assertFalse(V8NativeBridge.fireTimer("dummy", 1, 0))
        assertFalse(V8NativeBridge.fireTimer("dummy", 1, -1))

        assertFalse(V8NativeBridge.resolveHostApiCall("", 1, "r1", "{}", ""))
        assertFalse(V8NativeBridge.resolveHostApiCall("dummy", 0, "r1", "{}", ""))
        assertFalse(V8NativeBridge.resolveHostApiCall("dummy", -1, "r1", "{}", ""))
        assertFalse(V8NativeBridge.resolveHostApiCall("dummy", 1, "", "{}", ""))
        assertFalse(V8NativeBridge.resolveHostApiCall("dummy", 1, "   ", "{}", ""))

        assertFalse(V8NativeBridge.stopWorker(""))
        assertFalse(V8NativeBridge.stopWorker("   "))
        assertFalse(V8NativeBridge.stopWorker("dummy", 0))
        assertFalse(V8NativeBridge.stopWorker("dummy", -1))

        assertEquals(0L, V8NativeBridge.getHeapUsage(""))
        assertEquals(0L, V8NativeBridge.getHeapUsage("   "))
        assertEquals(0L, V8NativeBridge.getHeapUsage("dummy", 0))
        assertEquals(0L, V8NativeBridge.getHeapUsage("dummy", -1))
    }

    @Test
    fun testV8WorkerManager_RejectsOperationsWhenBridgeNotReady() {
        val context = RuntimeEnvironment.getApplication()
        val manager = V8WorkerManager(context)

        var startupResult: Pair<Boolean, String?>? = null
        manager.startWorker(
            extensionId = "test_ext",
            scriptPath = "bg.js",
            generationId = 1,
            bootstrapScript = "",
            scriptContent = "",
            onStartupResult = { ok, err -> startupResult = Pair(ok, err) }
        )
        assertNotNull(startupResult)
        assertFalse(startupResult!!.first)
        assertTrue(startupResult!!.second?.contains("Native V8 bridge is not ready") == true)

        var eventDispatched = true
        manager.evaluateEvent("test_ext", 1, "onTest", "{}") { ok -> eventDispatched = ok }
        assertFalse(eventDispatched)

        assertFalse(manager.scheduleNativeTimer("test_ext", 1, false, 100, 1))
        assertFalse(manager.clearNativeTimer("test_ext", 1, 1))

        var hostCallDelivered = true
        manager.resolveHostCall("test_ext", 1, "req1", "{}", "") { delivered -> hostCallDelivered = delivered }
        assertFalse(hostCallDelivered)

        assertEquals(0L, manager.getHeapUsage("test_ext"))
        manager.stopWorker("test_ext")
        manager.stopAll()
    }
}
