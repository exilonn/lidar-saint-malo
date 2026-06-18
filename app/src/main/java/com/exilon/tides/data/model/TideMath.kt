package com.exilon.tides.data.model

import kotlin.math.PI
import kotlin.math.cos

enum class TideType { HIGH, LOW }

/** A predicted high/low event. Android-free so it can be shared by UI, widget and tests. */
data class TideExtreme(
    val timeMillis: Long,
    val type: TideType,
    val heightMeters: Double,
    val localDate: String? = null,
)

/** A sampled point on the reconstructed continuous tide curve. */
data class TidePoint(val timeMillis: Long, val heightMeters: Double)

/**
 * Reconstructs the continuous tide curve from discrete high/low extremes.
 *
 * Why interpolate instead of fetching a time series: TideCheck returns only the extremes, and
 * doing this on-device keeps Room tiny and lets the widget + offline mode work with zero
 * network. Between two consecutive extremes the tide follows (very nearly) a half-cosine: the
 * curve is flat at each turning point and steepest at mid-tide. That is exactly
 *
 *     h(t) = h0 + (h1 - h0) * (1 - cos(pi * f)) / 2,   f = (t - t0) / (t1 - t0)
 *
 * which national hydrographic offices use as the standard "rule of twelfths" smooth form.
 */
object TideMath {

    /** Interpolated height (metres) at [timeMillis], or null if there are no extremes. */
    fun heightAt(extremes: List<TideExtreme>, timeMillis: Long): Double? {
        if (extremes.isEmpty()) return null
        if (timeMillis <= extremes.first().timeMillis) return extremes.first().heightMeters
        if (timeMillis >= extremes.last().timeMillis) return extremes.last().heightMeters

        val i = extremes.indexOfLast { it.timeMillis <= timeMillis }
        val a = extremes[i]
        val b = extremes[i + 1]
        val span = (b.timeMillis - a.timeMillis).toDouble()
        if (span <= 0.0) return a.heightMeters

        val f = (timeMillis - a.timeMillis) / span
        val smooth = (1.0 - cos(PI * f)) / 2.0
        return a.heightMeters + (b.heightMeters - a.heightMeters) * smooth
    }

    /** True if the tide is rising at [timeMillis]. Null if it can't be determined. */
    fun isRising(extremes: List<TideExtreme>, timeMillis: Long): Boolean? {
        if (extremes.isEmpty()) return null
        // Before the series: rising iff we are heading into a high. After: rising iff we just
        // left a low. (With 7 days of data the edge cases are only a safety net.)
        if (timeMillis <= extremes.first().timeMillis) return extremes.first().type == TideType.HIGH
        if (timeMillis >= extremes.last().timeMillis) return extremes.last().type == TideType.LOW

        val i = extremes.indexOfLast { it.timeMillis <= timeMillis }
        return extremes[i + 1].heightMeters > extremes[i].heightMeters
    }

    /** First extreme strictly after [afterMillis], optionally constrained to a [type]. */
    fun nextExtreme(
        extremes: List<TideExtreme>,
        afterMillis: Long,
        type: TideType? = null,
    ): TideExtreme? = extremes.firstOrNull {
        it.timeMillis > afterMillis && (type == null || it.type == type)
    }

    /** Most recent extreme at or before [atMillis]. */
    fun previousExtreme(extremes: List<TideExtreme>, atMillis: Long): TideExtreme? =
        extremes.lastOrNull { it.timeMillis <= atMillis }

    /**
     * Tide level at [timeMillis] as a 0..1 fraction of the level range visible across
     * [windowStartMillis, windowEndMillis]: 0 = the window's lowest water, 1 = its highest. Used by
     * the tide map to drive inundation. Normalizing against the *window* (not an absolute datum)
     * keeps the visual swing consistent regardless of the station's datum/range, and is fully
     * local/deterministic — no network. Null if there are no extremes or the window is empty.
     */
    fun fractionAt(
        extremes: List<TideExtreme>,
        timeMillis: Long,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): Float? {
        val h = heightAt(extremes, timeMillis) ?: return null
        val samples = curveSamples(extremes, windowStartMillis, windowEndMillis, samples = 96)
        if (samples.isEmpty()) return null
        var lo = samples.first().heightMeters
        var hi = lo
        for (p in samples) {
            if (p.heightMeters < lo) lo = p.heightMeters
            if (p.heightMeters > hi) hi = p.heightMeters
        }
        val range = hi - lo
        if (range <= 1e-6) return 0.5f
        return ((h - lo) / range).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Samples the reconstructed curve over [startMillis, endMillis] for the Canvas. Points are
     * spaced evenly **in time** — so the caller maps each [TidePoint.timeMillis] straight onto a
     * time-proportional x-axis — and every extreme inside the window is pinned as an exact sample
     * so each high/low lands precisely on the curve instead of being clipped between two samples.
     * Heights come from [heightAt], i.e. a half-cosine scaled to each interval's real duration.
     */
    fun curveSamples(
        extremes: List<TideExtreme>,
        startMillis: Long,
        endMillis: Long,
        samples: Int = 120,
    ): List<TidePoint> {
        if (extremes.isEmpty() || endMillis <= startMillis || samples <= 0) return emptyList()
        val span = (endMillis - startMillis).toDouble()
        val times = sortedSetOf<Long>()
        for (k in 0..samples) times += startMillis + (span * k / samples).toLong()
        for (e in extremes) if (e.timeMillis in startMillis..endMillis) times += e.timeMillis
        return times.map { t -> TidePoint(t, heightAt(extremes, t) ?: 0.0) }
    }
}
