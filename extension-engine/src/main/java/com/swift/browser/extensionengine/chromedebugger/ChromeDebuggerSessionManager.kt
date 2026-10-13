package com.swift.browser.extensionengine.chromedebugger

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active Debugger sessions, lifecycle transitions, and transport bindings.
 * Enforces single-debugger-per-target rule as required by Chrome specification.
 */
class ChromeDebuggerSessionManager(
    private val targetManager: ChromeDebuggerTargetManager,
    private val permissionChecker: ChromeDebuggerPermissionChecker,
    private val eventRouter: ChromeDebuggerEventRouter,
    private val transportFactory: ChromeDebuggerTransportFactory = ChromeDebuggerTransportFactory { target, onEvent ->
        ChromeDebuggerCdpTransport(target, onEvent)
    }
) {
    // Maps targetId -> ChromeDebuggerSession
    private val targetSessions = ConcurrentHashMap<String, ChromeDebuggerSession>()

    // Maps sessionId -> CDP transport interface (real in production, fake only in tests)
    private val sessionTransports = ConcurrentHashMap<String, ChromeDebuggerTransport>()

    // Maps extensionId -> Set of sessionIds
    private val extensionSessions = ConcurrentHashMap<String, MutableSet<String>>()

    fun getAttachedTargetIds(): Set<String> = targetSessions.keys

    fun isAttached(targetId: String): Boolean = targetSessions.containsKey(targetId)

    /**
     * Attaches debugger to the specified target.
     */
    suspend fun attach(
        extensionId: String,
        debuggee: ChromeDebuggee,
        requiredVersion: String
    ): ChromeDebuggerSession {
        // 1. Permission and protocol-version checks
        permissionChecker.verifyDebuggerPermission(extensionId)
        if (requiredVersion.trim() != "1.3") {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_PROTOCOL_VERSION_UNSUPPORTED,
                "Unsupported chrome.debugger protocol version '${requiredVersion.trim()}'; this WebView transport supports CDP 1.3."
            )
        }

        // 2. Resolve target
        val resolvedTarget = targetManager.resolveTarget(debuggee)
            ?: throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_TARGET_NOT_FOUND,
                ChromeDebuggerErrors.ERROR_TARGET_NOT_FOUND
            )

        // 3. Security/Restriction check
        if (!permissionChecker.isTargetAttachable(extensionId, resolvedTarget.url, resolvedTarget.isPrivate)) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_CANNOT_ATTACH_TO_RESTRICTED,
                ChromeDebuggerErrors.ERROR_CANNOT_ATTACH_TO_RESTRICTED
            )
        }

        // 4. Single-debugger-per-target rule
        val existingSession = targetSessions[resolvedTarget.targetId]
        if (existingSession != null && existingSession.isActive) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_ALREADY_ATTACHED,
                ChromeDebuggerErrors.ERROR_ALREADY_ATTACHED
            )
        }

        // 5. Create Session & CDP Transport
        val sessionId = "session_${UUID.randomUUID()}"
        val session = ChromeDebuggerSession(
            sessionId = sessionId,
            extensionId = extensionId,
            debuggee = debuggee,
            targetId = resolvedTarget.targetId,
            requiredVersion = requiredVersion
        )

        val transport = transportFactory.create(
            target = resolvedTarget,
            onEvent = { event ->
                if (session.isActive) {
                    eventRouter.dispatchDebuggerEvent(extensionId, debuggee, event)
                }
            }
        )

        try {
            transport.connect()
        } catch (e: Exception) {
            transport.close("attach_failed")
            throw e
        }

        targetSessions[resolvedTarget.targetId] = session
        sessionTransports[sessionId] = transport
        extensionSessions.computeIfAbsent(extensionId) { ConcurrentHashMap.newKeySet() }.add(sessionId)

        return session
    }

    /**
     * Detaches debugger from target.
     */
    fun detach(extensionId: String, debuggee: ChromeDebuggee, reason: String? = null) {
        val resolvedTarget = targetManager.resolveTarget(debuggee)
            ?: throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED,
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED
            )

        val session = targetSessions[resolvedTarget.targetId]
        if (session == null || session.extensionId != extensionId || !session.isActive) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED,
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED
            )
        }

        session.isActive = false
        targetSessions.remove(resolvedTarget.targetId)
        val transport = sessionTransports.remove(session.sessionId)
        transport?.close()

        extensionSessions[extensionId]?.remove(session.sessionId)

        // If detached due to external reasons (not explicit extension call), fire onDetach
        if (reason != null) {
            eventRouter.dispatchDebuggerDetach(extensionId, debuggee, reason)
        }
    }

    /**
     * Gets active CDP transport for sending commands.
     */
    fun getTransport(extensionId: String, debuggee: ChromeDebuggee): ChromeDebuggerTransport {
        val resolvedTarget = targetManager.resolveTarget(debuggee)
            ?: throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED,
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED
            )

        val session = targetSessions[resolvedTarget.targetId]
        if (session == null || session.extensionId != extensionId || !session.isActive) {
            throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED,
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED
            )
        }

        return sessionTransports[session.sessionId]
            ?: throw ChromeDebuggerException(
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED,
                ChromeDebuggerErrors.ERROR_NOT_ATTACHED
            )
    }

    /**
     * Called when a target tab is closed or destroyed.
     */
    fun onTargetClosed(targetId: String) {
        val session = targetSessions.remove(targetId) ?: return
        session.isActive = false
        val transport = sessionTransports.remove(session.sessionId)
        transport?.close()
        extensionSessions[session.extensionId]?.remove(session.sessionId)

        eventRouter.dispatchDebuggerDetach(
            session.extensionId,
            session.debuggee,
            ChromeDebuggerErrors.ERROR_TARGET_CLOSED
        )
    }

    /**
     * Detaches all active sessions for an extension (e.g. when disabled or uninstalled).
     */
    fun detachAllForExtension(extensionId: String) {
        val sessionIds = extensionSessions.remove(extensionId) ?: return
        for (sessionId in sessionIds) {
            val session = targetSessions.values.firstOrNull { it.sessionId == sessionId }
            if (session != null) {
                session.isActive = false
                targetSessions.remove(session.targetId)
                val transport = sessionTransports.remove(sessionId)
                transport?.close()
            }
        }
    }
}
