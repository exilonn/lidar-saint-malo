package com.exilon.tides.ui.screen.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val TWO_PI = (2.0 * PI).toFloat()

/** On-theme indeterminate loader: a circle filling with a gently rippling, bobbing tide. */
@Composable
fun WaveLoadingIndicator(color: Color, modifier: Modifier = Modifier.size(96.dp)) {
    val transition = rememberInfiniteTransition(label = "loading")
    val phase by transition.animateFloat(
        0f, TWO_PI, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "phase",
    )
    val level by transition.animateFloat(
        0.40f, 0.64f,
        infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "level",
    )

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val circle = Path().apply {
            addOval(Rect(center.x - r, center.y - r, center.x + r, center.y + r))
        }
        drawCircle(color.copy(alpha = 0.25f), radius = r, center = center, style = Stroke(width = 3f))
        clipPath(circle) {
            val baseY = (1f - level) * size.height
            val amp = size.minDimension * 0.05f
            val wave = Path().apply {
                moveTo(0f, baseY)
                var x = 0f
                val step = 6f
                while (x <= size.width) {
                    lineTo(x, baseY + amp * sin(2f * (x / size.width) * TWO_PI + phase))
                    x += step
                }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(wave, color.copy(alpha = 0.55f))
        }
    }
}
