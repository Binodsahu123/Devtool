package com.swift.browser.extensionengine

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ServiceWorkerBackendTest {

    @Test
    fun testBackendSelector() {
        val context = RuntimeEnvironment.getApplication()
        val scriptInjector = ScriptInjector()
        val messageBus = MessageBus()
        val swJsRuntime = ServiceWorkerJsRuntime(context, scriptInjector, messageBus)
        val webViewBackend = WebViewServiceWorkerBackend(swJsRuntime)
        val v8Backend = V8ServiceWorkerBackend(context)

        val selector = ServiceWorkerBackendSelector(context, webViewBackend, v8Backend)

        val mv3Extension = ParsedExtension(
            id = "mv3_test",
            name = "MV3 Test",
            version = "1.0.0",
            manifestVersion = 3,
            isServiceWorker = true
        )

        val selectedBackend = selector.selectBackendForExtension(mv3Extension)
        assertNotNull(selectedBackend)
    }

    @Test
    fun testBackendSelector_MV2Extension_AlwaysSelectsWebView() {
        val context = RuntimeEnvironment.getApplication()
        val scriptInjector = ScriptInjector()
        val messageBus = MessageBus()
        val swJsRuntime = ServiceWorkerJsRuntime(context, scriptInjector, messageBus)
        val webViewBackend = WebViewServiceWorkerBackend(swJsRuntime)
        val v8Backend = V8ServiceWorkerBackend(context)

        val selector = ServiceWorkerBackendSelector(context, webViewBackend, v8Backend)

        val mv2Extension = ParsedExtension(
            id = "mv2_test",
            name = "MV2 Test",
            version = "1.0.0",
            manifestVersion = 2,
            isServiceWorker = false
        )

        val selectedBackend = selector.selectLegacyBackendForMv2(mv2Extension)
        assertEquals("MV2 extensions requiring DOM must use WebView backend", webViewBackend, selectedBackend)
    }

    @Test
    fun testBackendSelector_WhenV8Null_RejectsMV3WithoutFallback() {
        val context = RuntimeEnvironment.getApplication()
        val scriptInjector = ScriptInjector()
        val messageBus = MessageBus()
        val swJsRuntime = ServiceWorkerJsRuntime(context, scriptInjector, messageBus)
        val webViewBackend = WebViewServiceWorkerBackend(swJsRuntime)

        val selector = ServiceWorkerBackendSelector(context, webViewBackend, null)

        val mv3Extension = ParsedExtension(
            id = "mv3_test_no_v8",
            name = "MV3 Test",
            version = "1.0.0",
            manifestVersion = 3,
            isServiceWorker = true
        )

        assertThrows(IllegalStateException::class.java) {
            selector.selectBackendForExtension(mv3Extension)
        }
    }

    @Test
    fun testV8ServiceWorkerBackend_IsLazyOnConstruction() {
        val context = RuntimeEnvironment.getApplication()
        val backend = V8ServiceWorkerBackend(context)
        // Construction must be completely lazy: V8Runtime should NOT be instantiated yet
        assertFalse("V8Runtime must not be instantiated simply by creating the backend", backend.isRuntimeInstantiated())
    }

    @Test
    fun testBackendSelector_MV3RejectsMV2Input() {
        val context = RuntimeEnvironment.getApplication()
        val scriptInjector = ScriptInjector()
        val messageBus = MessageBus()
        val swJsRuntime = ServiceWorkerJsRuntime(context, scriptInjector, messageBus)
        val webViewBackend = WebViewServiceWorkerBackend(swJsRuntime)
        val v8Backend = V8ServiceWorkerBackend(context)
        val selector = ServiceWorkerBackendSelector(context, webViewBackend, v8Backend)

        val mv2Extension = ParsedExtension(
            id = "mv2_rejected",
            name = "MV2",
            version = "1.0.0",
            manifestVersion = 2,
            isServiceWorker = false
        )

        assertThrows(IllegalArgumentException::class.java) {
            selector.selectBackendForExtension(mv2Extension)
        }
    }

}
