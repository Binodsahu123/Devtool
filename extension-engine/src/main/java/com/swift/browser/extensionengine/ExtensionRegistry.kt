package com.swift.browser.extensionengine

import com.swift.browser.bookmarkengine.api.BookmarkEngineApi
import com.swift.browser.historyengine.HistoryEngine
import com.swift.browser.tabengine.api.TabEngineApi

interface ExtensionRegistry {
    /**
     * Registers or replaces an extension revision while enforcing ID/identity continuity.
     * Implementations must reject a signing-key identity change for an existing ID.
     */
    fun register(extension: ParsedExtension, initialState: ExtensionState = ExtensionState.INSTALLED_ENABLED)
    fun transitionState(id: String, targetState: ExtensionState): RegisteredExtensionInfo
    fun unregister(id: String)
    fun getEnabledExtensions(): List<ParsedExtension>
    fun registerExtension(extension: ParsedExtension, initialState: ExtensionState = ExtensionState.INSTALLED_ENABLED)
    fun getExtension(id: String): ParsedExtension?
    fun getRegisteredInfo(id: String): RegisteredExtensionInfo?
    fun getExtensionState(id: String): ExtensionState
    fun isExtensionEnabled(id: String): Boolean
    fun getExtensionGeneration(id: String): Int
    fun getAllActiveExtensions(): List<ParsedExtension>
    fun getAllRegisteredExtensions(): List<RegisteredExtensionInfo>
    fun clear()
    
    // Dependencies
    val bookmarkEngine: BookmarkEngineApi?
    val historyEngine: HistoryEngine?
    val tabEngine: TabEngineApi?
}
