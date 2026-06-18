package com.exilon.tides.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.exilon.tides.data.local.entity.AppStateEntity
import com.exilon.tides.data.local.entity.DailyConditionEntity
import com.exilon.tides.data.local.entity.EstranCacheEntity
import com.exilon.tides.data.local.entity.LidarZoneEntity
import com.exilon.tides.data.local.entity.StationEntity
import com.exilon.tides.data.local.entity.TideExtremeEntity
import com.exilon.tides.data.local.entity.WeatherDailyEntity
import com.exilon.tides.data.local.entity.WeatherHourlyEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TideDao {

    // --- Reactive reads (Compose UI) ------------------------------------------------------

    /** Every *visible* saved station (soft-deleted ones are hidden), "current location" first. */
    @Query("SELECT * FROM saved_station WHERE isHidden = 0 ORDER BY sortOrder ASC, placeName ASC")
    abstract fun observeStations(): Flow<List<StationEntity>>

    /** All cached extremes across every station; the repository groups them by station id. */
    @Query("SELECT * FROM tide_extreme ORDER BY timeUtcMillis ASC")
    abstract fun observeAllExtremes(): Flow<List<TideExtremeEntity>>

    @Query("SELECT * FROM daily_condition ORDER BY date ASC")
    abstract fun observeAllDaily(): Flow<List<DailyConditionEntity>>

    @Query("SELECT * FROM weather_daily ORDER BY date ASC")
    abstract fun observeAllWeather(): Flow<List<WeatherDailyEntity>>

    @Query("SELECT * FROM weather_hourly ORDER BY timeMillis ASC")
    abstract fun observeAllHourly(): Flow<List<WeatherHourlyEntity>>

    @Query("SELECT * FROM app_state WHERE id = 0 LIMIT 1")
    abstract fun observeAppState(): Flow<AppStateEntity?>

    // --- One-shot snapshots (Glance widget + repository; never touch the network) ----------

    @Query("SELECT * FROM saved_station WHERE isHidden = 0 ORDER BY sortOrder ASC, placeName ASC")
    abstract suspend fun stationsSnapshot(): List<StationEntity>

    @Query("SELECT * FROM saved_station WHERE id = :id LIMIT 1")
    abstract suspend fun stationById(id: String): StationEntity?

    /** The auto "current location" station (the single non-favourite row), if any. */
    @Query("SELECT * FROM saved_station WHERE isFavourite = 0 LIMIT 1")
    abstract suspend fun currentLocationStation(): StationEntity?

    /** Soft-deleted stations (hidden from the list/picker but still cached) — eviction candidates. */
    @Query("SELECT * FROM saved_station WHERE isHidden = 1")
    abstract suspend fun hiddenStationsSnapshot(): List<StationEntity>

    @Query("SELECT * FROM app_state WHERE id = 0 LIMIT 1")
    abstract suspend fun appStateSnapshot(): AppStateEntity?

    @Query("SELECT MAX(sortOrder) FROM saved_station")
    abstract suspend fun maxSortOrder(): Int?

    @Query("SELECT MAX(timeUtcMillis) FROM tide_extreme WHERE stationId = :id")
    abstract suspend fun lastExtremeMillisForStation(id: String): Long?

    @Query("UPDATE saved_station SET weatherFetchedAtMillis = :millis WHERE id = :id")
    abstract suspend fun setWeatherFetchedAt(id: String, millis: Long)

    // The widget renders whichever station is currently selected — still purely from cache.
    @Query(
        "SELECT * FROM saved_station " +
            "WHERE id = (SELECT selectedStationId FROM app_state WHERE id = 0) LIMIT 1",
    )
    abstract suspend fun selectedStationSnapshot(): StationEntity?

    @Query(
        "SELECT * FROM tide_extreme " +
            "WHERE stationId = (SELECT selectedStationId FROM app_state WHERE id = 0) " +
            "ORDER BY timeUtcMillis ASC",
    )
    abstract suspend fun selectedExtremesSnapshot(): List<TideExtremeEntity>

    // --- Writes ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStation(station: StationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExtremes(extremes: List<TideExtremeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDaily(daily: List<DailyConditionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertWeather(weather: List<WeatherDailyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHourly(hourly: List<WeatherHourlyEntity>)

    @Query("DELETE FROM saved_station WHERE id = :id")
    abstract suspend fun deleteStation(id: String)

    @Query("DELETE FROM tide_extreme WHERE stationId = :stationId")
    abstract suspend fun deleteExtremesForStation(stationId: String)

    @Query("DELETE FROM daily_condition WHERE stationId = :stationId")
    abstract suspend fun deleteDailyForStation(stationId: String)

    @Query("DELETE FROM weather_daily WHERE stationId = :stationId")
    abstract suspend fun deleteWeatherForStation(stationId: String)

    @Query("DELETE FROM weather_hourly WHERE stationId = :stationId")
    abstract suspend fun deleteHourlyForStation(stationId: String)

    @Query("UPDATE saved_station SET isHidden = 1 WHERE id = :id")
    abstract suspend fun hideStation(id: String)

    @Query("DELETE FROM tide_extreme")
    abstract suspend fun deleteAllExtremes()

    @Query("DELETE FROM daily_condition")
    abstract suspend fun deleteAllDaily()

    @Query("DELETE FROM weather_daily")
    abstract suspend fun deleteAllWeather()

    @Query("DELETE FROM weather_hourly")
    abstract suspend fun deleteAllHourly()

    @Query("DELETE FROM estran_cache")
    abstract suspend fun deleteAllEstran()

    @Query("UPDATE saved_station SET fetchedAtMillis = 0, weatherFetchedAtMillis = 0")
    abstract suspend fun resetAllFetchTimestamps()

    // --- Estran (tide map) overlay cache metadata ------------------------------------------

    @Query("SELECT * FROM estran_cache WHERE stationId = :stationId LIMIT 1")
    abstract suspend fun estranCache(stationId: String): EstranCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertEstranCache(entity: EstranCacheEntity)

    // --- LiDAR zone asset cache ---------------------------------------------------------------

    @Query("SELECT * FROM lidar_zone WHERE zoneId = :zoneId LIMIT 1")
    abstract suspend fun lidarZone(zoneId: String): LidarZoneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLidarZone(entity: LidarZoneEntity)

    @Query("SELECT filePath FROM lidar_zone")
    abstract suspend fun allLidarFilePaths(): List<String>

    @Query("DELETE FROM lidar_zone")
    abstract suspend fun deleteAllLidarZones()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAppStateIfAbsent(state: AppStateEntity)

    @Query("UPDATE app_state SET selectedStationId = :id WHERE id = 0")
    abstract suspend fun setSelectedStation(id: String?)

    @Query("UPDATE app_state SET lastDeviceLat = :lat, lastDeviceLng = :lng WHERE id = 0")
    abstract suspend fun updateDeviceLocation(lat: Double, lng: Double)

    @Query("UPDATE app_state SET themeId = :id WHERE id = 0")
    abstract suspend fun setThemeId(id: String)

    @Query("UPDATE app_state SET languageTag = :tag WHERE id = 0")
    abstract suspend fun setLanguageTag(tag: String?)

    /** Make sure the single app-state row exists before any UPDATE touches it. */
    suspend fun ensureAppState() = insertAppStateIfAbsent(
        AppStateEntity(selectedStationId = null, lastDeviceLat = null, lastDeviceLng = null),
    )

    /**
     * Atomically swap *one station's* cached forecast. Other stations' caches are untouched, so
     * each saved place keeps its own offline data. Readers never observe a half-written forecast.
     */
    @Transaction
    open suspend fun replaceTidesForStation(
        stationId: String,
        extremes: List<TideExtremeEntity>,
        daily: List<DailyConditionEntity>,
    ) {
        deleteExtremesForStation(stationId)
        deleteDailyForStation(stationId)
        insertExtremes(extremes)
        insertDaily(daily)
    }

    /** Replace just one station's cached daily + hourly weather (atomic), other stations untouched. */
    @Transaction
    open suspend fun replaceWeatherForStation(
        stationId: String,
        weather: List<WeatherDailyEntity>,
        hourly: List<WeatherHourlyEntity> = emptyList(),
    ) {
        deleteWeatherForStation(stationId)
        deleteHourlyForStation(stationId)
        insertWeather(weather)
        insertHourly(hourly)
    }

    /** Remove a station and everything cached for it. */
    @Transaction
    open suspend fun deleteStationAndTides(id: String) {
        deleteExtremesForStation(id)
        deleteDailyForStation(id)
        deleteWeatherForStation(id)
        deleteHourlyForStation(id)
        deleteStation(id)
    }

    /** Wipes every station's cached tide + weather data (Settings > Clear cache); stations and
     *  their settings are kept, but every fetch timestamp resets so the next use re-fetches fresh. */
    @Transaction
    open suspend fun clearAllCache() {
        deleteAllExtremes()
        deleteAllDaily()
        deleteAllWeather()
        deleteAllHourly()
        deleteAllEstran()
        deleteAllLidarZones()
        resetAllFetchTimestamps()
    }
}
