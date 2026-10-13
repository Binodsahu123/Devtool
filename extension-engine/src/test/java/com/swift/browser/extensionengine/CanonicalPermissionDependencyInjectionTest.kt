package com.swift.browser.extensionengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanonicalPermissionDependencyInjectionTest {

    @Test
    fun testExplicitCanonicalPermissionAdapterInjection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionAdapter = ExtensionPermissionAdapter(context)
        val registry = ExtensionRegistryImpl()
        val messageBus = MessageBus()
        val eventManager = EventManager(messageBus)
        val dnrAdapter = ExtensionDnrAdapter(permissionAdapter, registry)
        val webRequestAdapter = ExtensionWebRequestAdapter(permissionAdapter, registry, eventManager, dnrAdapter)
        val scriptInjector = ScriptInjector()
        val cssInjector = CssInjector()
        val permissionManager = PermissionManager(context, permissionAdapter)
        val contentScriptManager = ContentScriptManager(context, permissionManager, scriptInjector, cssInjector, registry)
        val worldAwareScriptExecutor = WorldAwareScriptExecutor()
        val scriptingAdapter = ExtensionScriptingAdapter(permissionAdapter, registry, contentScriptManager, scriptInjector, cssInjector, worldAwareScriptExecutor, permissionManager)

        val swRegistry = ServiceWorkerRegistry()
        val lifecyclePolicy = Mv3ServiceWorkerLifecycleCoordinator(swRegistry)
        val wakeController = ServiceWorkerWakeController(
            serviceWorkerRegistry = swRegistry,
            executionBackend = V8ServiceWorkerBackend(context),
            extensionRegistry = registry,
            bootstrapScriptProvider = { "" },
            permissionAdapter = permissionAdapter,
            lifecyclePolicy = lifecyclePolicy
        )
        val shutdownController = ServiceWorkerShutdownController(
            serviceWorkerRegistry = swRegistry,
            messageBus = messageBus,
            lifecyclePolicy = lifecyclePolicy
        )
        val swLifecycleManager = ServiceWorkerLifecycleManager(
            serviceWorkerRegistry = swRegistry,
            wakeController = wakeController,
            shutdownController = shutdownController,
            extensionRegistry = registry,
            permissionAdapter = permissionAdapter,
            eventManager = eventManager
        )

        assertSame("DnrAdapter must use the canonical permissionAdapter", permissionAdapter, dnrAdapter.permissionAdapter)
        assertSame("WebRequestAdapter must use the canonical permissionAdapter", permissionAdapter, webRequestAdapter.permissionAdapter)
        assertSame("ScriptingAdapter must use the canonical permissionAdapter", permissionAdapter, scriptingAdapter.permissionAdapter)
        assertSame("ServiceWorkerLifecycleManager must use the canonical permissionAdapter", permissionAdapter, swLifecycleManager.permissionAdapter)
    }

    @Test
    fun testExtensionEngineImplInjectsCanonicalPermissionAdapter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExtensionEngineImpl(context, initialDelegate = null)

        val canonicalPermissionAdapter = engine.permissionAdapter

        assertSame(
            "ExtensionEngineImpl must inject canonical permissionAdapter into dnrAdapter",
            canonicalPermissionAdapter,
            engine.dnrAdapter.permissionAdapter
        )
        assertSame(
            "ExtensionEngineImpl must inject canonical permissionAdapter into webRequestAdapter",
            canonicalPermissionAdapter,
            engine.webRequestAdapter.permissionAdapter
        )
        assertSame(
            "ExtensionEngineImpl must inject canonical permissionAdapter into scriptingAdapter",
            canonicalPermissionAdapter,
            engine.scriptingAdapter.permissionAdapter
        )
        assertSame(
            "ExtensionEngineImpl must inject canonical permissionAdapter into swLifecycleManager",
            canonicalPermissionAdapter,
            engine.swLifecycleManager.permissionAdapter
        )

        val bridge = engine.createBridge(webView = null)
        assertSame(
            "ExtensionEngineImpl must inject canonical storageBackend into RuntimeBridge",
            engine.storageBackend,
            bridge.storageBackend
        )
        assertSame(
            "ExtensionEngineImpl must inject canonical coreServices into RuntimeBridge",
            engine.coreServices,
            bridge.coreServices
        )
    }
}
