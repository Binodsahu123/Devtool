package com.swift.browser.extensionengine

/**
 * Canonical WebExtension content-script URL/glob matcher.
 *
 * Both parsed manifest content scripts and normalized runtime definitions use this
 * exact implementation so matching semantics cannot drift between the two models.
 */
object ExtensionGlobMatcher {

    fun matchesUrl(
        urlStr: String,
        targetOrigin: String?,
        matches: List<String>,
        excludeMatches: List<String>,
        includeGlobs: List<String>,
        excludeGlobs: List<String>,
        matchAboutBlank: Boolean,
        matchOriginAsFallback: Boolean
    ): Boolean {
        if (urlStr.isBlank()) return false

        var urlToTest = urlStr
        if (urlStr == "about:blank" || urlStr.startsWith("about:")) {
            if (!matchAboutBlank && !matchOriginAsFallback) return false
            if (targetOrigin != null && targetOrigin.isNotBlank()) {
                urlToTest = targetOrigin
            } else {
                return false
            }
        }

        if (!ExtensionMatchPattern.matchesAny(urlToTest, matches)) return false

        if (excludeMatches.isNotEmpty() && ExtensionMatchPattern.matchesAny(urlToTest, excludeMatches)) {
            return false
        }

        if (includeGlobs.isNotEmpty() && includeGlobs.none { globToRegex(it).containsMatchIn(urlToTest) }) {
            return false
        }

        if (excludeGlobs.isNotEmpty() && excludeGlobs.any { globToRegex(it).containsMatchIn(urlToTest) }) {
            return false
        }

        return true
    }

    fun globToJsRegex(glob: String): String {
        val sb = StringBuilder("^")
        for (char in glob) {
            when (char) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '\\', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|' ->
                    sb.append('\\').append(char)
                else -> sb.append(char)
            }
        }
        return sb.append('$').toString()
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (char in glob) {
            when (char) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.' -> sb.append("\\.")
                '\\' -> sb.append("\\\\")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '[' -> sb.append("\\[")
                ']' -> sb.append("\\]")
                '{' -> sb.append("\\{")
                '}' -> sb.append("\\}")
                '+' -> sb.append("\\+")
                '^' -> sb.append("\\^")
                '$' -> sb.append("\\$")
                '|' -> sb.append("\\|")
                else -> sb.append(char)
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
