package com.exilon.tides.ui

import android.content.Context
import com.exilon.tides.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Small presentation helpers shared by the screen, components and the widget. */
object TideFormat {

    private val timeFmtDevice: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) // respects the device 12h/24h locale
    private val timeFmt24: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val timeFmt12: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /**
     * Formats an epoch-millis to a clock string.
     * [use24h] = true forces 24h format, false forces 12h, null uses the device locale default.
     */
    fun time(millis: Long, use24h: Boolean? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = when (use24h) {
            true -> timeFmt24
            false -> timeFmt12
            null -> timeFmtDevice
        }
        return Instant.ofEpochMilli(millis).atZone(zone).format(fmt)
    }

    /**
     * Formats a sun event (sunrise/sunset) to a localized clock time. TideCheck may send a full
     * ISO-8601 instant (e.g. "2026-06-13T04:06:21.887Z"), an offset datetime, or a bare "HH:mm";
     * all three are normalized to the device locale's short time. Falls back to the raw text for
     * anything unrecognized, and returns null for null/blank input.
     */
    fun clockTime(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (value.isNullOrBlank()) return null
        runCatching { Instant.parse(value).atZone(zone) }.getOrNull()
            ?.let { return it.format(timeFmtDevice) }
        runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
            ?.let { return it.format(timeFmtDevice) }
        runCatching { LocalTime.parse(value) }.getOrNull()
            ?.let { return it.format(timeFmtDevice) }
        return value
    }

    /**
     * Resolves a sunrise/sunset value to an epoch-millis instant for the sky scene. A full ISO
     * instant/offset is used directly; a bare "HH:mm" is anchored to today in [zone]. Null if
     * unparseable.
     */
    fun sunEventMillis(value: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (value.isNullOrBlank()) return null
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?.let { return it }
        runCatching {
            LocalDate.now(zone).atTime(LocalTime.parse(value)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }

    /** Tide height formatted in metres (default) or feet, with unit suffix. */
    fun height(meters: Double, useMetric: Boolean = true): String =
        if (useMetric) String.format(Locale.getDefault(), "%.1f m", meters)
        else String.format(Locale.getDefault(), "%.1f ft", meters * 3.28084)

    /** Temperature in whole degrees; Celsius shows bare °, Fahrenheit shows °F. Null → "—". */
    fun temp(celsius: Double?, useMetric: Boolean = true): String =
        celsius?.let {
            if (useMetric) String.format(Locale.getDefault(), "%.0f°", it)
            else String.format(Locale.getDefault(), "%.0f°F", it * 9.0 / 5.0 + 32.0)
        } ?: "—"

    /** Wind speed in km/h (default) or mph, with unit suffix. Null → "—". */
    fun windSpeed(kmh: Double?, useMetric: Boolean = true): String =
        kmh?.let {
            if (useMetric) String.format(Locale.getDefault(), "%.0f km/h", it)
            else String.format(Locale.getDefault(), "%.0f mph", it * 0.621371)
        } ?: "—"

    /** 16-point compass label ("N", "NNE", "NE", ...) for a wind/bearing direction in degrees. */
    fun compassLabel(degrees: Double): String {
        val labels = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = (((degrees % 360.0) + 360.0) % 360.0 / 22.5).roundToIndex(labels.size)
        return labels[index]
    }

    private fun Double.roundToIndex(size: Int): Int =
        Math.round(this).toInt().mod(size)

    /** Local minute-of-day (0..1439) for an instant. */
    fun minutesOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        return t.hour * 60 + t.minute
    }

    /** Local minute-of-day for a sunrise/sunset value (ISO instant or bare "HH:mm"), or null. */
    fun minutesOfDay(value: String?, zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (value.isNullOrBlank()) return null
        runCatching { Instant.parse(value).atZone(zone) }.getOrNull()
            ?.let { return it.hour * 60 + it.minute }
        runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone) }.getOrNull()
            ?.let { return it.hour * 60 + it.minute }
        runCatching { LocalTime.parse(value) }.getOrNull()
            ?.let { return it.hour * 60 + it.minute }
        return null
    }

    /** Localized "2h 14m" / "14m" until [toMillis]. Clamped at zero. */
    fun countdown(context: Context, fromMillis: Long, toMillis: Long): String {
        val mins = ((toMillis - fromMillis) / 60_000).coerceAtLeast(0)
        val h = (mins / 60).toInt()
        val m = (mins % 60).toInt()
        return if (h > 0) context.getString(R.string.countdown_hm, h, m)
        else context.getString(R.string.countdown_m, m)
    }

    fun updatedAgo(context: Context, fetchedAtMillis: Long, nowMillis: Long): String {
        val mins = ((nowMillis - fetchedAtMillis) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> context.getString(R.string.just_now)
            mins < 60 -> context.getString(R.string.min_ago, mins.toInt())
            mins < 1_440 -> context.getString(R.string.hours_ago, (mins / 60).toInt())
            else -> context.getString(R.string.days_ago, (mins / 1_440).toInt())
        }
    }

    /** "Today" / "Tomorrow" / "Mon 16 Jun" from a YYYY-MM-DD string, localized. */
    fun dayLabel(context: Context, date: String, zone: ZoneId = ZoneId.systemDefault()): String =
        runCatching {
            val d = LocalDate.parse(date)
            when (d) {
                LocalDate.now(zone) -> context.getString(R.string.today)
                LocalDate.now(zone).plusDays(1) -> context.getString(R.string.tomorrow)
                else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }.getOrDefault(date)
}
