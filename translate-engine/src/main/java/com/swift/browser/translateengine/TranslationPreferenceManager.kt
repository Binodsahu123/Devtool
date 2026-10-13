package com.swift.browser.translateengine

import android.content.Context
import android.content.SharedPreferences

object TranslationPreferenceManager {
    const val PREFS_NAME = "swift_translation_preferences"
    const val KEY_TARGET_LANG_CODE = "pref_target_lang_code"
    const val KEY_TARGET_LANG_NAME = "pref_target_lang_name"
    const val KEY_AUTO_TRANSLATE = "pref_auto_translate"
    const val KEY_ACTIVE_DOMAINS = "pref_active_domains"
    const val KEY_OFFER_TRANSLATE = "pref_offer_translate"
    const val KEY_NEVER_TRANSLATE_SITES = "pref_never_translate_sites"
    const val KEY_NEVER_TRANSLATE_LANGUAGES = "pref_never_translate_languages"

    private const val OLD_PREFS_NAME = "swift_translate_settings"

    private fun getPrefs(context: Context): SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateIfNeeded(context, prefs)
        return prefs
    }

    private fun migrateIfNeeded(context: Context, newPrefs: SharedPreferences) {
        val oldPrefs = context.applicationContext.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
        val oldAll = oldPrefs.all
        if (oldAll.isNotEmpty()) {
            val editor = newPrefs.edit()
            if (!newPrefs.contains(KEY_AUTO_TRANSLATE) && oldPrefs.contains("key_auto_translate")) {
                editor.putBoolean(KEY_AUTO_TRANSLATE, oldPrefs.getBoolean("key_auto_translate", false))
            }
            if (!newPrefs.contains(KEY_TARGET_LANG_CODE) && oldPrefs.contains("key_default_target_lang")) {
                val lang = oldPrefs.getString("key_default_target_lang", "en") ?: "en"
                editor.putString(KEY_TARGET_LANG_CODE, lang)
            }
            if (!newPrefs.contains(KEY_OFFER_TRANSLATE) && oldPrefs.contains("key_offer_translate")) {
                editor.putBoolean(KEY_OFFER_TRANSLATE, oldPrefs.getBoolean("key_offer_translate", true))
            }
            if (!newPrefs.contains(KEY_ACTIVE_DOMAINS) && oldPrefs.contains("key_always_translate_sites")) {
                editor.putStringSet(KEY_ACTIVE_DOMAINS, oldPrefs.getStringSet("key_always_translate_sites", emptySet()))
            }
            if (!newPrefs.contains(KEY_NEVER_TRANSLATE_SITES) && oldPrefs.contains("key_never_translate_sites")) {
                editor.putStringSet(KEY_NEVER_TRANSLATE_SITES, oldPrefs.getStringSet("key_never_translate_sites", emptySet()))
            }
            if (!newPrefs.contains(KEY_NEVER_TRANSLATE_LANGUAGES) && oldPrefs.contains("key_never_translate_languages")) {
                editor.putStringSet(KEY_NEVER_TRANSLATE_LANGUAGES, oldPrefs.getStringSet("key_never_translate_languages", emptySet()))
            }
            editor.apply()
        }
    }

    fun getTargetLanguageCode(context: Context): String {
        return getPrefs(context).getString(KEY_TARGET_LANG_CODE, "en") ?: "en"
    }

    fun getTargetLanguageName(context: Context): String {
        return getPrefs(context).getString(KEY_TARGET_LANG_NAME, "English") ?: "English"
    }

    fun saveTargetLanguage(context: Context, langCode: String, langName: String) {
        getPrefs(context).edit()
            .putString(KEY_TARGET_LANG_CODE, langCode)
            .putString(KEY_TARGET_LANG_NAME, langName)
            .apply()
    }

    fun isAutoTranslateEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_TRANSLATE, false)
    }

    fun setAutoTranslateEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_TRANSLATE, enabled).apply()
    }

    fun isOfferToTranslateEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OFFER_TRANSLATE, true)
    }

    fun setOfferToTranslateEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OFFER_TRANSLATE, enabled).apply()
    }

    fun getPersistedActiveDomains(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_ACTIVE_DOMAINS, emptySet()) ?: emptySet()
    }

    fun addPersistedActiveDomain(context: Context, domain: String) {
        if (domain.isEmpty()) return
        val current = getPersistedActiveDomains(context).toMutableSet()
        current.add(domain)
        getPrefs(context).edit().putStringSet(KEY_ACTIVE_DOMAINS, current).apply()
    }

    fun removePersistedActiveDomain(context: Context, domain: String) {
        if (domain.isEmpty()) return
        val current = getPersistedActiveDomains(context).toMutableSet()
        current.remove(domain)
        getPrefs(context).edit().putStringSet(KEY_ACTIVE_DOMAINS, current).apply()
    }

    fun getNeverTranslateSites(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_NEVER_TRANSLATE_SITES, emptySet()) ?: emptySet()
    }

    fun addNeverTranslateSite(context: Context, host: String) {
        if (host.isEmpty()) return
        val current = getNeverTranslateSites(context).toMutableSet()
        current.add(host)
        getPrefs(context).edit().putStringSet(KEY_NEVER_TRANSLATE_SITES, current).apply()
    }

    fun removeNeverTranslateSite(context: Context, host: String) {
        if (host.isEmpty()) return
        val current = getNeverTranslateSites(context).toMutableSet()
        current.remove(host)
        getPrefs(context).edit().putStringSet(KEY_NEVER_TRANSLATE_SITES, current).apply()
    }

    fun getNeverTranslateLanguages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_NEVER_TRANSLATE_LANGUAGES, emptySet()) ?: emptySet()
    }

    fun addNeverTranslateLanguage(context: Context, langCode: String) {
        if (langCode.isEmpty()) return
        val current = getNeverTranslateLanguages(context).toMutableSet()
        current.add(langCode)
        getPrefs(context).edit().putStringSet(KEY_NEVER_TRANSLATE_LANGUAGES, current).apply()
    }

    fun removeNeverTranslateLanguage(context: Context, langCode: String) {
        if (langCode.isEmpty()) return
        val current = getNeverTranslateLanguages(context).toMutableSet()
        current.remove(langCode)
        getPrefs(context).edit().putStringSet(KEY_NEVER_TRANSLATE_LANGUAGES, current).apply()
    }
}
