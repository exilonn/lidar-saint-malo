package com.exilon.tides.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.Instant
import java.time.ZoneId

/** A curated, complete palette that drives BOTH the Compose app and the Glance widget. */
enum class ThemeId(val key: String) {
    OCEAN("ocean"),
    MARINE_GOLD("marine_gold");

    companion object {
        fun from(key: String?): ThemeId = entries.firstOrNull { it.key == key } ?: OCEAN
    }
}

data class TidePalette(
    val id: ThemeId,
    val displayName: String,
    val scheme: ColorScheme,
    // Hero scene gradient (a subtle day/night nuance is applied on top).
    val skyTop: Color,
    val skyBottom: Color,
    // Tide-direction accent.
    val rising: Color,
    val falling: Color,
    // Text/markers floating over the scene.
    val onScene: Color,
    // Widget surface (same identity as the app).
    val widgetBgTop: Color,
    val widgetBgBottom: Color,
    val widgetText: Color,
    val widgetSecondary: Color,
    // Tide map basemap colours (drive the MapLibre style at runtime, see TideMapController).
    val mapWater: Color,
    val mapLand: Color,
) {
    fun tideAccent(isRising: Boolean): Color = if (isRising) rising else falling
}

/** Read the active palette anywhere in the tree (provided by [TideTheme]). */
val LocalTidePalette = staticCompositionLocalOf { Palettes.Ocean }

object Palettes {

    val Ocean = TidePalette(
        id = ThemeId.OCEAN,
        displayName = "Ocean",
        scheme = darkColorScheme(
            primary = Color(0xFF6FB6FF),
            onPrimary = Color(0xFF00263F),
            primaryContainer = Color(0xFF0E3A57),
            onPrimaryContainer = Color(0xFFCDE5FF),
            secondary = Color(0xFF8FB3CC),
            tertiary = Color(0xFFF2B36B),
            background = Color(0xFF0B1722),
            onBackground = Color(0xFFE6F0F7),
            surface = Color(0xFF0F1E2E),
            onSurface = Color(0xFFE6F0F7),
            surfaceVariant = Color(0xFF1B2C3C),
            onSurfaceVariant = Color(0xFFA9BECE),
            outline = Color(0xFF3A4D5E),
            outlineVariant = Color(0xFF26384A),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            inverseSurface = Color(0xFFE6F0F7),
            inverseOnSurface = Color(0xFF0F1E2E),
        ),
        skyTop = Color(0xFF0E2A45),
        skyBottom = Color(0xFF16415F),
        rising = Color(0xFF5BA8FF),
        falling = Color(0xFFF2B36B),
        onScene = Color.White,
        widgetBgTop = Color(0xFF0E2940),
        widgetBgBottom = Color(0xFF1C5374),
        widgetText = Color.White,
        widgetSecondary = Color(0xFFB9C7D2),
        mapWater = Color(0xFF15425F),
        mapLand = Color(0xFF0E1C29),
    )

    val MarineGold = TidePalette(
        id = ThemeId.MARINE_GOLD,
        displayName = "Marine & Gold",
        scheme = darkColorScheme(
            primary = Color(0xFFE3B341),
            onPrimary = Color(0xFF2A2000),
            primaryContainer = Color(0xFF5A4500),
            onPrimaryContainer = Color(0xFFFFE08A),
            secondary = Color(0xFFC9A24B),
            tertiary = Color(0xFF7FB2E8),
            background = Color(0xFF081726),
            onBackground = Color(0xFFEAF0F6),
            surface = Color(0xFF10243A),
            onSurface = Color(0xFFEAF0F6),
            surfaceVariant = Color(0xFF1C3350),
            onSurfaceVariant = Color(0xFFAFC0CE),
            outline = Color(0xFF3E5A78),
            outlineVariant = Color(0xFF27405A),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            inverseSurface = Color(0xFFEAF0F6),
            inverseOnSurface = Color(0xFF10243A),
        ),
        skyTop = Color(0xFF0A1E33),
        skyBottom = Color(0xFF143A5A),
        rising = Color(0xFF7FB2E8),
        falling = Color(0xFFE3B341),
        onScene = Color.White,
        widgetBgTop = Color(0xFF0A1E33),
        widgetBgBottom = Color(0xFF143A5A),
        widgetText = Color.White,
        widgetSecondary = Color(0xFFAFC0CE),
        mapWater = Color(0xFF143A5A),
        mapLand = Color(0xFF0C1A2A),
    )

    val all = listOf(Ocean, MarineGold)

    fun byId(id: ThemeId): TidePalette = when (id) {
        ThemeId.OCEAN -> Ocean
        ThemeId.MARINE_GOLD -> MarineGold
    }
}

/**
 * 0 at deep night → 1 in full daylight, with soft ramps around sunrise/sunset. Drives the subtle
 * day/night scene nuance.
 */
fun daylightFactor(
    nowMillis: Long,
    sunriseMillis: Long?,
    sunsetMillis: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): Float {
    val m = minutesOfDay(nowMillis, zone)
    val sr = sunriseMillis?.let { minutesOfDay(it, zone) } ?: (6 * 60)
    val ss = sunsetMillis?.let { minutesOfDay(it, zone) } ?: (20 * 60)
    val edge = 60
    return when {
        m <= sr - edge || m >= ss + edge -> 0f
        m in (sr + edge)..(ss - edge) -> 1f
        m < sr + edge -> ((m - (sr - edge)).toFloat() / (2 * edge)).coerceIn(0f, 1f)
        else -> (1f - (m - (ss - edge)).toFloat() / (2 * edge)).coerceIn(0f, 1f)
    }
}

/** A subtle (±10%) lighten-by-day / darken-by-night nuance of the palette's scene gradient. */
fun nuancedScene(palette: TidePalette, daylight: Float): Pair<Color, Color> {
    val day = daylight.coerceIn(0f, 1f)
    fun adjust(c: Color): Color =
        lerp(lerp(c, Color.White, 0.10f * day), Color.Black, 0.10f * (1f - day))
    return adjust(palette.skyTop) to adjust(palette.skyBottom)
}

private fun minutesOfDay(millis: Long, zone: ZoneId): Int {
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    return t.hour * 60 + t.minute
}
