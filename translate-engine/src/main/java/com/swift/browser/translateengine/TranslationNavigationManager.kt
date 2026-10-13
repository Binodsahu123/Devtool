package com.swift.browser.translateengine

import android.content.Context
import android.webkit.WebView
import android.util.Log

object TranslationNavigationManager {
    
    /**
     * Checks if a newly navigated URL should be translated automatically.
     * If so, returns the target language code. Otherwise returns null.
     */
    fun shouldAutoTranslate(context: Context, url: String?): String? {
        if (url == null || url.trim().isEmpty() || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("swift:")) return null
        
        // Auto-translate is OFF by default. Only check if explicitly enabled in user settings.
        if (!TranslationPreferenceManager.isAutoTranslateEnabled(context)) {
            return null
        }

        try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return null

            // Respect never-translate sites list
            val neverSites = TranslationPreferenceManager.getNeverTranslateSites(context)
            if (neverSites.contains(host)) {
                return null
            }
            
            // Check session manager
            if (TranslationSessionManager.isDomainTranslationActive(host)) {
                return TranslationSessionManager.getDomainTargetLanguageCode(host)
            }
            
            // Check persistent preference manager
            val persistedSet = TranslationPreferenceManager.getPersistedActiveDomains(context)
            if (persistedSet.contains(host)) {
                return TranslationPreferenceManager.getTargetLanguageCode(context)
            }
        } catch (e: Exception) {
            Log.e("TranslationNavManager", "Error parsing host from url: $url", e)
        }
        
        return null
    }

    /**
     * Handles navigation events on the WebView to manage sessions.
     */
    fun handlePageStarted(context: Context, tabId: String, url: String?) {
        if (url == null) return
        try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: ""
            if (host.isNotEmpty()) {
                TranslationSessionManager.updateSessionDomain(tabId, host)
            }
        } catch (e: Exception) {
            Log.e("TranslationNavManager", "handlePageStarted error", e)
        }
    }
}
