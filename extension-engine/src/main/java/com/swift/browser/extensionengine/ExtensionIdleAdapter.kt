package com.swift.browser.extensionengine

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

class ExtensionIdleAdapter(
    private val permissionManager: PermissionManager,
    private val registry: ExtensionRegistry
) {
    private var detectionIntervalSeconds: Int = 60

    private fun validate(sender: ExtensionSender): ParsedExtension =
        ExtensionSenderAccessValidator.validate(
            sender = sender,
            registry = registry,
            permissionManager = permissionManager,
            apiPermission = "idle",
            useSecurityException = true
        )

    fun queryState(sender: ExtensionSender, detectionIntervalInSeconds: Int, context: Context? = null): JSONObject {
        validate(sender)
        val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val isInteractive = powerManager?.isInteractive ?: true
        val isLocked = keyguardManager?.isKeyguardLocked ?: false

        val state = when {
            isLocked -> "locked"
            !isInteractive -> "idle"
            else -> "active"
        }

        return JSONObject().put("state", state)
    }

    fun setDetectionInterval(sender: ExtensionSender, intervalInSeconds: Int): JSONObject {
        validate(sender)
        this.detectionIntervalSeconds = intervalInSeconds.coerceAtLeast(15)
        return JSONObject().put("status", "success").put("interval", this.detectionIntervalSeconds)
    }
}
