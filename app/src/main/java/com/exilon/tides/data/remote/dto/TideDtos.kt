package com.exilon.tides.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the TideCheck API (https://tidecheck.com/api).
 *
 * The JSON is decoded with `ignoreUnknownKeys = true`, so we only declare the fields we use.
 * Optional fields are nullable with defaults so a partial/changed response never crashes
 * parsing — if the live contract differs, adjust here at the boundary only.
 */

/**
 * One station from the lookup endpoint. The station-lookup response is a JSON **array** of
 * these (`[{...}, {...}]`), not a single object — the repository picks the nearest one.
 */
@Serializable
data class StationDto(
    // Search results can omit the id (those have no tide station and 404 on /tides) — the
    // repository filters them out. Nearest always supplies a real id. `slug` is the search slug.
    val id: String = "",
    val slug: String? = null,
    val name: String? = null,
    val region: String? = null,
    val country: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    @SerialName("distanceKm") val distanceKm: Double? = null,
)

/** Response of `GET /station/{id}/tides?days=7`. */
@Serializable
data class TidesResponseDto(
    val station: StationRefDto? = null,
    val extremes: List<ExtremeDto> = emptyList(),
    @SerialName("dailyConditions") val dailyConditions: List<DailyConditionDto> = emptyList(),
)

@Serializable
data class StationRefDto(
    val id: String? = null,
    val name: String? = null,
)

/** A single high/low tide event. */
@Serializable
data class ExtremeDto(
    val type: String,                               // "high" | "low"
    val time: String,                               // ISO-8601 UTC, e.g. "2026-06-14T12:34:00Z"
    @SerialName("localDate") val localDate: String? = null, // YYYY-MM-DD (station local)
    val height: Double,                             // metres
)

/** Per-day sun/moon/solunar conditions. */
@Serializable
data class DailyConditionDto(
    val date: String,                               // YYYY-MM-DD
    val sunrise: String? = null,                    // "HH:MM"
    val sunset: String? = null,                     // "HH:MM"
    @SerialName("moonPhase") val moonPhase: String? = null,
    @SerialName("moonIllumination") val moonIllumination: Int? = null, // 0..100
    @SerialName("springNeap") val springNeap: String? = null,          // "spring" | "neap"
    @SerialName("solunarRating") val solunarRating: Int? = null,       // 1..5
)
