package com.swift.browser.extensionengine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class DebugErrorType {
    RUNTIME, MANIFEST, PERMISSION, STORAGE, MESSAGE
}

data class ExtensionDebugLog(
    val id: String = UUID.randomUUID().toString(),
    val extensionId: String,
    val extensionName: String,
    val type: DebugErrorType,
    val message: String,
    val severity: String, // "ERROR", "WARNING", "INFO"
    val timestamp: Long = System.currentTimeMillis()
)

data class ExtensionPerformanceMetric(
    val extensionId: String,
    val extensionName: String,
    val cpuUsagePercent: Double,
    val memoryUsageMb: Double,
    val storageUsageKb: Double,
    val activeWorkersCount: Int
)

data class ExtensionAnalysisReport(
    val extensionId: String,
    val name: String,
    val manifestIssues: List<String>,
    val securityWarnings: List<String>,
    val errorSummaryEnglish: String,
    val errorSummaryHindi: String,
    val deepSolutionEnglish: String,
    val deepSolutionHindi: String,
    val healthScore: Int // 0-100
)

data class ExtensionDiagnosticEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val extensionId: String,
    val extensionName: String = "",
    val context: String = "SERVICE_WORKER", // SERVICE_WORKER, BACKGROUND, CONTENT_SCRIPT, POPUP, OPTIONS, SIDE_PANEL
    val tabId: Int? = null,
    val frameId: Int? = null,
    val documentId: String? = null,
    val apiOrEventName: String = "",
    val category: String = "API", // CONSOLE, SERVICE_WORKER, CONTENT_SCRIPT, API, NETWORK, STORAGE, PERMISSION, PERFORMANCE, EXCEPTION
    val level: String = "INFO", // VERBOSE, INFO, WARN, ERROR
    val latencyMs: Long = 0,
    val success: Boolean = true,
    val details: String = "",
    val errorMessage: String? = null
)

class ExtensionDebuggerEngine {
    private val maxEventBufferSize = 1000

    private val _logs = MutableStateFlow<List<ExtensionDebugLog>>(emptyList())
    val logs: StateFlow<List<ExtensionDebugLog>> = _logs.asStateFlow()

    private val _events = MutableStateFlow<List<ExtensionDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<ExtensionDiagnosticEvent>> = _events.asStateFlow()

    private val _metrics = MutableStateFlow<List<ExtensionPerformanceMetric>>(emptyList())
    val metrics: StateFlow<List<ExtensionPerformanceMetric>> = _metrics.asStateFlow()

    fun recordEvent(event: ExtensionDiagnosticEvent) {
        _events.update { currentList ->
            val updated = currentList + event
            if (updated.size > maxEventBufferSize) {
                updated.takeLast(maxEventBufferSize)
            } else {
                updated
            }
        }

        // Mirror errors to legacy logs list if category/level warrants it
        if (event.level == "ERROR" || event.errorMessage != null) {
            logError(
                extensionId = event.extensionId,
                extensionName = event.extensionName.ifBlank { event.extensionId },
                type = when (event.category) {
                    "PERMISSION" -> DebugErrorType.PERMISSION
                    "STORAGE" -> DebugErrorType.STORAGE
                    "NETWORK" -> DebugErrorType.MESSAGE
                    else -> DebugErrorType.RUNTIME
                },
                message = event.errorMessage ?: event.details.ifBlank { event.apiOrEventName },
                severity = "ERROR"
            )
        }
    }

    fun logError(extensionId: String, extensionName: String, type: DebugErrorType, message: String, severity: String = "ERROR") {
        val entry = ExtensionDebugLog(
            extensionId = extensionId,
            extensionName = extensionName,
            type = type,
            message = message,
            severity = severity
        )
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > maxEventBufferSize) updated.takeLast(maxEventBufferSize) else updated
        }

        // Developer Tools owns browser-page diagnostics. Extension diagnostics remain local to
        // this engine so the extension module does not depend on a removed InspectorEngine API.
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _events.value = emptyList()
    }

    fun updateMetrics(newMetrics: List<ExtensionPerformanceMetric>) {
        _metrics.value = newMetrics
    }

    fun generateAnalysisReport(id: String, parsedExtension: ParsedExtension? = null): ExtensionAnalysisReport {
        val extName = parsedExtension?.name ?: id
        val extEvents = _events.value.filter { it.extensionId == id }
        val errorCount = extEvents.count { !it.success || it.level == "ERROR" }
        val permDenials = extEvents.count { it.category == "PERMISSION" && !it.success }
        val manifestIssues = mutableListOf<String>()
        val securityWarnings = mutableListOf<String>()

        if (parsedExtension != null) {
            if (parsedExtension.manifestVersion < 3) {
                manifestIssues.add("Extension uses Manifest V2 format (MV3 recommended for Service Worker sandboxing)")
            }
            if (parsedExtension.permissions.contains("webRequestBlocking")) {
                securityWarnings.add("Uses webRequestBlocking (DeclarativeNetRequest preferred)")
            }
            if (parsedExtension.permissions.contains("<all_urls>")) {
                securityWarnings.add("Broad <all_urls> host permissions requested")
            }
        }

        val healthScore = (100 - (errorCount * 10) - (permDenials * 15) - (manifestIssues.size * 5)).coerceIn(10, 100)

        val errSummaryEng = if (errorCount == 0) {
            "Extension runtime health is optimal. Captured 0 uncaught errors in live session."
        } else {
            "Recorded $errorCount runtime issues/exceptions across ${extEvents.size} captured events."
        }

        val errSummaryHin = if (errorCount == 0) {
            "एक्सटेंशन का लाइव प्रदर्शन सामान्य है। सत्र में 0 त्रुटियां दर्ज की गईं।"
        } else {
            "कुल ${extEvents.size} घटनाओं में $errorCount रनटाइम त्रुटियां दर्ज की गईं।"
        }

        val deepSolEng = if (errorCount == 0) {
            "No urgent action required. Native V8 worker and content scripts are functioning within expected parameters."
        } else {
            "Check Developer Console API trace logs to resolve throwing methods or CSP parameter rejections."
        }

        val deepSolHin = if (errorCount == 0) {
            "किसी कार्रवाई की आवश्यकता नहीं है। V8 वर्कर और कंटेंट स्क्रिप्ट सही ढंग से चल रहे हैं।"
        } else {
            "डेवलपर कंसोल एपीआई ट्रेसेज की जांच करें और त्रुटियों का निवारण करें।"
        }

        return ExtensionAnalysisReport(
            extensionId = id,
            name = extName,
            manifestIssues = manifestIssues,
            securityWarnings = securityWarnings,
            errorSummaryEnglish = errSummaryEng,
            errorSummaryHindi = errSummaryHin,
            deepSolutionEnglish = deepSolEng,
            deepSolutionHindi = deepSolHin,
            healthScore = healthScore
        )
    }

    companion object {
        val instance = ExtensionDebuggerEngine()
    }
}
