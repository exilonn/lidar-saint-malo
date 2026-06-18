package com.exilon.tides.ui

import android.content.Context

/** Lightweight SharedPreferences wrapper for display-only user preferences (units, clock format).
 *  These don't affect stored data, so SharedPreferences is sufficient — no Room migration needed. */
object UserPrefs {
    private const val PREFS = "tide_settings"
    private const val KEY_METRIC = "use_metric"
    private const val KEY_24H = "use_24h"

    fun useMetric(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_METRIC, true)

    fun use24h(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_24H, true)

    fun setMetric(context: Context, v: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_METRIC, v).apply()

    fun set24h(context: Context, v: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_24H, v).apply()
}
