package com.exilon.tides.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A station the user can view. There may be several: at most one auto "current location" station
 * ([isFavourite] = false, resolved from the device location) plus any [isFavourite] stations the
 * user saved from search. `lat`/`lng` are the *station's* coordinates. Times are epoch millis;
 * nothing in Room is timezone-bound. Each station owns its own cached extremes/conditions, so
 * switching between them is instant and offline.
 */
@Entity(tableName = "saved_station")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val placeName: String,
    val lat: Double,
    val lng: Double,
    val datum: String,
    val isFavourite: Boolean,
    val sortOrder: Int,
    val fetchedAtMillis: Long, // 0 = never fetched yet
    val region: String? = null,       // e.g. admin area/state, raw from the source — localized at render time
    val country: String? = null,      // raw English country name from the source — localized at render time
    val currentAirC: Double? = null,  // Open-Meteo current air temperature (°C)
    val currentSeaC: Double? = null,  // Open-Meteo current sea-surface temperature (°C)
    val currentWindSpeedKmh: Double? = null,      // Open-Meteo current wind speed (km/h)
    val currentWindDirectionDeg: Double? = null,  // Open-Meteo current wind direction, degrees, the
                                                   // direction the wind blows FROM (meteorological convention)
    val timezone: String? = null,             // IANA timezone from Open-Meteo (e.g. "America/Los_Angeles")
    val weatherFetchedAtMillis: Long = 0,     // separate staleness clock for weather-only refreshes
    val isHidden: Boolean = false,            // soft-deleted: hidden from the list/picker, cache kept
)

// Per-day air/sea temperatures + wind from Open-Meteo, keyed (stationId, date) like daily_condition.
// Sea is nullable: inland/un-gridded cells return no SST.
@Entity(tableName = "weather_daily", primaryKeys = ["stationId", "date"])
data class WeatherDailyEntity(
    val stationId: String,
    val date: String,          // YYYY-MM-DD (station-local)
    val airMaxC: Double?,
    val airMinC: Double?,
    val seaC: Double?,
    val windSpeedMaxKmh: Double? = null,
    val windDirectionDominantDeg: Double? = null,
)

/**
 * Metadata for a station's cached OSM intertidal/estran overlay. The geometry itself is a GeoJSON
 * file on disk (filesDir/estran/<stationId>.geojson) — too big for Room — this row only tracks
 * staleness. [schemaVersion] lets a query/classification change invalidate old caches.
 */
@Entity(tableName = "estran_cache")
data class EstranCacheEntity(
    @PrimaryKey val stationId: String,
    val fetchedAtMillis: Long,
    val hasIntertidal: Boolean,
    val schemaVersion: Int,
)

// Hourly air/sea temperature + wind from Open-Meteo, keyed (stationId, timeMillis). Only covers the
// same ~7-day window as the daily/current weather call (Open-Meteo's default forecast length) — it
// backs the per-day "Details" view, not the full 30-day tide window.
@Entity(tableName = "weather_hourly", primaryKeys = ["stationId", "timeMillis"])
data class WeatherHourlyEntity(
    val stationId: String,
    val timeMillis: Long,
    val airC: Double?,
    val seaC: Double?,
    val windSpeedKmh: Double?,
    val windDirectionDeg: Double?,
)

/**
 * Single-row app state (id is always [SINGLETON_ID]): which station is selected, plus the last
 * device location used to resolve the "current location" station (so we only re-resolve the
 * nearest station after the user has moved appreciably — keeping us well under the API quota).
 */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val selectedStationId: String?,
    val lastDeviceLat: Double?,
    val lastDeviceLng: Double?,
    val themeId: String = "ocean",        // selected palette; drives app + widget
    val languageTag: String? = null,      // manual locale override; null = follow system
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * Tracks a downloaded LiDAR zone asset. Keyed by zone slug (not stationId) because one zone
 * covers multiple stations. The GeoJSON geometry lives at [filePath]; this row only tracks the
 * version so we know when to re-download. No TTL — invalidation is version-driven only.
 */
@Entity(tableName = "lidar_zone")
data class LidarZoneEntity(
    @PrimaryKey val zoneId: String,
    val assetVersion: Int,
    val fetchedAtMillis: Long,
    val filePath: String,
)

/** A single high/low tide event. The continuous curve is derived from these, never stored. */
@Entity(tableName = "tide_extreme")
data class TideExtremeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String,
    val type: String,            // "high" | "low"
    val timeUtcMillis: Long,
    val heightMeters: Double,
    val localDate: String?,      // YYYY-MM-DD station-local, used for day grouping
)

// Keyed by (stationId, date): each station has its own per-day conditions, so the same calendar
// date can exist for several stations without overwriting one another.
@Entity(tableName = "daily_condition", primaryKeys = ["stationId", "date"])
data class DailyConditionEntity(
    val date: String,
    val stationId: String,
    val sunrise: String?,
    val sunset: String?,
    val moonPhase: String?,
    val moonIllumination: Int?,
    val springNeap: String?,
    val solunarRating: Int?,
)
