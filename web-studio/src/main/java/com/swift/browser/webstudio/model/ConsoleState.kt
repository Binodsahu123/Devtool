package com.swift.browser.webstudio.model

data class WebStudioConsoleEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val tag: String = "Console",
    val message: String
)

data class WebStudioConsoleState(
    val logs: List<WebStudioConsoleEntry> = emptyList(),
    val isVisible: Boolean = false
)
