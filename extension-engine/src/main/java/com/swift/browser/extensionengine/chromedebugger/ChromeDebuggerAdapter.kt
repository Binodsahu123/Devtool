package com.swift.browser.extensionengine.chromedebugger

import com.swift.browser.extensionengine.EventManager
import com.swift.browser.extensionengine.ExtensionPermissionAdapter
import com.swift.browser.extensionengine.ExtensionRegistry
import com.swift.browser.extensionengine.ExtensionSender
import com.swift.browser.extensionengine.PermissionManager
import com.swift.browser.tabengine.api.TabEngineApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical Adapter for chrome.debugger API.
 * Encapsulates the entire debugger extension subsystem while integrating
 * seamlessly with canonical Tab Engine, Permission Engine, and Extension Registry.
 */
class ChromeDebuggerAdapter(
    val tabEngine: TabEngineApi,
    val registry: ExtensionRegistry,
    val permissionManager: PermissionManager,
    val permissionAdapter: ExtensionPermissionAdapter,
    val eventManager: EventManager,
    private val transportFactory: ChromeDebuggerTransportFactory = ChromeDebuggerTransportFactory { target, onEvent ->
        ChromeDebuggerCdpTransport(target, onEvent)
    }
) : ChromeDebuggerApi {

    val permissionChecker: ChromeDebuggerPermissionChecker by lazy {
        ChromeDebuggerPermissionChecker(permissionManager, permissionAdapter, registry)
    }

    val targetManager: ChromeDebuggerTargetManager by lazy {
        ChromeDebuggerTargetManager(tabEngine, registry)
    }

    val eventRouter: ChromeDebuggerEventRouter by lazy {
        ChromeDebuggerEventRouter(eventManager)
    }

    val sessionManager: ChromeDebuggerSessionManager by lazy {
        ChromeDebuggerSessionManager(targetManager, permissionChecker, eventRouter, transportFactory)
    }

    val messageRouter: ChromeDebuggerMessageRouter by lazy {
        ChromeDebuggerMessageRouter(this)
    }

    override suspend fun attach(extensionId: String, target: ChromeDebuggee, requiredVersion: String) {
        sessionManager.attach(extensionId, target, requiredVersion)
    }

    override fun detach(extensionId: String, target: ChromeDebuggee) {
        sessionManager.detach(extensionId, target)
    }

    override suspend fun sendCommand(
        extensionId: String,
        target: ChromeDebuggee,
        method: String,
        params: JSONObject
    ): JSONObject {
        val transport = sessionManager.getTransport(extensionId, target)
        return transport.sendCommand(method, params)
    }

    override suspend fun getTargets(extensionId: String): List<ChromeDebuggerTargetInfo> {
        permissionChecker.verifyDebuggerPermission(extensionId)
        val attachedIds = sessionManager.getAttachedTargetIds()
        return targetManager.getAllTargets(attachedIds)
    }

    suspend fun handleBridgeCall(
        sender: ExtensionSender,
        api: String,
        args: JSONArray
    ): Any? {
        return messageRouter.route(sender, api, args)
    }

    fun onTabClosed(tabUuid: String) {
        val targetId = targetManager.getTargetIdForTab(tabUuid)
        sessionManager.onTargetClosed(targetId)
        targetManager.onTabClosed(tabUuid)
    }

    fun onExtensionUnloaded(extensionId: String) {
        sessionManager.detachAllForExtension(extensionId)
    }
}
