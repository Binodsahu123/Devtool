package com.swift.browser.extensionengine

import java.util.regex.Pattern

class HostPattern(val pattern: String) {
    val rawPattern: String = pattern.trim()
    val isAllUrls: Boolean = rawPattern == "<all_urls>"
    val isFilePattern: Boolean = rawPattern.startsWith("file:///")

    val scheme: String
    val host: String
    val path: String

    init {
        if (isAllUrls) {
            scheme = "*"
            host = "*"
            path = "/*"
        } else if (isFilePattern) {
            scheme = "file"
            host = ""
            path = rawPattern.removePrefix("file://")
        } else {
            val schemeSep = rawPattern.indexOf("://")
            if (schemeSep > 0) {
                scheme = rawPattern.substring(0, schemeSep).lowercase()
                val remainder = rawPattern.substring(schemeSep + 3)
                val pathSep = remainder.indexOf('/')
                if (pathSep >= 0) {
                    host = remainder.substring(0, pathSep).lowercase()
                    path = remainder.substring(pathSep)
                } else {
                    host = remainder.lowercase()
                    path = "/*"
                }
            } else {
                scheme = "*"
                host = "*"
                path = "/*"
            }
        }
    }

    fun matchesUrl(urlStr: String): Boolean {
        if (urlStr.isBlank()) return false
        if (isAllUrls) return true

        val trimmed = urlStr.trim()
        if (isFilePattern) {
            if (!trimmed.startsWith("file:///")) return false
            val urlPath = trimmed.removePrefix("file://")
            if (path == "/*" || path == "*") return true
            val cleanUrlPath = urlPath.substringBefore('?').substringBefore('#')
            val regexStr = "^" + Pattern.quote(path).replace("*", "\\E.*\\Q") + "$"
            return try {
                val compiled = Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE)
                compiled.matcher(urlPath).matches() || compiled.matcher(cleanUrlPath).matches()
            } catch (e: Exception) {
                false
            }
        }

        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) return false

        val urlScheme = trimmed.substring(0, schemeSep).lowercase()
        val remainder = trimmed.substring(schemeSep + 3)
        val pathSep = remainder.indexOf('/')
        val urlHostWithPort = (if (pathSep >= 0) remainder.substring(0, pathSep) else remainder).lowercase()
        val urlHostNoPort = if (urlHostWithPort.contains(':')) urlHostWithPort.substringBefore(':') else urlHostWithPort
        val urlPath = if (pathSep >= 0) remainder.substring(pathSep) else "/"
        val cleanUrlPath = urlPath.substringBefore('?').substringBefore('#')

        // Scheme check
        if (scheme != "*") {
            if (scheme != urlScheme) return false
        } else {
            if (urlScheme != "http" && urlScheme != "https") return false
        }

        // Host check
        if (host != "*") {
            val expectedHostNoPort = if (host.contains(':')) host.substringBefore(':') else host
            val expectedPort = if (host.contains(':')) host.substringAfter(':') else null
            val urlPort = if (urlHostWithPort.contains(':')) urlHostWithPort.substringAfter(':') else null

            if (expectedPort != null && expectedPort != urlPort) {
                return false
            }

            if (expectedHostNoPort.startsWith("*.")) {
                val domain = expectedHostNoPort.substring(2)
                if (urlHostNoPort != domain && !urlHostNoPort.endsWith(".$domain")) return false
            } else if (expectedHostNoPort != urlHostNoPort) {
                return false
            }
        }

        // Path check
        if (path == "/*" || path == "*") return true

        val regexStr = "^" + Pattern.quote(path).replace("*", "\\E.*\\Q") + "$"
        return try {
            val compiled = Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE)
            if (compiled.matcher(urlPath).matches() || compiled.matcher(cleanUrlPath).matches()) {
                true
            } else {
                // If path doesn't contain explicit query or fragment, allow trailing query/hash
                val extendedRegex = "^" + Pattern.quote(path).replace("*", "\\E.*\\Q") + "(?:[?#].*)?$"
                Pattern.compile(extendedRegex, Pattern.CASE_INSENSITIVE).matcher(urlPath).matches()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun toRegexString(): String {
        if (isAllUrls) {
            return "^(?:https?|file|ftp)://.*$"
        }
        val schemeRx = when (scheme) {
            "*" -> "https?"
            else -> Pattern.quote(scheme)
        }
        val hostRx = when {
            host == "*" -> "[^/]+"
            host.startsWith("*.") -> {
                val domain = host.substring(2)
                "(?:[^/:]+\\.)*${Pattern.quote(domain)}(?::\\d+)?"
            }
            else -> "${Pattern.quote(host)}(?::\\d+)?"
        }
        val pathRx = when {
            path == "/*" || path == "*" -> "(?:/.*)?"
            else -> {
                val parts = path.split("*")
                val escaped = parts.joinToString(".*") { Pattern.quote(it) }
                "(?:$escaped)(?:[?#].*)?"
            }
        }
        return "^$schemeRx://$hostRx$pathRx$"
    }

    fun toJsRegexString(): String {
        if (rawPattern == "<all_urls>" || rawPattern == "*://*/*") {
            return "^(?:https?|http)://"
        }
        fun rxLit(s: String): String =
            s.replace(Regex("[\\\\.^$|()\\[\\]{}+*?]")) {
                if (it.value == "*") ".*" else "\\\\${it.value}"
            }

        val schemeRx = if (scheme == "*" || scheme.isBlank()) "https?" else rxLit(scheme)
        val hostRx = when {
            host == "*" || host.isBlank() -> "[^/]+"
            host.startsWith("*.") -> {
                val domain = host.substring(2)
                "(?:[^/:]+\\.)*" + rxLit(domain) + "(?::\\d+)?"
            }
            else -> rxLit(host) + "(?::\\d+)?"
        }
        val pathRx = if (path == "/*" || path == "*") {
            "(?:/.*)?"
        } else {
            val parts = path.split("*")
            val escaped = parts.joinToString(".*") { p ->
                p.replace(Regex("[\\\\.^$|()\\[\\]{}+?]")) { "\\\\${it.value}" }
            }
            "(?:$escaped)(?:[?#].*)?"
        }
        return "^$schemeRx://$hostRx$pathRx$"
    }
}

data class ExtensionMatchPattern(val patternStr: String) {
    private val hostPattern = HostPattern(patternStr)

    val rawPattern: String get() = hostPattern.rawPattern
    val isAllUrls: Boolean get() = hostPattern.isAllUrls

    fun matches(urlStr: String): Boolean {
        return hostPattern.matchesUrl(urlStr)
    }

    fun toRegex(): Regex = Regex(hostPattern.toRegexString(), RegexOption.IGNORE_CASE)

    fun toJsRegex(): String = hostPattern.toJsRegexString()

    companion object {
        fun matchesAny(urlStr: String, patterns: List<String>): Boolean {
            if (patterns.isEmpty() || urlStr.isBlank()) return false
            return patterns.any { ExtensionMatchPattern(it).matches(urlStr) }
        }

        fun compileToJsRegex(pattern: String): String =
            HostPattern(pattern).toJsRegexString()
    }
}
