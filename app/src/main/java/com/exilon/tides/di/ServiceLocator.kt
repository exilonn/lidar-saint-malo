package com.exilon.tides.di

import android.content.Context
import androidx.room.Room
import com.exilon.tides.BuildConfig
import com.exilon.tides.data.EstranRepository
import com.exilon.tides.data.LidarRepository
import com.exilon.tides.data.TideRepository
import com.exilon.tides.data.local.TideDao
import com.exilon.tides.data.local.TideDatabase
import com.exilon.tides.data.location.LocationProvider
import com.exilon.tides.data.remote.ApiKeyInterceptor
import com.exilon.tides.data.remote.NominatimApi
import com.exilon.tides.data.remote.OverpassApi
import com.exilon.tides.data.remote.TideApi
import com.exilon.tides.data.remote.WeatherApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.maplibre.android.MapLibre
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (no Hilt). Held by [com.exilon.tides.TideApp]; everything is
 * lazy and process-wide. The widget and WorkManager reach the same singletons through it, so
 * Room is genuinely shared.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    init {
        // One-time MapLibre init for the interactive tide map. Lightweight (stores context/config;
        // no GL until a MapView is created), so it's safe at process start even though the map is
        // app-only — the widget/worker never create a MapView. Tile-provider keys live in the
        // style URL (BuildConfig.MAPTILER_KEY), not here.
        MapLibre.getInstance(appContext)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor(BuildConfig.TIDECHECK_API_KEY))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    private val api: TideApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.TIDECHECK_BASE_URL)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TideApi::class.java)
    }

    // Open-Meteo: its own client WITHOUT the TideCheck API-key interceptor (no key to a 3rd party).
    private val weatherOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    private val weatherApi: WeatherApi by lazy {
        // Calls use absolute URLs, so this base URL is only a required placeholder.
        Retrofit.Builder()
            .baseUrl("https://api.open-meteo.com/")
            .client(weatherOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(WeatherApi::class.java)
    }

    // Nominatim (OSM): its own client WITHOUT the TideCheck key, but WITH an identifying User-Agent
    // — the Nominatim usage policy requires one and blocks the default OkHttp/library UA. Used only
    // to translate the admin/region name, which Android's Geocoder won't localize.
    private val nominatimOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Tides/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
                    )
                    .build()
                chain.proceed(request)
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    private val nominatimApi: NominatimApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(nominatimOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NominatimApi::class.java)
    }

    // Overpass (OSM): own client with an identifying User-Agent (no TideCheck key) and a generous
    // read timeout — Overpass can be slow. Only the tide map uses it; results are cached hard.
    private val overpassOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Tides/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
                    )
                    .build()
                chain.proceed(request)
            }
            .callTimeout(40, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    private val overpassApi: OverpassApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://overpass-api.de/api/")
            .client(overpassOkHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OverpassApi::class.java)
    }

    private val database: TideDatabase by lazy {
        // The Room contents are a rebuildable cache (re-fetched on next refresh), so a schema
        // change can safely drop and recreate rather than ship a migration.
        Room.databaseBuilder(appContext, TideDatabase::class.java, "tides.db")
            .addMigrations(
                TideDatabase.MIGRATION_4_5,
                TideDatabase.MIGRATION_5_6,
                TideDatabase.MIGRATION_6_7,
                TideDatabase.MIGRATION_7_8,
                TideDatabase.MIGRATION_8_9,
                TideDatabase.MIGRATION_9_10,
                TideDatabase.MIGRATION_10_11,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    val dao: TideDao by lazy { database.tideDao() }

    private val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val repository: TideRepository by lazy { TideRepository(api, weatherApi, nominatimApi, dao, locationProvider) }

    /** Tide-map intertidal overlay (app-only; cached hard; no TideCheck quota). */
    val estranRepository: EstranRepository by lazy { EstranRepository(overpassApi, dao, appContext.filesDir) }

    // GitHub Releases: own client with no TideCheck API-key interceptor. LiDAR assets are public;
    // sending the TideCheck key to GitHub's CDN must never happen.
    private val githubOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }
            .build()
    }

    /** LiDAR zone GeoJSON cache (app-only; no TideCheck quota; version-driven invalidation). */
    val lidarRepository: LidarRepository by lazy { LidarRepository(githubOkHttp, dao, appContext.filesDir) }
}
