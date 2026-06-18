package com.exilon.tides.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * One design language for the whole app. The curated [palette] (chosen in settings, persisted in
 * Room) drives the Material colour scheme AND is exposed via [LocalTidePalette] so the hero scene,
 * tide accents and the widget all read the same colours. No Material You here — the point is that
 * app and widget look identical.
 */
@Composable
fun TideTheme(palette: TidePalette = Palettes.Ocean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTidePalette provides palette) {
        MaterialTheme(
            colorScheme = palette.scheme,
            typography = TideTypography,
            content = content,
        )
    }
}
