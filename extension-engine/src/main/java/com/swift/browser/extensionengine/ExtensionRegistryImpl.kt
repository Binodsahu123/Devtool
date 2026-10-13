package com.swift.browser.extensionengine

import com.swift.browser.bookmarkengine.api.BookmarkEngineApi
import com.swift.browser.historyengine.HistoryEngine
import com.swift.browser.tabengine.api.TabEngineApi
import java.util.concurrent.ConcurrentHashMap

class ExtensionRegistryImpl(
    override val bookmarkEngine: BookmarkEngineApi? = null,
    override val historyEngine: HistoryEngine? = null,
    override val tabEngine: TabEngineApi? = null
) : ExtensionRegistry {
    private val registryMap = ConcurrentHashMap<String, RegisteredExtensionInfo>()
    private val extensionGenerations = ConcurrentHashMap<String, Int>()

    @Synchronized
    override fun register(
        extension: ParsedExtension,
        initialState: ExtensionState
    ) {
        require(extension.id.isNotBlank()) {
            "Invalid extension ID '${extension.id}': ID cannot be blank"
        }
        require(extension.version.isNotBlank()) { "Extension '${extension.id}' has an empty version" }

        val now = System.currentTimeMillis()
        val existing = registryMap[extension.id]

        // Registry-level identity continuity: an ID can only ever represent the same
        // signing/public-key identity. Unsigned local identities are allowed to replace
        // the same ID only when the derived local identity remains identical.
        if (existing != null) {
            val oldIdentity = existing.extension.identity
            val newIdentity = extension.identity
            val oldKey = oldIdentity.publicKeyBytes
            val newKey = newIdentity.publicKeyBytes
            if (oldKey != null || newKey != null) {
                if (oldKey == null || newKey == null || !oldKey.contentEquals(newKey)) {
                    throw ExtensionError.RegistryError.ExtensionAlreadyExists(extension.id)
                }
            }
            if (existing.extension.identity.identityType != newIdentity.identityType) {
                throw ExtensionError.RegistryError.ExtensionAlreadyExists(extension.id)
            }
        }

        val currentGen = (extensionGenerations[extension.id] ?: 0) + 1
        extensionGenerations[extension.id] = currentGen

        val info = RegisteredExtensionInfo(
            extension = extension,
            state = initialState,
            generation = currentGen,
            installedTimestamp = existing?.installedTimestamp ?: now,
            lastStateChangeTimestamp = now
        )
        registryMap[extension.id] = info
    }

    @Synchronized
    override fun transitionState(id: String, targetState: ExtensionState): RegisteredExtensionInfo {
        val currentInfo = registryMap[id]
            ?: throw ExtensionError.SecurityError.ExtensionNotFound(id)

        if (!currentInfo.state.canTransitionTo(targetState)) {
            throw ExtensionError.RegistryError.InvalidStateTransition(
                extensionId = id,
                currentState = currentInfo.state.name,
                targetState = targetState.name
            )
        }

        val updatedInfo = currentInfo.copy(
            state = targetState,
            lastStateChangeTimestamp = System.currentTimeMillis()
        )
        registryMap[id] = updatedInfo
        return updatedInfo
    }

    @Synchronized
    override fun unregister(id: String) {
        // Keep generation history so stale contexts cannot become valid after reinstall.
        registryMap.remove(id)
    }

    override fun getEnabledExtensions(): List<ParsedExtension> = getAllActiveExtensions()

    override fun registerExtension(extension: ParsedExtension, initialState: ExtensionState) {
        register(extension, initialState)
    }

    override fun getExtension(id: String): ParsedExtension? {
        return registryMap[id]?.extension
    }

    override fun getRegisteredInfo(id: String): RegisteredExtensionInfo? {
        return registryMap[id]
    }

    override fun getExtensionState(id: String): ExtensionState {
        return registryMap[id]?.state ?: ExtensionState.UNINSTALLED
    }

    override fun isExtensionEnabled(id: String): Boolean {
        val state = getExtensionState(id)
        return state == ExtensionState.ACTIVE || state == ExtensionState.INSTALLED_ENABLED
    }

    override fun getExtensionGeneration(id: String): Int {
        return extensionGenerations[id] ?: 0
    }

    override fun getAllActiveExtensions(): List<ParsedExtension> {
        return registryMap.values
            .asSequence()
            .filter { it.state == ExtensionState.ACTIVE || it.state == ExtensionState.INSTALLED_ENABLED }
            .sortedBy { it.extension.id }
            .map { it.extension }
            .toList()
    }

    override fun getAllRegisteredExtensions(): List<RegisteredExtensionInfo> {
        return registryMap.values.sortedBy { it.extension.id }
    }

    @Synchronized
    override fun clear() {
        // Generation counters deliberately survive clear() to invalidate stale runtimes.
        registryMap.clear()
    }
}
