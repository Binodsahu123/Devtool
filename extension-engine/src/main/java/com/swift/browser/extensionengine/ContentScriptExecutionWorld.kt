package com.swift.browser.extensionengine

/** Chrome content-script execution worlds. ISOLATED is the default. */
enum class ContentScriptExecutionWorld {
    ISOLATED,
    MAIN,
    USER_SCRIPT;

    companion object {
        fun fromString(value: String?): ContentScriptExecutionWorld = when {
            value?.trim()?.equals("MAIN", ignoreCase = true) == true -> MAIN
            value?.trim()?.equals("USER_SCRIPT", ignoreCase = true) == true -> USER_SCRIPT
            else -> ISOLATED
        }
    }
}

/** Source-compatible alias; new code must use ContentScriptExecutionWorld. */
typealias ContentScriptWorld = ContentScriptExecutionWorld

const val SCRIPTING_WORLD_UNSUPPORTED = "SCRIPTING_WORLD_UNSUPPORTED"
const val CONTENT_SCRIPT_CSS_UNSUPPORTED = "CONTENT_SCRIPT_CSS_UNSUPPORTED"
const val CONTENT_SCRIPT_TIMING_UNSUPPORTED = "CONTENT_SCRIPT_TIMING_UNSUPPORTED"
const val ISOLATED_WORLD_UNAVAILABLE = "ISOLATED_WORLD_UNAVAILABLE"
