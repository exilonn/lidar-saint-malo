package com.exilon.tides.ui.screen.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.exilon.tides.ui.theme.LocalTidePalette
import com.exilon.tides.ui.theme.daylightFactor
import com.exilon.tides.ui.theme.nuancedScene

/**
 * Full-bleed hero gradient from the active palette, with a subtle day/night nuance (slightly
 * lighter by day, darker by night) driven by sunrise/sunset. Colours cross-fade as time passes.
 */
@Composable
fun SkyScene(
    nowMillis: Long,
    sunriseMillis: Long?,
    sunsetMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTidePalette.current
    val daylight = remember(nowMillis, sunriseMillis, sunsetMillis) {
        daylightFactor(nowMillis, sunriseMillis, sunsetMillis)
    }
    val (topTarget, bottomTarget) = nuancedScene(palette, daylight)
    val top by animateColorAsState(topTarget, tween(1500), label = "skyTop")
    val bottom by animateColorAsState(bottomTarget, tween(1500), label = "skyBottom")

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
}
