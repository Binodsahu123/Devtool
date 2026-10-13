package com.swift.browser.extensionengine.chromedebugger

/**
 * Standard error messages and codes for chrome.debugger API.
 */
object ChromeDebuggerErrors {
    const val ERROR_NO_DEBUGGER_PERMISSION = "Extension does not have 'debugger' permission in manifest."
    const val ERROR_TARGET_NOT_FOUND = "No target found with the given targetId or tabId."
    const val ERROR_ALREADY_ATTACHED = "Another debugger is already attached to the target with the given id."
    const val ERROR_NOT_ATTACHED = "Debugger is not attached to the target with the given id."
    const val ERROR_CANNOT_ATTACH_TO_RESTRICTED = "Cannot attach to this target."
    const val ERROR_TARGET_CLOSED = "target_closed"
    const val ERROR_DETACHED_BY_USER = "canceled_by_user"
    const val ERROR_DETACHED_BY_DEVTOOLS = "replaced_with_devtools"
    const val ERROR_COMMAND_FAILED = "CDP command execution failed."
    const val ERROR_TIMEOUT = "CDP command timed out."
    const val ERROR_PROTOCOL_VERSION_UNSUPPORTED = "Requested protocol version is not supported."
    const val ERROR_INVALID_ARGUMENTS = "Invalid arguments provided to debugger API."
}

class ChromeDebuggerException(
    val errorCode: String,
    override val message: String
) : Exception(message)
