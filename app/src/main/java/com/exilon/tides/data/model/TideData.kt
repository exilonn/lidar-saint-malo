package com.exilon.tides.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-day sun/moon/solunar conditions, keyed by station-local date (YYYY-MM-DD). */
data class DailyCondition(
    val date: String,
    val sunrise: String?,
    val sunset: String?,
    val moonPhase: String?,
    val moonIllumination: Int?,
    val springNeap: String?,
    val solunarRating: Int?,
)

/** Per-day air/sea temperatures (°C) + wind from Open-Meteo. Any field may be null (e.g. sea inland). */
data class DayWeather(
    val date: String,
    val airMaxC: Double?,
    val airMinC: Double?,
    val seaC: Double?,
    val windSpeedMaxKmh: Double? = null,
    val windDirectionDominantDeg: Double? = null,
)

/** One hourly sample backing the per-day "Details" view. [windDirectionDeg] is the direction the
 *  wind blows FROM (meteorological convention), degrees clockwise from geographic north. */
data class HourlyWeather(
    val timeMillis: Long,
    val airC: Double?,
    val seaC: Double?,
    val windSpeedKmh: Double?,
    val windDirectionDeg: Double?,
)

/** The whole cached forecast assembled from Room. The single object the UI/widget render from. */
data class TideData(
    val stationId: String,
    val stationName: String,
    val placeName: String,
    val region: String? = null,
    val country: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val datum: String,
    val isFavourite: Boolean,
    val fetchedAtMillis: Long,
    val extremes: List<TideExtreme>,
    val conditions: List<DailyCondition>,
    val weather: List<DayWeather> = emptyList(),
    val hourly: List<HourlyWeather> = emptyList(),
    val currentAirC: Double? = null,
    val currentSeaC: Double? = null,
    val currentWindSpeedKmh: Double? = null,
    val currentWindDirectionDeg: Double? = null,
    val timezone: String? = null,
) {
    /** True once a forecast has actually been cached for this station. */
    val hasForecast: Boolean get() = extremes.isNotEmpty()

    /** The station's IANA timezone, falling back to the device default for local stations. */
    val stationZone: ZoneId
        get() = timezone?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
            ?: ZoneId.systemDefault()

    /** Everything the "current state" header needs, computed for a given instant. */
    fun snapshotAt(nowMillis: Long): TideSnapshot? {
        val height = TideMath.heightAt(extremes, nowMillis) ?: return null
        return TideSnapshot(
            currentHeightMeters = height,
            isRising = TideMath.isRising(extremes, nowMillis) ?: false,
            previous = TideMath.previousExtreme(extremes, nowMillis),
            nextHigh = TideMath.nextExtreme(extremes, nowMillis, TideType.HIGH),
            nextLow = TideMath.nextExtreme(extremes, nowMillis, TideType.LOW),
        )
    }

    /**
     * Groups extremes into days (station-local date when known, else device zone), ordered
     * chronologically and starting at [from] (today by default) so the "Coming days" list never
     * shows past days above today.
     */
    fun byDay(
        zone: ZoneId = ZoneId.systemDefault(),
        from: LocalDate = LocalDate.now(zone),
        maxDays: Int = 7,
    ): List<DayTides> {
        val conditionByDate = conditions.associateBy { it.date }
        val weatherByDate = weather.associateBy { it.date }
        val hourlyByDate = hourly.groupBy { it.timeMillis.toLocalDate(zone) }
        return extremes
            .groupBy { it.localDate ?: it.timeMillis.toLocalDate(zone) }
            .toSortedMap()
            .filterKeys { date -> runCatching { LocalDate.parse(date) >= from }.getOrDefault(true) }
            .map { (date, dayExtremes) ->
                DayTides(
                    date = date,
                    condition = conditionByDate[date],
                    weather = weatherByDate[date],
                    extremes = dayExtremes.sortedBy { it.timeMillis },
                    hourly = (hourlyByDate[date] ?: emptyList()).sortedBy { it.timeMillis },
                )
            }
            .take(maxDays)
    }
}

data class TideSnapshot(
    val currentHeightMeters: Double,
    val isRising: Boolean,
    val previous: TideExtreme?,
    val nextHigh: TideExtreme?,
    val nextLow: TideExtreme?,
) {
    /** The very next turning point, regardless of type. */
    val nextExtreme: TideExtreme?
        get() = listOfNotNull(nextHigh, nextLow).minByOrNull { it.timeMillis }
}

data class DayTides(
    val date: String,
    val condition: DailyCondition?,
    val weather: DayWeather?,
    val extremes: List<TideExtreme>,
    val hourly: List<HourlyWeather> = emptyList(),
)

private fun Long.toLocalDate(zone: ZoneId): String =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toString()
