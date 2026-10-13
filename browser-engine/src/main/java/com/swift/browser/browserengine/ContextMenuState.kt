package com.swift.browser.browserengine

data class ContextMenuState(
    val show: Boolean = false,
    val pageUrl: String = "",
    val linkUrl: String? = null,
    val srcUrl: String? = null,
    val selectionText: String? = null,
    val isEditable: Boolean = false,
    val isImage: Boolean = false,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val isLink: Boolean = false,
    val isSelection: Boolean = false,
    val isImageLink: Boolean = false,
    val frameUrl: String? = null,
    val mediaType: String? = null,
    val tabId: String? = null,
    // Backwards-compatibility aliases
    val url: String = pageUrl.ifBlank { linkUrl ?: "" },
    val imageUrl: String = srcUrl ?: ""
)

