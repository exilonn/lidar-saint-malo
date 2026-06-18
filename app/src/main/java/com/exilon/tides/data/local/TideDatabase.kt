package com.exilon.tides.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.exilon.tides.data.local.entity.AppStateEntity
import com.exilon.tides.data.local.entity.DailyConditionEntity
import com.exilon.tides.data.local.entity.EstranCacheEntity
import com.exilon.tides.data.local.entity.LidarZoneEntity
import com.exilon.tides.data.local.entity.StationEntity
import com.exilon.tides.data.local.entity.TideExtremeEntity
import com.exilon.tides.data.local.entity.WeatherDailyEntity
import com.exilon.tides.data.local.entity.WeatherHourlyEntity

@Database(
    entities = [
        StationEntity::class,
        TideExtremeEntity::class,
        DailyConditionEntity::class,
        AppStateEntity::class,
        WeatherDailyEntity::class,
        WeatherHourlyEntity::class,
        EstranCacheEntity::class,
        LidarZoneEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class TideDatabase : RoomDatabase() {
    abstract fun tideDao(): TideDao

    companion object {
        /** Adds theme/language settings to app_state without wiping the user's saved stations. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_state ADD COLUMN themeId TEXT NOT NULL DEFAULT 'ocean'")
                db.execSQL("ALTER TABLE app_state ADD COLUMN languageTag TEXT")
            }
        }

        /** Adds station IANA timezone and a separate weather staleness timestamp. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_station ADD COLUMN timezone TEXT")
                db.execSQL("ALTER TABLE saved_station ADD COLUMN weatherFetchedAtMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Splits the old combined "region, country" label into separate columns so the country
         * part can be localized live at render time instead of stored pre-formatted in English. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_station ADD COLUMN country TEXT")
            }
        }

        /** Adds wind (current + daily + hourly) and the soft-delete flag for removed locations. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_station ADD COLUMN currentWindSpeedKmh REAL")
                db.execSQL("ALTER TABLE saved_station ADD COLUMN currentWindDirectionDeg REAL")
                db.execSQL("ALTER TABLE saved_station ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE weather_daily ADD COLUMN windSpeedMaxKmh REAL")
                db.execSQL("ALTER TABLE weather_daily ADD COLUMN windDirectionDominantDeg REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS weather_hourly (" +
                        "stationId TEXT NOT NULL, timeMillis INTEGER NOT NULL, " +
                        "airC REAL, seaC REAL, windSpeedKmh REAL, windDirectionDeg REAL, " +
                        "PRIMARY KEY(stationId, timeMillis))",
                )
            }
        }

        /** Adds the tide-map estran overlay cache metadata (geometry lives in a file). */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS estran_cache (" +
                        "stationId TEXT NOT NULL, fetchedAtMillis INTEGER NOT NULL, " +
                        "hasIntertidal INTEGER NOT NULL, schemaVersion INTEGER NOT NULL, " +
                        "PRIMARY KEY(stationId))",
                )
            }
        }

        /** Adds the LiDAR zone asset cache table (GeoJSON file path + version, keyed by zone slug). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS lidar_zone (" +
                        "zoneId TEXT NOT NULL, assetVersion INTEGER NOT NULL, " +
                        "fetchedAtMillis INTEGER NOT NULL, filePath TEXT NOT NULL, " +
                        "PRIMARY KEY(zoneId))",
                )
            }
        }

        /**
         * Purges MLLW-contaminated tide extremes. The API was called with datum=MLLW from the
         * beginning; all stored heightMeters values are MLLW-referenced instead of LAT-referenced,
         * causing the IGN69 waterline to be placed 2.892 m below correct. Clearing the extremes
         * and resetting fetchedAtMillis forces a clean LAT re-fetch on next open.
         * daily_condition, weather, and saved station metadata are datum-independent and kept.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM tide_extreme")
                db.execSQL("UPDATE saved_station SET fetchedAtMillis = 0")
            }
        }
    }
}
