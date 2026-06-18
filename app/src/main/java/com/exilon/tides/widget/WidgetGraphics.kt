package com.exilon.tides.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.exilon.tides.R
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideMath

/**
 * Glance compiles to RemoteViews and can't draw Canvas, so the background, the mini curve and the
 * height/arrow are rendered to Bitmaps here from the Room-cached extremes + the active palette
 * colours, then shown via Glance `Image`. This is how the widget matches the app's theme.
 */
object WidgetGraphics {

    private fun spaceGrotesk(context: Context): Typeface {
        val base = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 600, false)
        else Typeface.create(base, Typeface.BOLD)
    }

    /** Themed gradient surface for the widget background. */
    fun bgBitmap(top: Int, bottom: Int): Bitmap {
        val w = 24
        val h = 48
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            },
        )
        return bmp
    }

    /** The rising/falling arrow + big height number in Space Grotesk; width sized to the text. */
    fun headerBitmap(context: Context, heightText: String, isRising: Boolean, accent: Int, text: Int): Bitmap {
        val h = 132
        val arrow = 96f
        val gap = 18f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = spaceGrotesk(context)
            textSize = 96f
            color = text
        }
        val textW = paint.measureText(heightText)
        val w = (arrow + gap + textW + 8f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawArrow(c, 6f, (h - arrow) / 2f, arrow - 12f, isRising, accent)
        val fm = paint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(heightText, arrow + gap, baseline, paint)
        return bmp
    }

    private fun drawArrow(c: Canvas, left: Float, top: Float, s: Float, up: Boolean, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.13f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val cx = left + s / 2f
        val path = Path()
        if (up) {
            path.moveTo(cx, top + s * 0.84f)
            path.lineTo(cx, top + s * 0.18f)
            path.moveTo(left + s * 0.22f, top + s * 0.44f)
            path.lineTo(cx, top + s * 0.18f)
            path.lineTo(left + s * 0.78f, top + s * 0.44f)
        } else {
            path.moveTo(cx, top + s * 0.16f)
            path.lineTo(cx, top + s * 0.82f)
            path.moveTo(left + s * 0.22f, top + s * 0.56f)
            path.lineTo(cx, top + s * 0.82f)
            path.lineTo(left + s * 0.78f, top + s * 0.56f)
        }
        c.drawPath(path, p)
    }

    /**
     * A rolling tide curve windowed around "now", which sits at a **fixed** [NOW_FRACTION] of the
     * width — the curve slides underneath it as time passes rather than a marker sliding across a
     * static day. Mirrors the in-app [com.exilon.tides.ui.screen.components.TideCurveCanvas] window.
     */
    fun curveBitmap(extremes: List<TideExtreme>, nowMillis: Long, accent: Int, now: Int): Bitmap {
        val w = 900
        val h = 260
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val windowStart = nowMillis - WINDOW_BEFORE_MS
        val windowEnd = nowMillis + WINDOW_AFTER_MS
        val samples = TideMath.curveSamples(extremes, windowStart, windowEnd, 120)
        if (samples.size < 2) return bmp

        val padTop = 22f
        val padBottom = 16f
        val minH = samples.minOf { it.heightMeters }
        val maxH = samples.maxOf { it.heightMeters }
        val range = (maxH - minH).takeIf { it > 1e-6 } ?: 1.0
        fun x(t: Long): Float = ((t - windowStart).toDouble() / (windowEnd - windowStart) * w).toFloat()
        fun y(hgt: Double): Float =
            (padTop + (1 - (hgt - minH) / range) * (h - padTop - padBottom)).toFloat()

        val line = Path()
        samples.forEachIndexed { i, s ->
            val px = x(s.timeMillis)
            val py = y(s.heightMeters)
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        val fill = Path(line).apply {
            lineTo(x(samples.last().timeMillis), h.toFloat())
            lineTo(x(samples.first().timeMillis), h.toFloat())
            close()
        }
        c.drawPath(
            fill,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, padTop, 0f, h.toFloat(),
                    withAlpha(accent, 0.45f), withAlpha(accent, 0.04f), Shader.TileMode.CLAMP,
                )
            },
        )
        c.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = accent
            },
        )

        // "Now" is fixed at NOW_FRACTION of the width; the curve (not the marker) moves over time.
        val nowX = (NOW_FRACTION * w)
        c.drawLine(
            nowX, padTop * 0.4f, nowX, h - padBottom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(now, 0.5f); strokeWidth = 3f },
        )
        val nowY = y(TideMath.heightAt(extremes, nowMillis) ?: samples.first().heightMeters)
        drawBuoy(c, nowX, nowY)
        return bmp
    }

    /**
     * A buoy marker (float + waterline band + mast) at the "now" position, sized to read clearly
     * at widget scale and drawn in a dedicated red so it stands out against the curve/fill colours
     * regardless of the active theme's accent.
     */
    private fun drawBuoy(c: Canvas, cx: Float, cy: Float) {
        val r = 16f
        // A thin white halo first so the red buoy stays legible over a red-ish curve too.
        c.drawCircle(cx, cy, r + 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(android.graphics.Color.WHITE, 0.9f)
        })
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BUOY_RED })
        c.drawLine(
            cx - r, cy, cx + r, cy,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                color = withAlpha(android.graphics.Color.WHITE, 0.85f)
            },
        )
        val mastTop = cy - r - 16f
        c.drawLine(
            cx, cy - r * 0.4f, cx, mastTop,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                color = BUOY_RED
            },
        )
        c.drawCircle(cx, mastTop, 4.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = BUOY_RED })
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** "Now" sits a third of the way into the window (matches the in-app curve's ~1/8–1/3 feel at widget size). */
    private const val NOW_FRACTION = 1f / 3f
    private const val WINDOW_BEFORE_MS = 8L * 3_600_000L
    private const val WINDOW_AFTER_MS = 16L * 3_600_000L

    /** Dedicated, theme-independent red for the "now" buoy so it always stands out from the curve. */
    private const val BUOY_RED = 0xFFE53935.toInt()
}
