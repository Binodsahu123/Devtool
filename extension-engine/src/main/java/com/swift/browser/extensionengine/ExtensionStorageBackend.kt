package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical extension storage boundary.
 *
 * Browser-global engines are not appropriate for chrome.storage because every
 * extension requires namespace isolation. This facade therefore owns the
 * extension-specific storage contract while keeping registry persistence
 * separate from browser-global preferences.
 *
 * The implementation is intentionally backed by the existing ExtensionDatabase
 * through StorageManager; no second browser-wide storage engine is introduced.
 */
class ExtensionStorageBackend(
    private val storageManager: StorageManager
) {
    suspend fun get(extensionId: String, area: String, keys: Any?, isPrivate: Boolean, privateSessionId: String?): JSONObject =
        storageManager.get(extensionId, area, keys, isPrivate, privateSessionId)

    suspend fun set(extensionId: String, area: String, items: JSONObject, isPrivate: Boolean, privateSessionId: String?) =
        storageManager.set(extensionId, area, items, isPrivate, privateSessionId)

    suspend fun remove(extensionId: String, area: String, keys: List<String>, isPrivate: Boolean, privateSessionId: String?) =
        storageManager.remove(extensionId, area, keys, isPrivate, privateSessionId)

    suspend fun clear(extensionId: String, area: String, isPrivate: Boolean, privateSessionId: String?) =
        storageManager.clear(extensionId, area, isPrivate, privateSessionId)

    suspend fun getBytesInUse(extensionId: String, area: String, keys: Any?, isPrivate: Boolean, privateSessionId: String?): Long =
        storageManager.getBytesInUse(extensionId, area, keys, isPrivate, privateSessionId)

    suspend fun getKeys(extensionId: String, area: String, isPrivate: Boolean, privateSessionId: String?): List<String> =
        storageManager.getKeys(extensionId, area, isPrivate, privateSessionId)

    suspend fun syncWithRemote(extensionId: String): StorageSyncTransport.DownloadResult? =
        storageManager.syncWithRemote(extensionId)

    fun syncStatus(extensionId: String): ExtensionStorageSyncJournal.SyncStatus =
        storageManager.syncStatus(extensionId)

    fun exportSyncDelta(extensionId: String, afterRevision: Long = 0L, maxItems: Int = 512): JSONObject =
        storageManager.exportSyncDelta(extensionId, afterRevision, maxItems)

    fun acknowledgeSyncDelta(extensionId: String, throughRevision: Long) =
        storageManager.acknowledgeSyncDelta(extensionId, throughRevision)

    fun pendingSyncChanges(extensionId: String): Int =
        storageManager.pendingSyncChanges(extensionId)

    fun setAccessLevel(extensionId: String, area: String, level: String) =
        storageManager.setAccessLevel(extensionId, area, level)

    fun getAccessLevel(extensionId: String, area: String): String =
        storageManager.getAccessLevel(extensionId, area)
}
