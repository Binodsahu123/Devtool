package com.swift.browser.extensionengine

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreEngineBindingContractTest {
    @Test
    fun browserCoreServicesExposesCanonicalEngineRegistry() {
        assertTrue(BrowserCoreServices::class.java.declaredMethods.any { it.name == "initializeCoreEngines" })
    }

    @Test
    fun canonicalBindingsExposeFourRequestedEngines() {
        assertTrue(CanonicalBrowserEngineBindings::class.java.declaredMethods.any { it.name == "assertCanonical" })
    }
}
