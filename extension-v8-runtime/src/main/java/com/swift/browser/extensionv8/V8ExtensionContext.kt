package com.swift.browser.extensionv8

data class V8ExtensionContext(
    val extensionId: String,
    val generationId: Int,
    val scriptPath: String,
    val isModule: Boolean = false,
    val isPrivate: Boolean = false,
    val startTime: Long = System.currentTimeMillis(),
    var heapUsageBytes: Long = 0L,
    var isRunning: Boolean = false
)
