package com.exilon.tides.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Procedural hero sky palette: (top, horizon) gradient endpoints per time-of-day phase. The tops
// stay mid/deep enough that white hero text floating over them keeps contrast. Used by SkyScene.
val SkyDawnTopLight = Color(0xFF3A5B8C)
val SkyDawnBottomLight = Color(0xFFF4A98C)
val SkyDayTopLight = Color(0xFF3E86C9)
val SkyDayBottomLight = Color(0xFFBFE0F2)
val SkyDuskTopLight = Color(0xFF2E2A55)
val SkyDuskBottomLight = Color(0xFFE07A5F)
val SkyNightTopLight = Color(0xFF0B1430)
val SkyNightBottomLight = Color(0xFF1E335C)

val SkyDawnTopDark = Color(0xFF16223B)
val SkyDawnBottomDark = Color(0xFF6E4A45)
val SkyDayTopDark = Color(0xFF0E2940)
val SkyDayBottomDark = Color(0xFF185074)
val SkyDuskTopDark = Color(0xFF14112B)
val SkyDuskBottomDark = Color(0xFF5E343B)
val SkyNightTopDark = Color(0xFF04060C)
val SkyNightBottomDark = Color(0xFF0F1A30)

// Static fallback schemes for < Android 12 (dynamic color / Material You takes over on 12+).
val LightColors = lightColorScheme(
    primary = Color(0xFF1A6CA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF4F8C86),
    tertiary = Color(0xFF55608F),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF95CCFF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B70),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB1CCC6),
    tertiary = Color(0xFFBDC7F8),
)
