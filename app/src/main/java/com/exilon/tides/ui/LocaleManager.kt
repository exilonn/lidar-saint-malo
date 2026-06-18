package com.exilon.tides.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the manual language override (BCP-47 tag). Room holds the authoritative value shown in
 * settings; it's mirrored to SharedPreferences here so [android.app.Activity.attachBaseContext] can
 * read it synchronously before the UI inflates. Null = follow the system locale.
 */
object LocaleManager {
    private const val PREFS = "tide_settings"
    private const val KEY_LANG = "lang_tag"

    fun persistedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null)

    fun store(context: Context, tag: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (tag.isNullOrBlank()) editor.remove(KEY_LANG) else editor.putString(KEY_LANG, tag)
        editor.apply()
    }

    /** Wraps [context] with the overridden locale, if any. */
    fun wrap(context: Context): Context {
        val tag = persistedTag(context) ?: return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
