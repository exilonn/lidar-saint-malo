package com.exilon.tides.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo responses (https://open-meteo.com — free, no key, CC BY 4.0). Decoded with
 * `ignoreUnknownKeys = true`, so only the fields we use are declared. Daily arrays are parallel to
 * `daily.time`; sea values can be null for inland/un-gridded cells, so they're nullable.
 */

@Serializable
data class AirForecastDto(
    val timezone: String? = null,
    val current: AirCurrentDto? = null,
    val daily: AirDailyDto? = null,
    val hourly: AirHourlyDto? = null,
)

@Serializable
data class AirCurrentDto(
    @SerialName("temperature_2m") val temperature2m: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerialName("wind_direction_10m") val windDirection10m: Double? = null,
)

@Serializable
data class AirDailyDto(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeedMax: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m_dominant") val windDirectionDominant: List<Double?> = emptyList(),
)

@Serializable
data class AirHourlyDto(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature2m: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed10m: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection10m: List<Double?> = emptyList(),
)

@Serializable
data class SeaForecastDto(
    val current: SeaCurrentDto? = null,
    val daily: SeaDailyDto? = null,
    val hourly: SeaHourlyDto? = null,
)

@Serializable
data class SeaCurrentDto(
    @SerialName("sea_surface_temperature") val seaSurfaceTemperature: Double? = null,
)

@Serializable
data class SeaDailyDto(
    val time: List<String> = emptyList(),
    @SerialName("sea_surface_temperature_max") val seaSurfaceTemperatureMax: List<Double?> = emptyList(),
)

@Serializable
data class SeaHourlyDto(
    val time: List<String> = emptyList(),
    @SerialName("sea_surface_temperature") val seaSurfaceTemperature: List<Double?> = emptyList(),
)
