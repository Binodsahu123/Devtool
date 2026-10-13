package com.swift.browser.extensionengine

import android.content.Context
import com.swift.browser.extensionengine.resources.ExtensionResourceResolver
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class StorageManager(
    private val db: ExtensionDatabase,
    private val context: Context? = null,
    private val registry: ExtensionRegistry? = null,
    private val syncTransport: StorageSyncTransport? = null
) {

    private val storageDao = db.storageDao()
    private val syncJournal: ExtensionStorageSyncJournal? = context?.let { ExtensionStorageSyncJournal(it) }

    // Isolated in-memory storage for normal mode "session" area: extensionId -> key -> value
    private val sessionStorageMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    // Isolated in-memory storage for private browsing sessions: sessionId -> "extensionId:area" -> key -> value
    private val privateStorageMap = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>>()

    // Callback used to dispatch chrome.storage.onChanged events downstream.
    // Receives arguments: (extensionId, area, changes)
    var changeListener: ((String, String, JSONObject) -> Unit)? = null

    // Chrome-compatible practical quotas. storage.local/session are ~10 MB; sync is ~100 KB
    // total and ~8 KB per item. A manifest "unlimitedStorage" permission lifts local quota.
    companion object {
        private const val LOCAL_QUOTA_BYTES = 10L * 1024L * 1024L
        private const val SESSION_QUOTA_BYTES = 10L * 1024L * 1024L
        private const val SYNC_QUOTA_BYTES = 100L * 1024L
        private const val SYNC_ITEM_QUOTA_BYTES = 8L * 1024L
        const val SYNC_MAX_ITEMS = 512
        const val SYNC_MAX_WRITE_OPERATIONS_PER_MINUTE = 120
        const val SYNC_MAX_WRITE_OPERATIONS_PER_HOUR = 1800
    }

    private val accessLevels = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val writeCounters = ConcurrentHashMap<String, MutableList<Long>>()

    private fun checkAreaSupported(area: String) {
        if (area == "managed" || area == "sync" || area == "local" || area == "session") {
            return
        }
        throw IllegalArgumentException("UNSUPPORTED_BY_ORION")
    }

    private fun getManagedStorage(extensionId: String, keys: Any?): JSONObject {
        val ctx = context ?: throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        val reg = registry ?: throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        val ext = reg.getExtension(extensionId) ?: throw IllegalArgumentException("Extension not found")
        
        val manifest = JSONObject(ext.manifestJson)
        val storageObj = manifest.optJSONObject("storage")
        val schemaPath = storageObj?.optString("managed_schema")
        if (schemaPath.isNullOrBlank()) {
            throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        }

        val extensionDir = ExtensionDirectoryResolver.getExtensionDir(ctx, extensionId, ext.name)
        val schemaFile = ExtensionDirectoryResolver.findFileCaseInsensitive(extensionDir, schemaPath)
        if (schemaFile == null || !schemaFile.exists()) {
            throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        }

        try {
            PathSanitizer.checkCanonicalContainment(extensionDir, schemaFile)
        } catch (e: Exception) {
            throw SecurityException("SecurityError: Path traversal detected in managed schema path.")
        }

        val schemaContent = schemaFile.readText(Charsets.UTF_8)
        val schemaJson = try {
            JSONObject(schemaContent)
        } catch (e: Exception) {
            throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        }

        if (schemaJson.optString("type") != "object") {
            throw IllegalArgumentException("MANAGED_STORAGE_UNAVAILABLE")
        }

        val restrictionsManager = ctx.getSystemService(Context.RESTRICTIONS_SERVICE) as? android.content.RestrictionsManager
        val restrictionsBundle = restrictionsManager?.applicationRestrictions

        val result = JSONObject()
        if (restrictionsBundle != null && !restrictionsBundle.isEmpty) {
            val schemaProperties = schemaJson.optJSONObject("properties") ?: JSONObject()
            for (propKey in schemaProperties.keys()) {
                if (restrictionsBundle.containsKey(propKey)) {
                    val rawValue = restrictionsBundle.get(propKey)
                    if (isKeyFiltered(propKey, keys)) {
                        result.put(propKey, rawValue)
                    }
                }
            }
        }
        return result
    }

    private fun isKeyFiltered(key: String, keys: Any?): Boolean {
        return when (keys) {
            null -> true
            is String -> key == keys
            is List<*> -> keys.contains(key)
            is JSONObject -> keys.has(key)
            else -> false
        }
    }

    private fun storageSize(obj: JSONObject): Long {
        var total = 0L
        for (key in obj.keys()) {
            val value = obj.opt(key)
            total += key.toByteArray(Charsets.UTF_8).size
            total += (value?.toString() ?: "").toByteArray(Charsets.UTF_8).size
        }
        return total
    }

    private fun hasUnlimitedStorage(extensionId: String): Boolean {
        val ext = registry?.getExtension(extensionId) ?: return false
        return ext.permissions.any { it.equals("unlimitedStorage", ignoreCase = true) } ||
            ext.optionalPermissions.any { it.equals("unlimitedStorage", ignoreCase = true) }
    }

    private fun enforceQuota(
        extensionId: String,
        area: String,
        current: JSONObject,
        incoming: JSONObject
    ) {
        val limit = when (area) {
            "sync" -> SYNC_QUOTA_BYTES
            "session" -> SESSION_QUOTA_BYTES
            else -> if (hasUnlimitedStorage(extensionId)) Long.MAX_VALUE else LOCAL_QUOTA_BYTES
        }

        val projected = JSONObject()
        for (key in current.keys()) projected.put(key, current.get(key))
        for (key in incoming.keys()) projected.put(key, incoming.get(key))

        if (limit != Long.MAX_VALUE && storageSize(projected) > limit) {
            throw IllegalStateException("STORAGE_QUOTA_BYTES")
        }

        if (area == "sync") {
            val projectedKeys = projected.keys().asSequence().count()
            if (projectedKeys > SYNC_MAX_ITEMS) throw IllegalStateException("STORAGE_MAX_ITEMS")
            for (key in incoming.keys()) {
                val one = JSONObject().put(key, incoming.get(key))
                if (storageSize(one) > SYNC_ITEM_QUOTA_BYTES) {
                    throw IllegalStateException("STORAGE_QUOTA_BYTES_PER_ITEM")
                }
            }
        }
    }

    private fun recordSyncChanges(extensionId: String, area: String, changes: JSONObject) {
        if (area == "sync" && changes.length() > 0) {
            syncJournal?.record(extensionId, area, changes)
        }
    }

    fun exportSyncDelta(extensionId: String, afterRevision: Long = 0L, maxItems: Int = 512): JSONObject =
        syncJournal?.export(extensionId, afterRevision, maxItems)?.toJson()
            ?: JSONObject().apply {
                put("extensionId", extensionId)
                put("deviceId", "")
                put("firstRevision", 0L)
                put("lastRevision", 0L)
                put("changes", JSONArray())
            }

    fun acknowledgeSyncDelta(extensionId: String, throughRevision: Long) {
        syncJournal?.acknowledge(extensionId, throughRevision)
    }

    fun pendingSyncChanges(extensionId: String): Int =
        syncJournal?.pendingCount(extensionId) ?: 0

    suspend fun syncWithRemote(extensionId: String): StorageSyncTransport.DownloadResult? {
        val transport = syncTransport ?: throw IllegalStateException("STORAGE_SYNC_PROVIDER_UNAVAILABLE")
        val journal = syncJournal ?: throw IllegalStateException("STORAGE_SYNC_JOURNAL_UNAVAILABLE")
        val delta = journal.export(extensionId)
        if (delta.changes.length() > 0) {
            journal.recordUploadAttempt(extensionId)
            try {
                val upload = transport.upload(extensionId, delta.toJson())
                journal.acknowledge(extensionId, upload.acknowledgedThroughRevision)
                journal.recordUploadSuccess(extensionId, upload.remoteRevision)
            } catch (t: Throwable) {
                journal.recordUploadFailure(extensionId, t.message ?: "STORAGE_SYNC_UPLOAD_FAILED")
                throw t
            }
        }
        val status = journal.status(extensionId)
        return try {
            val download = transport.download(extensionId, status.remoteRevision)
            val winners = journal.mergeRemote(extensionId, download.changes)
            applyRemoteChanges(extensionId, winners)
            journal.recordDownloadSuccess(extensionId, download.remoteRevision)
            download
        } catch (t: Throwable) {
            journal.recordDownloadFailure(extensionId, t.message ?: "STORAGE_SYNC_DOWNLOAD_FAILED")
            throw t
        }
    }

    fun syncStatus(extensionId: String): ExtensionStorageSyncJournal.SyncStatus =
        syncJournal?.status(extensionId) ?: throw IllegalStateException("STORAGE_SYNC_JOURNAL_UNAVAILABLE")

    private suspend fun applyRemoteChanges(extensionId: String, changes: JSONArray) {
        for (i in 0 until changes.length()) {
            val change = changes.optJSONObject(i) ?: continue
            val key = change.optString("key", "")
            if (key.isBlank()) continue

            val currentEntity = storageDao.getStorageByKeys(extensionId, "sync", listOf(key)).firstOrNull()
            val oldValue = currentEntity?.let { parseRawValue(it.valueJson) }
            val changeObj = JSONObject()
            if (oldValue != null) changeObj.put("oldValue", oldValue)

            if (change.optBoolean("deleted", false)) {
                storageDao.deleteStorageByKeys(extensionId, "sync", listOf(key))
                changeObj.put("newValue", JSONObject.NULL)
            } else if (change.has("value") && !change.isNull("value")) {
                val value = change.opt("value")
                enforceQuota(extensionId, "sync", get(extensionId, "sync", null), JSONObject().put(key, value))
                storageDao.insertStorage(listOf(StorageEntity(extensionId, "sync", key, value.toString())))
                changeObj.put("newValue", value)
            } else {
                continue
            }
            changeListener?.invoke(extensionId, "sync", JSONObject().put(key, changeObj))
        }
    }
    fun setAccessLevel(extensionId: String, area: String, level: String) {
        checkAreaSupported(area)
        val normalized = level.uppercase().trim()
        if (normalized != "TRUSTED_CONTEXTS" && normalized != "TRUSTED_AND_UNTRUSTED_CONTEXTS") {
            throw IllegalArgumentException("STORAGE_INVALID_ACCESS_LEVEL")
        }
        accessLevels.computeIfAbsent(extensionId.lowercase().trim()) { ConcurrentHashMap() }[area] = normalized
    }

    fun getAccessLevel(extensionId: String, area: String): String {
        checkAreaSupported(area)
        val defaultLevel = if (area == "session") "TRUSTED_CONTEXTS" else "TRUSTED_AND_UNTRUSTED_CONTEXTS"
        return accessLevels[extensionId.lowercase().trim()]?.get(area) ?: defaultLevel
    }

    private fun recordWrite(extensionId: String, area: String) {
        if (area != "sync") return
        val now = System.currentTimeMillis()
        val key = "$extensionId:$area"
        val list = writeCounters.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60 * 60_000L }
            val minuteCount = list.count { now - it <= 60_000L }
            val hourCount = list.size
            if (minuteCount >= SYNC_MAX_WRITE_OPERATIONS_PER_MINUTE || hourCount >= SYNC_MAX_WRITE_OPERATIONS_PER_HOUR) {
                throw IllegalStateException("STORAGE_WRITE_QUOTA_EXCEEDED")
            }
            list.add(now)
        }
    }

    fun clearPrivateStorage(privateSessionId: String? = null) {
        if (privateSessionId != null) {
            privateStorageMap.remove(privateSessionId)
        } else {
            privateStorageMap.clear()
        }
    }

    private fun parseRawValue(raw: String): Any {
        return try {
            if (raw.startsWith("{")) {
                JSONObject(raw)
            } else if (raw.startsWith("[")) {
                JSONArray(raw)
            } else if (raw == "true" || raw == "false") {
                raw.toBoolean()
            } else {
                val d = raw.toDoubleOrNull()
                if (d != null) {
                    if (raw.contains(".")) d else raw.toLong()
                } else {
                    raw
                }
            }
        } catch (e: Exception) {
            raw
        }
    }

    suspend fun get(
        extensionId: String,
        area: String,
        keys: Any?,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ): JSONObject {
        checkAreaSupported(area)

        if (area == "managed") {
            return getManagedStorage(extensionId, keys)
        }

        if (isPrivate) {
            val sessionId = privateSessionId ?: "default_private"
            val keyStore = privateStorageMap[sessionId]?.get("$extensionId:$area") ?: emptyMap()
            val result = JSONObject()
            when (keys) {
                null -> {
                    for ((k, v) in keyStore) {
                        result.put(k, parseRawValue(v))
                    }
                }
                is String -> {
                    keyStore[keys]?.let { result.put(keys, parseRawValue(it)) }
                }
                is List<*> -> {
                    val keysList = keys.filterIsInstance<String>()
                    for (k in keysList) {
                        keyStore[k]?.let { result.put(k, parseRawValue(it)) }
                    }
                }
                is JSONObject -> {
                    val defaultKeys = keys.keys().asSequence().toList()
                    for (key in defaultKeys) {
                        val v = keyStore[key]
                        if (v != null) {
                            result.put(key, parseRawValue(v))
                        } else {
                            result.put(key, keys.get(key))
                        }
                    }
                }
            }
            return result
        }

        if (area == "session") {
            val keyStore = sessionStorageMap[extensionId] ?: emptyMap()
            val result = JSONObject()
            when (keys) {
                null -> {
                    for ((k, v) in keyStore) {
                        result.put(k, parseRawValue(v))
                    }
                }
                is String -> {
                    keyStore[keys]?.let { result.put(keys, parseRawValue(it)) }
                }
                is List<*> -> {
                    val keysList = keys.filterIsInstance<String>()
                    for (k in keysList) {
                        keyStore[k]?.let { result.put(k, parseRawValue(it)) }
                    }
                }
                is JSONObject -> {
                    val defaultKeys = keys.keys().asSequence().toList()
                    for (key in defaultKeys) {
                        val v = keyStore[key]
                        if (v != null) {
                            result.put(key, parseRawValue(v))
                        } else {
                            result.put(key, keys.get(key))
                        }
                    }
                }
            }
            return result
        }

        val result = JSONObject()
        val allEntities = when (keys) {
            null -> {
                storageDao.getStorageByArea(extensionId, area)
            }
            is String -> {
                storageDao.getStorageByKeys(extensionId, area, listOf(keys))
            }
            is List<*> -> {
                val keysList = keys.filterIsInstance<String>()
                storageDao.getStorageByKeys(extensionId, area, keysList)
            }
            is JSONObject -> {
                val defaultKeys = keys.keys().asSequence().toList()
                val loaded = storageDao.getStorageByKeys(extensionId, area, defaultKeys)
                for (key in defaultKeys) {
                    result.put(key, keys.get(key))
                }
                loaded
            }
            else -> emptyList()
        }

        for (entity in allEntities) {
            val jsonVal = parseRawValue(entity.valueJson)
            result.put(entity.key, jsonVal)
        }

        return result
    }

    suspend fun set(
        extensionId: String,
        area: String,
        items: JSONObject,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ) {
        checkAreaSupported(area)
        if (area == "managed") {
            throw IllegalArgumentException("STORAGE_MANAGED_READ_ONLY")
        }
        val keysList = items.keys().asSequence().toList()
        if (keysList.isEmpty()) return

        if (isPrivate) {
            val sessionId = privateSessionId ?: "default_private"
            val sessionMap = privateStorageMap.getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap() }
            val keyStore = sessionMap.getOrPut("$extensionId:$area") { java.util.concurrent.ConcurrentHashMap() }

            val changes = JSONObject()
            for (key in keysList) {
                val newValue = items.get(key)
                val oldValueRaw = keyStore[key]
                val oldValue = oldValueRaw?.let { parseRawValue(it) }

                keyStore[key] = newValue.toString()

                val changeObj = JSONObject()
                if (oldValue != null) {
                    changeObj.put("oldValue", oldValue)
                }
                changeObj.put("newValue", newValue)
                changes.put(key, changeObj)
            }

            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        if (area == "session") {
            val keyStore = sessionStorageMap.getOrPut(extensionId) { java.util.concurrent.ConcurrentHashMap() }
            val changes = JSONObject()
            for (key in keysList) {
                val newValue = items.get(key)
                val oldValueRaw = keyStore[key]
                val oldValue = oldValueRaw?.let { parseRawValue(it) }

                keyStore[key] = newValue.toString()

                val changeObj = JSONObject()
                if (oldValue != null) {
                    changeObj.put("oldValue", oldValue)
                }
                changeObj.put("newValue", newValue)
                changes.put(key, changeObj)
            }

            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        recordWrite(extensionId, area)

        // Gather current area to enforce total quota against the post-write state.
        val currentEntities = storageDao.getStorageByArea(extensionId, area)
        val currentObject = JSONObject()
        for (entity in currentEntities) currentObject.put(entity.key, parseRawValue(entity.valueJson))

        enforceQuota(extensionId, area, currentObject, items)

        val oldEntities = storageDao.getStorageByKeys(extensionId, area, keysList)
        val oldValuesMap = oldEntities.associate { it.key to parseRawValue(it.valueJson) }

        // Perform write
        val entities = mutableListOf<StorageEntity>()
        val changes = JSONObject()

        for (key in keysList) {
            val newValue = items.get(key)
            entities.add(StorageEntity(extensionId, area, key, newValue.toString()))

            val oldValue = oldValuesMap[key]
            val changeObj = JSONObject()
            if (oldValue != null) {
                changeObj.put("oldValue", oldValue)
            }
            changeObj.put("newValue", newValue)
            changes.put(key, changeObj)
        }

        storageDao.insertStorage(entities)

        // 3. Trigger change listeners
        if (changes.length() > 0) {
            recordSyncChanges(extensionId, area, changes)
            changeListener?.invoke(extensionId, area, changes)
        }
    }

    suspend fun remove(extensionId: String, area: String, keys: List<String>, isPrivate: Boolean = false, privateSessionId: String? = null) {
        checkAreaSupported(area)
        if (area == "managed") {
            throw IllegalArgumentException("STORAGE_MANAGED_READ_ONLY")
        }
        if (keys.isEmpty()) return

        if (isPrivate) {
            val sessionId = privateSessionId ?: "default_private"
            val keyStore = privateStorageMap[sessionId]?.get("$extensionId:$area") ?: return
            val changes = JSONObject()
            for (key in keys) {
                val oldValueRaw = keyStore.remove(key)
                if (oldValueRaw != null) {
                    val changeObj = JSONObject()
                    changeObj.put("oldValue", parseRawValue(oldValueRaw))
                    changeObj.put("newValue", JSONObject.NULL)
                    changes.put(key, changeObj)
                }
            }
            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        if (area == "session") {
            val keyStore = sessionStorageMap[extensionId] ?: return
            val changes = JSONObject()
            for (key in keys) {
                val oldValueRaw = keyStore.remove(key)
                if (oldValueRaw != null) {
                    val changeObj = JSONObject()
                    changeObj.put("oldValue", parseRawValue(oldValueRaw))
                    changeObj.put("newValue", JSONObject.NULL)
                    changes.put(key, changeObj)
                }
            }
            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        // 1. Gather old values
        val oldEntities = storageDao.getStorageByKeys(extensionId, area, keys)
        val changes = JSONObject()

        for (entity in oldEntities) {
            val oldValue = parseRawValue(entity.valueJson)
            val changeObj = JSONObject()
            changeObj.put("oldValue", oldValue)
            changeObj.put("newValue", JSONObject.NULL)
            changes.put(entity.key, changeObj)
        }

        // 2. Perform deletion
        storageDao.deleteStorageByKeys(extensionId, area, keys)

        // 3. Trigger change listeners
        if (changes.length() > 0) {
            recordSyncChanges(extensionId, area, changes)
            changeListener?.invoke(extensionId, area, changes)
        }
    }

    suspend fun clear(extensionId: String, area: String, isPrivate: Boolean = false, privateSessionId: String? = null) {
        checkAreaSupported(area)
        if (area == "managed") {
            throw IllegalArgumentException("STORAGE_MANAGED_READ_ONLY")
        }

        if (isPrivate) {
            val sessionId = privateSessionId ?: "default_private"
            val keyStore = privateStorageMap[sessionId]?.remove("$extensionId:$area") ?: return
            val changes = JSONObject()
            for ((key, rawVal) in keyStore) {
                val changeObj = JSONObject()
                changeObj.put("oldValue", parseRawValue(rawVal))
                changeObj.put("newValue", JSONObject.NULL)
                changes.put(key, changeObj)
            }
            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        if (area == "session") {
            val keyStore = sessionStorageMap.remove(extensionId) ?: return
            val changes = JSONObject()
            for ((key, rawVal) in keyStore) {
                val changeObj = JSONObject()
                changeObj.put("oldValue", parseRawValue(rawVal))
                changeObj.put("newValue", JSONObject.NULL)
                changes.put(key, changeObj)
            }
            if (changes.length() > 0) {
                changeListener?.invoke(extensionId, area, changes)
            }
            return
        }

        // 1. Gather all existing keys
        val oldEntities = storageDao.getStorageByArea(extensionId, area)
        val changes = JSONObject()

        for (entity in oldEntities) {
            val oldValue = parseRawValue(entity.valueJson)
            val changeObj = JSONObject()
            changeObj.put("oldValue", oldValue)
            changeObj.put("newValue", JSONObject.NULL)
            changes.put(entity.key, changeObj)
        }

        // 2. Perform wipe
        storageDao.clearStorage(extensionId, area)

        // 3. Trigger change listeners
        if (changes.length() > 0) {
            recordSyncChanges(extensionId, area, changes)
            changeListener?.invoke(extensionId, area, changes)
        }
    }

    suspend fun getKeys(
        extensionId: String,
        area: String,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ): List<String> {
        checkAreaSupported(area)
        if (area == "managed") {
            return getManagedStorage(extensionId, null).keys().asSequence().toList().sorted()
        }
        return get(extensionId, area, null, isPrivate, privateSessionId).keys().asSequence().toList().sorted()
    }

    suspend fun getBytesInUse(
        extensionId: String,
        area: String,
        keys: Any?,
        isPrivate: Boolean = false,
        privateSessionId: String? = null
    ): Long {
        checkAreaSupported(area)
        val data = get(extensionId, area, keys, isPrivate, privateSessionId)
        var totalBytes = 0L
        for (key in data.keys()) {
            val keyBytes = key.toByteArray(Charsets.UTF_8).size.toLong()
            val valueStr = data.opt(key)?.toString() ?: ""
            val valBytes = valueStr.toByteArray(Charsets.UTF_8).size.toLong()
            totalBytes += (keyBytes + valBytes)
        }
        return totalBytes
    }
}

