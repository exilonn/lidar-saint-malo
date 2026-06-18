package com.exilon.tides.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Display-only user preferences (units, clock) shared across the entire Compose tree. */
data class TidePrefs(
    val useMetric: Boolean = true,
    val use24h: Boolean = true,
)

val LocalTidePrefs = staticCompositionLocalOf { TidePrefs() }
