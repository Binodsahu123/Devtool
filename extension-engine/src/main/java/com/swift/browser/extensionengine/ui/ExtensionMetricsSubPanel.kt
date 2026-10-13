package com.swift.browser.extensionengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swift.browser.extensionengine.ExtensionDebuggerEngine
import com.swift.browser.extensionengine.ExtensionEngineApi

@Composable
fun ExtensionMetricsSubPanel(
    api: ExtensionEngineApi,
    modifier: Modifier = Modifier
) {
    val uiState by api.uiState.collectAsState()
    val debugger = remember { ExtensionDebuggerEngine.instance }
    val events by debugger.events.collectAsState()

    val realMemoryMb = remember {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        "$used MB allocated"
    }

    val avgLatencyMs = remember(events) {
        if (events.isEmpty()) "0.0 ms"
        else {
            val avg = events.map { it.latencyMs }.average()
            String.format("%.1f ms", avg)
        }
    }

    val errorCount = remember(events) {
        val count = events.count { !it.success || it.level == "ERROR" }
        if (count == 1) "1 Error" else "$count Errors"
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MetricCard(
            title = "Active Loaded Extensions",
            value = "${uiState.enabledExtensions.size} of ${uiState.installedExtensions.size}",
            subtitle = "Extensions running background pages or content scripts",
            icon = Icons.Default.Extension
        )

        MetricCard(
            title = "Process Memory Allocated",
            value = realMemoryMb,
            subtitle = "Measured JVM & V8 isolate heap usage",
            icon = Icons.Default.Memory
        )

        MetricCard(
            title = "Average API Call Latency",
            value = avgLatencyMs,
            subtitle = "Measured across ${events.size} captured events",
            icon = Icons.Default.Speed
        )

        MetricCard(
            title = "Captured Script & API Errors",
            value = errorCount,
            subtitle = "Runtime exceptions & permission denials",
            icon = Icons.Default.BugReport
        )
    }
}
