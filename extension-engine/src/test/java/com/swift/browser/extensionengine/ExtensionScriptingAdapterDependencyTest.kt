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
class ExtensionScriptingAdapterDependencyTest {

    @Test
    fun testScriptingAdapterUsesInjectedInjectorsAndExecutor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionAdapter = ExtensionPermissionAdapter(context)
        val registry = ExtensionRegistryImpl()
        val scriptInjector = ScriptInjector()
        val cssInjector = CssInjector()
        val worldAwareScriptExecutor = WorldAwareScriptExecutor()
        val permissionManager = PermissionManager(context, permissionAdapter)
        val contentScriptManager = ContentScriptManager(
            context = context,
            permissionManager = permissionManager,
            scriptInjector = scriptInjector,
            cssInjector = cssInjector,
            extensionRegistry = registry
        )

        val adapter = ExtensionScriptingAdapter(
            permissionAdapter = permissionAdapter,
            registry = registry,
            contentScriptManager = contentScriptManager,
            scriptInjector = scriptInjector,
            cssInjector = cssInjector,
            worldAwareScriptExecutor = worldAwareScriptExecutor,
            permissionManager = permissionManager
        )

        assertSame("ScriptInjector must be the injected canonical instance", scriptInjector, adapter.scriptInjector)
        assertSame("CssInjector must be the injected canonical instance", cssInjector, adapter.cssInjector)
        assertSame("WorldAwareScriptExecutor must be the injected canonical instance", worldAwareScriptExecutor, adapter.worldAwareScriptExecutor)
        assertSame("PermissionManager must be the injected canonical instance", permissionManager, adapter.permissionManager)
    }

    @Test
    fun testUserScriptsAdapterUsesInjectedExecutor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionAdapter = ExtensionPermissionAdapter(context)
        val permissionManager = PermissionManager(context, permissionAdapter)
        val registry = ExtensionRegistryImpl()
        val worldAwareScriptExecutor = WorldAwareScriptExecutor()

        val adapter = ExtensionUserScriptsAdapter(
            context = context,
            permissionManager = permissionManager,
            registry = registry,
            worldAwareScriptExecutor = worldAwareScriptExecutor
        )

        assertSame("WorldAwareScriptExecutor must be the injected canonical instance", worldAwareScriptExecutor, adapter.worldAwareScriptExecutor)
    }

    @Test
    fun testExtensionEngineProvidesCanonicalInjectorsAndExecutor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ExtensionEngineImpl(context, initialDelegate = null)

        val scriptingAdapter = engine.scriptingAdapter
        val userScriptsAdapter = engine.userScriptsAdapter

        assertSame(
            "ExtensionEngineImpl must inject its canonical scriptInjector into ExtensionScriptingAdapter",
            engine.scriptInjector,
            scriptingAdapter.scriptInjector
        )
        assertSame(
            "ExtensionEngineImpl must inject its canonical cssInjector into ExtensionScriptingAdapter",
            engine.cssInjector,
            scriptingAdapter.cssInjector
        )
        assertSame(
            "ExtensionEngineImpl must inject its canonical worldAwareScriptExecutor into ExtensionScriptingAdapter",
            engine.worldAwareScriptExecutor,
            scriptingAdapter.worldAwareScriptExecutor
        )
        assertSame(
            "ExtensionEngineImpl must inject its canonical worldAwareScriptExecutor into ExtensionUserScriptsAdapter",
            engine.worldAwareScriptExecutor,
            userScriptsAdapter.worldAwareScriptExecutor
        )
        assertSame(
            "ExtensionEngineImpl must inject its canonical scriptInjector into ServiceWorkerJsRuntime",
            engine.scriptInjector,
            engine.swJsRuntime.scriptInjector
        )
        assertSame(
            "ExtensionEngineImpl must inject its canonical permissionManager into ExtensionScriptingAdapter",
            engine.permissionManager,
            scriptingAdapter.permissionManager
        )
    }
}
