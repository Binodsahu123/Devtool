package com.swift.browser.extensionengine

import org.json.JSONArray

/**
 * Shared JavaScript URL guard compiler used by manifest content scripts and USER_SCRIPT.
 * WebView's native origin allow-list handles the coarse boundary; this guard preserves Chrome
 * match-pattern paths and exclusions without creating a second pattern implementation.
 */
object ExtensionMatchGuardJs {
    fun build(
        matches: List<String>,
        excludeMatches: List<String> = emptyList(),
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList(),
        matchAboutBlank: Boolean = false,
        matchOriginAsFallback: Boolean = false,
        requireTopFrame: Boolean = false
    ): String {
        val matchRegexes = matches.map { HostPattern(it).toJsRegexString() }
        val excludeRegexes = excludeMatches.map { HostPattern(it).toJsRegexString() }
        val includeRegexes = includeGlobs.map { ExtensionGlobMatcher.globToJsRegex(it) }
        val excludeGlobRegexes = excludeGlobs.map { ExtensionGlobMatcher.globToJsRegex(it) }
        val matchesJson = JSONArray().apply { matchRegexes.forEach(::put) }
        val excludesJson = JSONArray().apply { excludeRegexes.forEach(::put) }
        val includesJson = JSONArray().apply { includeRegexes.forEach(::put) }
        val excludedGlobsJson = JSONArray().apply { excludeGlobRegexes.forEach(::put) }
        return """
            (function(){
              try {
                var u = String(location.href || "");
                var related = (u.indexOf("about:blank") === 0 ||
                               u.indexOf("about:srcdoc") === 0 ||
                               u.indexOf("data:") === 0 ||
                               u.indexOf("blob:") === 0 ||
                               u.indexOf("filesystem:") === 0);
                var ok = false;
                if (!related) {
                  var ms = $matchesJson;
                  ok = ms.some(function(r){
                    try { return new RegExp(r, "i").test(u); } catch(e) { return false; }
                  });
                } else if (${if (matchAboutBlank || matchOriginAsFallback) "true" else "false"}) {
                  try {
                    var parent = document.referrer || "";
                    var ms2 = $matchesJson;
                    ok = !!parent && ms2.some(function(r){
                      try { return new RegExp(r, "i").test(parent); } catch(e) { return false; }
                    });
                  } catch(e) {}
                }
                if (!ok) return false;
                var es = $excludesJson;
                if (es.some(function(r){
                  try { return new RegExp(r, "i").test(u); } catch(e) { return false; }
                })) return false;
                var ig = $includesJson;
                if (ig.length && !ig.some(function(r){
                  try { return new RegExp(r, "i").test(u); } catch(e) { return false; }
                })) return false;
                var eg = $excludedGlobsJson;
                if (eg.some(function(r){
                  try { return new RegExp(r, "i").test(u); } catch(e) { return false; }
                })) return false;
                ${if (requireTopFrame) "if (window.top !== window) return false;" else ""}
                return true;
              } catch(e) { return false; }
            })()
        """.trimIndent()
    }
}
