package com.exilon.tides.data.remote

import com.exilon.tides.data.remote.dto.AirForecastDto
import com.exilon.tides.data.remote.dto.SeaForecastDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open-Meteo weather (air + sea-surface temperature). Two different hosts, so each call uses an
 * absolute URL (overriding the Retrofit base URL). Runs on its own Retrofit/OkHttp **without** the
 * TideCheck API-key interceptor — we don't send that key to a third party. `timezone=auto` makes
 * the daily dates station-local so they line up with the tide days.
 */
interface WeatherApi {

    @GET("https://api.open-meteo.com/v1/forecast")
    suspend fun airForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lng: Double,
        @Query("daily") daily: String =
            "temperature_2m_max,temperature_2m_min,wind_speed_10m_max,wind_direction_10m_dominant",
        @Query("current") current: String = "temperature_2m,wind_speed_10m,wind_direction_10m",
        @Query("hourly") hourly: String = "temperature_2m,wind_speed_10m,wind_direction_10m",
        @Query("timezone") timezone: String = "auto",
    ): AirForecastDto

    @GET("https://marine-api.open-meteo.com/v1/marine")
    suspend fun seaForecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lng: Double,
        @Query("daily") daily: String = "sea_surface_temperature_max",
        @Query("current") current: String = "sea_surface_temperature",
        @Query("hourly") hourly: String = "sea_surface_temperature",
        @Query("timezone") timezone: String = "auto",
    ): SeaForecastDto
}
