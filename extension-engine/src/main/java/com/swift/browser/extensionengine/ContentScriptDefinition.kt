package com.swift.browser.extensionengine

data class ContentScriptDefinition(
    val extensionId: String,
    val scriptId: String,
    val jsFiles: List<String>,
    val cssFiles: List<String>,
    val matches: List<String>,
    val excludeMatches: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val runAt: String = "document_idle",
    val allFrames: Boolean = false,
    val matchAboutBlank: Boolean = false,
    val matchOriginAsFallback: Boolean = false,
    val world: String = "ISOLATED",
    val persist: Boolean = true,
    val enabled: Boolean = true
) {
    fun matchesUrl(urlStr: String, targetOrigin: String? = null): Boolean =
        ExtensionGlobMatcher.matchesUrl(
            urlStr = urlStr,
            targetOrigin = targetOrigin,
            matches = matches,
            excludeMatches = excludeMatches,
            includeGlobs = includeGlobs,
            excludeGlobs = excludeGlobs,
            matchAboutBlank = matchAboutBlank,
            matchOriginAsFallback = matchOriginAsFallback
        )
}
