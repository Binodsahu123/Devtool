package com.swift.browser.translateengine

import android.content.Context

class TranslateSettings(context: Context) {
    private val appContext = context.applicationContext

    fun isAutoTranslateEnabled(): Boolean {
        return TranslationPreferenceManager.isAutoTranslateEnabled(appContext)
    }

    fun setAutoTranslateEnabled(enabled: Boolean) {
        TranslationPreferenceManager.setAutoTranslateEnabled(appContext, enabled)
    }

    fun getDefaultTargetLanguage(): String {
        return TranslationPreferenceManager.getTargetLanguageCode(appContext)
    }

    fun setDefaultTargetLanguage(langCode: String) {
        val langName = TranslateLanguageManager(appContext).getLanguageDisplayName(langCode)
        TranslationPreferenceManager.saveTargetLanguage(appContext, langCode, langName)
    }

    fun isOfferToTranslateEnabled(): Boolean {
        return TranslationPreferenceManager.isOfferToTranslateEnabled(appContext)
    }

    fun setOfferToTranslateEnabled(enabled: Boolean) {
        TranslationPreferenceManager.setOfferToTranslateEnabled(appContext, enabled)
    }

    fun addNeverTranslateSite(host: String) {
        TranslationPreferenceManager.addNeverTranslateSite(appContext, host)
    }

    fun removeNeverTranslateSite(host: String) {
        TranslationPreferenceManager.removeNeverTranslateSite(appContext, host)
    }

    fun getNeverTranslateSites(): Set<String> {
        return TranslationPreferenceManager.getNeverTranslateSites(appContext)
    }

    fun addAlwaysTranslateSite(host: String) {
        TranslationPreferenceManager.addPersistedActiveDomain(appContext, host)
    }

    fun removeAlwaysTranslateSite(host: String) {
        TranslationPreferenceManager.removePersistedActiveDomain(appContext, host)
    }

    fun getAlwaysTranslateSites(): Set<String> {
        return TranslationPreferenceManager.getPersistedActiveDomains(appContext)
    }

    fun addNeverTranslateLanguage(langCode: String) {
        TranslationPreferenceManager.addNeverTranslateLanguage(appContext, langCode)
    }

    fun removeNeverTranslateLanguage(langCode: String) {
        TranslationPreferenceManager.removeNeverTranslateLanguage(appContext, langCode)
    }

    fun getNeverTranslateLanguages(): Set<String> {
        return TranslationPreferenceManager.getNeverTranslateLanguages(appContext)
    }
}
