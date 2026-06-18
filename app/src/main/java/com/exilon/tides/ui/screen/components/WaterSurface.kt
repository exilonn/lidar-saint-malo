package com.exilon.tides.ui.screen.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

private val TWO_PI = (2.0 * PI).toFloat()

/**
 * A stylized, fully procedural water surface filling the lower part of the screen. The waterline
 * sits at the current tide [level] (0 = today's lowest extreme, 1 = highest), animates up to that
 * level when the screen opens, ripples with two summed sine waves, and slowly drifts in the
 * rising/falling direction. [accent] (the rising/falling colour) tints it.
 */
@Composable
fun WaterSurface(
    level: Float,
    rising: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val levelAnim = remember { Animatable(0f) }
    LaunchedEffect(level) {
        levelAnim.animateTo(level.coerceIn(0f, 1f), tween(1400, easing = FastOutSlowInEasing))
    }

    val transition = rememberInfiniteTransition(label = "water")
    val phaseFront by transition.animateFloat(
        0f, TWO_PI, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "phaseFront",
    )
    val phaseBack by transition.animateFloat(
        0f, TWO_PI, infiniteRepeatable(tween(8300, easing = LinearEasing)), label = "phaseBack",
    )
    val drift by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "drift",
    )

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val trend = if (rising) -1f else 1f // rising water drifts up (smaller y)
        val driftPx = (drift - 0.5f) * 2f * trend * (h * 0.012f)
        val baseY = ((1f - levelAnim.value) * h + driftPx).coerceIn(0f, h)

        drawWave(w, h, baseY - h * 0.02f, amp = h * 0.012f, phase = phaseBack, periods = 2, color = accent.copy(alpha = 0.16f))
        drawWave(w, h, baseY, amp = h * 0.02f, phase = phaseFront, periods = 3, color = accent.copy(alpha = 0.34f))
    }
}

/**
 * One wave layer. [periods] is a whole number of wavelengths across the width and the time term
 * enters only as `+ phase`, so the shape is continuous across the canvas (left edge == right edge)
 * and the frame at phase = 2π is identical to phase = 0 — a seamless loop with RepeatMode.Restart.
 */
private fun DrawScope.drawWave(
    w: Float,
    h: Float,
    lineY: Float,
    amp: Float,
    phase: Float,
    periods: Int,
    color: Color,
) {
    fun yAt(nx: Float): Float =
        lineY + amp * sin(periods * nx * TWO_PI + phase) + amp * 0.4f * sin(2 * periods * nx * TWO_PI + phase)

    val path = Path().apply {
        moveTo(0f, yAt(0f))
        var x = 0f
        val step = 8f
        while (x <= w) {
            lineTo(x, yAt(x / w))
            x += step
        }
        lineTo(w, yAt(1f))
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(
        path,
        brush = Brush.verticalGradient(
            listOf(color, color.copy(alpha = color.alpha * 0.25f)),
            startY = lineY,
            endY = h,
        ),
    )
}
