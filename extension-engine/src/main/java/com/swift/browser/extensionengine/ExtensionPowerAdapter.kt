package com.swift.browser.extensionengine

import android.content.Context
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of chrome.power requestKeepAwake/releaseKeepAwake.
 * Ownership is per extension and reference-counted so one extension cannot release another's lock.
 */
class ExtensionPowerAdapter(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    private val locks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    fun requestKeepAwake(sender: ExtensionSender, level: String): JSONObject {
        authorize(sender)
        val mode = when (level.lowercase()) {
            "display" -> PowerManager.SCREEN_BRIGHT_WAKE_LOCK
            "system" -> PowerManager.PARTIAL_WAKE_LOCK
            "" -> throw IllegalArgumentException("POWER_LEVEL_REQUIRED")
            else -> throw IllegalArgumentException("POWER_LEVEL_INVALID")
        }
        val key = sender.extensionId.lowercase()
        val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        synchronized(locks) {
            val existing = locks[key]
            if (existing?.isHeld == true) return JSONObject().put("status", "success")
            val wakeLock = power.newWakeLock(mode, "SwiftBrowser:ExtensionPower:$key")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire()
            locks[key] = wakeLock
        }
        return JSONObject().put("status", "success")
    }

    fun releaseKeepAwake(sender: ExtensionSender): JSONObject {
        authorize(sender)
        val key = sender.extensionId.lowercase()
        synchronized(locks) {
            locks.remove(key)?.let { runCatching { if (it.isHeld) it.release() } }
        }
        return JSONObject().put("status", "success")
    }

    fun clearExtension(extensionId: String) {
        val key = extensionId.lowercase()
        synchronized(locks) {
            locks.remove(key)?.let { runCatching { if (it.isHeld) it.release() } }
        }
    }

    fun clearAll() {
        synchronized(locks) {
            locks.values.forEach { runCatching { if (it.isHeld) it.release() } }
            locks.clear()
        }
    }

    private fun authorize(sender: ExtensionSender) {
        val ext = registry.getExtension(sender.extensionId) ?: throw SecurityException("POWER_EXTENSION_NOT_FOUND")
        if (!registry.isExtensionEnabled(sender.extensionId)) throw SecurityException("POWER_EXTENSION_DISABLED")
        if (!permissionManager.hasApiPermission(sender.extensionId, ext.permissions, "power")) {
            throw SecurityException("POWER_PERMISSION_DENIED")
        }
    }
}
