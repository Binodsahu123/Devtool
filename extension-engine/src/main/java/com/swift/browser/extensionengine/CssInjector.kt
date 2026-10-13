package com.swift.browser.extensionengine

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a registered CSS injection record as specified in PRD Section 11.
 */
data class CssInjectionRecord(
    val extensionId: String,
    val tabId: String,
    val frameId: Int = 0,
    val documentId: String = "",
    val key: String,
    val styleElementId: String,
    val sourceHash: Int,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Handles CSS injection into WebView instances by dynamically creating
 * and attaching <style> elements to the DOM, with deterministic resource lifecycle tracking.
 */
class CssInjector {
    private val records = ConcurrentHashMap<String, CssInjectionRecord>()

    fun registerRecord(record: CssInjectionRecord) {
        records[record.key] = record
    }

    fun getRecord(key: String): CssInjectionRecord? = records[key]

    fun removeRecord(key: String): CssInjectionRecord? = records.remove(key)

    fun clearTabRecords(tabId: String) {
        records.entries.removeIf { it.value.tabId == tabId }
    }

    fun clearDocumentRecords(documentId: String) {
        if (documentId.isNotBlank()) {
            records.entries.removeIf { it.value.documentId == documentId }
        }
    }

    fun createRecord(
        extensionId: String,
        tabId: String,
        frameId: Int = 0,
        documentId: String = "",
        key: String,
        cssContent: String
    ): CssInjectionRecord {
        val record = CssInjectionRecord(
            extensionId = extensionId,
            tabId = tabId,
            frameId = frameId,
            documentId = documentId,
            key = key,
            styleElementId = key,
            sourceHash = cssContent.hashCode()
        )
        registerRecord(record)
        return record
    }

    fun injectCss(evaluator: ScriptEvaluator, cssContent: String) {
        if (cssContent.isBlank()) return
        val escapedCss = JSONObject.quote(cssContent)
        val js = """
            (function() {
                try {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.appendChild(document.createTextNode($escapedCss));
                    (document.head || document.documentElement || document.body).appendChild(style);
                } catch(e) {
                    console.error("CssInjector error: ", e);
                }
            })();
        """.trimIndent()
        evaluator.evaluateJavascript(js, null)
    }

    fun injectCssWithKey(evaluator: ScriptEvaluator, styleKey: String, cssContent: String, onComplete: (() -> Unit)? = null) {
        if (cssContent.isBlank()) {
            onComplete?.invoke()
            return
        }
        val escapedKey = JSONObject.quote(styleKey)
        val escapedCss = JSONObject.quote(cssContent)
        val js = """
            (function() {
                try {
                    var existing = document.getElementById($escapedKey);
                    if (existing) { existing.remove(); }
                    var style = document.createElement('style');
                    style.id = $escapedKey;
                    style.type = 'text/css';
                    style.appendChild(document.createTextNode($escapedCss));
                    (document.head || document.documentElement || document.body).appendChild(style);
                } catch(e) {
                    console.error("CssInjector error with key: ", e);
                }
            })();
        """.trimIndent()
        evaluator.evaluateJavascript(js) {
            onComplete?.invoke()
        }
    }

    fun removeCssByKey(evaluator: ScriptEvaluator, styleKey: String, onComplete: (() -> Unit)? = null) {
        removeRecord(styleKey)
        val escapedKey = JSONObject.quote(styleKey)
        val js = """
            (function() {
                try {
                    var el = document.getElementById($escapedKey);
                    if (el) { el.remove(); }
                } catch(e) {}
            })();
        """.trimIndent()
        evaluator.evaluateJavascript(js) {
            onComplete?.invoke()
        }
    }
}

