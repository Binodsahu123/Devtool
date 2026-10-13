package com.swift.browser.webstudio.engine

class WebStudioCrashRecoveryManager(private val diagnosticsManager: WebStudioDiagnosticsManager) {
    fun executeSafe(operationName: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            diagnosticsManager.logError("Crash recovered in operation: $operationName", e)
            // Prevent actual crash, ensure state remains stable
        }
    }
}
