package com.exilon.tides.ui.screen.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.exilon.tides.R
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideMath
import com.exilon.tides.data.model.TideType
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.theme.LocalTidePrefs
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * A 24-hour tide curve drawn entirely with Compose Canvas — no chart library. The smooth shape
 * is sampled from [TideMath.curveSamples] (half-cosine interpolation of the extremes, plotted on
 * a time-proportional x-axis), filled with a gradient, and annotated with high/low dots, their
 * times, and a movable indicator.
 *
 * The window runs from 3h before now to 21h ahead. On first show the curve draws itself in
 * left-to-right. Drag horizontally to scrub: the indicator follows your finger and a tooltip
 * shows the interpolated height and time at that point; releasing animates it back to "now".
 * [accent] tints the line/fill/dots to match the rising/falling state.
 */
@Composable
fun TideCurveCanvas(
    extremes: List<TideExtreme>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val windowStart = nowMillis - 3 * 3_600_000L
    val windowEnd = nowMillis + 21 * 3_600_000L
    val windowSpan = windowEnd - windowStart

    // "Now" always sits 3h into the 24h window, i.e. at this fixed fraction of the width.
    val nowFraction = 3f / 24f

    val samples = remember(extremes, windowStart) {
        TideMath.curveSamples(extremes, windowStart, windowEnd, samples = 160)
    }
    val windowExtremes = remember(extremes, windowStart) {
        extremes.filter { it.timeMillis in windowStart..windowEnd }
    }
    // Every local midnight inside the visible window, to mark the date change with a gridline.
    val midnights = remember(windowStart, windowEnd, zone) {
        val firstDate = Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate()
        val lastDate = Instant.ofEpochMilli(windowEnd).atZone(zone).toLocalDate()
        generateSequence(firstDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDate.plusDays(1)) }
            .map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            .filter { it in windowStart..windowEnd }
            .toList()
    }

    // Indicator position as a 0..1 fraction of the width. Tracks "now" when idle; follows the
    // finger while scrubbing; animates back to "now" on release.
    val scope = rememberCoroutineScope()
    val indicator = remember { Animatable(nowFraction) }
    var scrubbing by remember { mutableStateOf(false) }

    // Entrance: the curve draws itself in left-to-right once.
    val drawProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drawProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    val prefs = LocalTidePrefs.current
    val context = LocalContext.current
    val spaceGroteskTypeface = remember { ResourcesCompat.getFont(context, R.font.space_grotesk) }
    val lineColor = accent
    val fillTop = lineColor.copy(alpha = 0.32f)
    val fillBottom = lineColor.copy(alpha = 0.03f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipText = MaterialTheme.colorScheme.inverseOnSurface

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubbing = true
                        scope.launch { indicator.snapTo((offset.x / size.width).coerceIn(0f, 1f)) }
                    },
                    onDragEnd = {
                        scrubbing = false
                        scope.launch { indicator.animateTo(nowFraction, tween(450)) }
                    },
                    onDragCancel = {
                        scrubbing = false
                        scope.launch { indicator.animateTo(nowFraction, tween(450)) }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scope.launch { indicator.snapTo((change.position.x / size.width).coerceIn(0f, 1f)) }
                    },
                )
            },
    ) {
        if (samples.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val padTop = 26.dp.toPx()
        val padBottom = 26.dp.toPx()
        val baseline = h - padBottom

        val minH = samples.minOf { it.heightMeters }
        val maxH = samples.maxOf { it.heightMeters }
        val range = (maxH - minH).takeIf { it > 1e-6 } ?: 1.0

        fun xOf(t: Long): Float =
            ((t - windowStart).toDouble() / windowSpan * w).toFloat()

        fun yOf(height: Double): Float {
            val norm = (height - minH) / range // 0 at lowest, 1 at highest
            return (padTop + (1 - norm) * (baseline - padTop)).toFloat()
        }

        val revealX = (drawProgress.value * w).coerceIn(0.001f, w)
        clipRect(right = revealX) {
            // Baseline.
            drawLine(gridColor, Offset(0f, baseline), Offset(w, baseline), strokeWidth = 1f)

            // Curve path.
            val curve = Path().apply {
                samples.forEachIndexed { i, p ->
                    val x = xOf(p.timeMillis)
                    val y = yOf(p.heightMeters)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            // Gradient fill under the curve.
            val fill = Path().apply {
                addPath(curve)
                lineTo(xOf(samples.last().timeMillis), baseline)
                lineTo(xOf(samples.first().timeMillis), baseline)
                close()
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(listOf(fillTop, fillBottom), startY = padTop, endY = baseline),
            )
            drawPath(
                path = curve,
                color = lineColor,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Midnight gridlines: thin grey dashes marking the date change, no label. Drawn on top
            // of the fill/curve (not behind, like the baseline) so they stay visible regardless of
            // the fill's opacity, and with a dedicated grey (not a theme token) so contrast holds
            // up against every palette.
            midnights.forEach { midnightMillis ->
                val x = xOf(midnightMillis).coerceIn(0f, w)
                drawLine(
                    color = Color.Gray.copy(alpha = 0.55f),
                    start = Offset(x, padTop * 0.4f),
                    end = Offset(x, baseline),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            // High/low dots with time labels.
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 11.sp.toPx()
                color = labelColor.toArgb()
                typeface = spaceGroteskTypeface
            }
            windowExtremes.forEach { ext ->
                val x = xOf(ext.timeMillis).coerceIn(0f, w)
                val y = yOf(ext.heightMeters)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x, y))
                val labelY = if (ext.type == TideType.HIGH) y - 9.dp.toPx() else y + 16.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(
                    TideFormat.time(ext.timeMillis, use24h = prefs.use24h, zone = zone),
                    x.coerceIn(16.dp.toPx(), w - 16.dp.toPx()),
                    labelY,
                    textPaint,
                )
            }

            // Indicator: dashed vertical line + dot at the (interpolated) height under it. When idle
            // this is the "now" marker; while scrubbing it follows the finger and gains a tooltip.
            val frac = indicator.value.coerceIn(0f, 1f)
            val indicatorX = frac * w
            val indicatorTime = windowStart + (frac.toDouble() * windowSpan).toLong()
            val indicatorHeight = TideMath.heightAt(extremes, indicatorTime) ?: samples.first().heightMeters
            drawLine(
                color = nowColor,
                start = Offset(indicatorX, padTop * 0.4f),
                end = Offset(indicatorX, baseline),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            drawCircle(nowColor, radius = 5.dp.toPx(), center = Offset(indicatorX, yOf(indicatorHeight)))

            if (scrubbing) {
                val timeStr = TideFormat.time(indicatorTime, use24h = prefs.use24h, zone = zone)
                val heightStr = TideFormat.height(indicatorHeight, useMetric = prefs.useMetric)
                val tipPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.LEFT
                    textSize = 11.sp.toPx()
                    color = tooltipText.toArgb()
                    typeface = spaceGroteskTypeface
                }
                val padX = 8.dp.toPx()
                val padY = 6.dp.toPx()
                val gap = 2.dp.toPx()
                val fm = tipPaint.fontMetrics
                val lineH = fm.descent - fm.ascent
                val textW = max(tipPaint.measureText(timeStr), tipPaint.measureText(heightStr))
                val boxW = textW + padX * 2
                val boxH = lineH * 2 + gap + padY * 2
                val left = (indicatorX - boxW / 2).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
                drawRoundRect(
                    color = tooltipBg,
                    topLeft = Offset(left, 0f),
                    size = Size(boxW, boxH),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                val textX = left + padX
                val firstBaseline = padY - fm.ascent
                drawContext.canvas.nativeCanvas.drawText(timeStr, textX, firstBaseline, tipPaint)
                drawContext.canvas.nativeCanvas.drawText(heightStr, textX, firstBaseline + lineH + gap, tipPaint)
            }
        }
    }
}
