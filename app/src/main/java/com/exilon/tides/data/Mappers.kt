package com.exilon.tides.data

import com.exilon.tides.data.local.entity.DailyConditionEntity
import com.exilon.tides.data.local.entity.TideExtremeEntity
import com.exilon.tides.data.local.entity.WeatherDailyEntity
import com.exilon.tides.data.local.entity.WeatherHourlyEntity
import com.exilon.tides.data.model.DailyCondition
import com.exilon.tides.data.model.DayWeather
import com.exilon.tides.data.model.HourlyWeather
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideType
import com.exilon.tides.data.remote.dto.DailyConditionDto
import com.exilon.tides.data.remote.dto.ExtremeDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// --- DTO -> Entity (network boundary; ISO-8601 parsed exactly once, here) -------------------

fun ExtremeDto.toEntityOrNull(stationId: String): TideExtremeEntity? {
    val millis = parseIsoMillis(time) ?: return null
    return TideExtremeEntity(
        stationId = stationId,
        type = type.lowercase(),
        timeUtcMillis = millis,
        heightMeters = height,
        localDate = localDate,
    )
}

fun DailyConditionDto.toEntity(stationId: String) = DailyConditionEntity(
    date = date,
    stationId = stationId,
    sunrise = sunrise,
    sunset = sunset,
    moonPhase = moonPhase,
    moonIllumination = moonIllumination,
    springNeap = springNeap,
    solunarRating = solunarRating,
)

// --- Entity -> Domain -----------------------------------------------------------------------

fun TideExtremeEntity.toDomain() = TideExtreme(
    timeMillis = timeUtcMillis,
    type = if (type.equals("high", ignoreCase = true)) TideType.HIGH else TideType.LOW,
    heightMeters = heightMeters,
    localDate = localDate,
)

fun DailyConditionEntity.toDomain() = DailyCondition(
    date = date,
    sunrise = sunrise,
    sunset = sunset,
    moonPhase = moonPhase,
    moonIllumination = moonIllumination,
    springNeap = springNeap,
    solunarRating = solunarRating,
)

fun WeatherDailyEntity.toDomain() = DayWeather(
    date = date,
    airMaxC = airMaxC,
    airMinC = airMinC,
    seaC = seaC,
    windSpeedMaxKmh = windSpeedMaxKmh,
    windDirectionDominantDeg = windDirectionDominantDeg,
)

fun WeatherHourlyEntity.toDomain() = HourlyWeather(
    timeMillis = timeMillis,
    airC = airC,
    seaC = seaC,
    windSpeedKmh = windSpeedKmh,
    windDirectionDeg = windDirectionDeg,
)

// --- Helpers --------------------------------------------------------------------------------

/** Parses ISO-8601 ("...Z" or with offset) to epoch millis; null if unparseable. */
fun parseIsoMillis(iso: String): Long? = runCatching {
    Instant.parse(iso).toEpochMilli()
}.recoverCatching {
    OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}.getOrNull()

/**
 * Parses one of Open-Meteo's naive local hourly timestamps (e.g. "2024-06-14T13:00", no offset —
 * `timezone=auto` already converts the response to the station's local time) to epoch millis using
 * [zoneId] (the station's IANA timezone from the same response), falling back to the device zone.
 */
fun parseLocalDateTimeMillis(local: String, zoneId: String?): Long? = runCatching {
    val zone = zoneId?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
    LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
}.getOrNull()

/** Great-circle distance in metres (Haversine). Used to decide if the user moved enough. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
