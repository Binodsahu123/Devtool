package com.swift.browser.tabengine.api

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import com.swift.browser.tabengine.engine.TabEngine

object TabEngineProvider {
    @Volatile
    private var instance: TabEngineApi? = null

    fun initialize(context: Context, scope: CoroutineScope? = null): TabEngineApi =
        getEngine(context, scope)

    fun getEngine(context: Context, scope: CoroutineScope? = null): TabEngineApi {
        val safeScope = scope ?: CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        return instance ?: synchronized(this) {
            instance ?: TabEngine(context, safeScope).also { instance = it }
        }
    }
}
