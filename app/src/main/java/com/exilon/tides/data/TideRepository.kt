package com.exilon.tides.data

import com.exilon.tides.data.local.TideDao
import com.exilon.tides.data.local.entity.StationEntity
import com.exilon.tides.data.local.entity.WeatherDailyEntity
import com.exilon.tides.data.local.entity.WeatherHourlyEntity
import com.exilon.tides.data.location.LocationProvider
import com.exilon.tides.data.model.TideData
import com.exilon.tides.data.remote.NominatimApi
import com.exilon.tides.data.remote.TideApi
import com.exilon.tides.data.remote.WeatherApi
import com.exilon.tides.data.remote.dto.StationDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.util.Locale

/**
 * The single network touchpoint. Orchestrates location -> nearest station -> 30-day forecast
 * -> Room, manages the user's saved stations, and exposes the cache as [Flow]s (Room is the
 * single source of truth — UI and widget both read from here, never from the API directly).
 *
 * Network is used only by this class, and only on demand: a fetch when a station's cached window
 * is genuinely stale, selected for the first time, or newly added. Plain switching/reopening the
 * app never force-refreshes — see [refresh] and [selectStation]. A short in-memory backoff also
 * stops repeated calls right after a 429 from TideCheck's free tier (50 req/day).
 */
class TideRepository(
    private val api: TideApi,
    private val weatherApi: WeatherApi,
    private val nominatimApi: NominatimApi,
    private val dao: TideDao,
    private val location: LocationProvider,
) {

    /** Sits in memory only (not persisted) — a process-lifetime cooldown after a 429. */
    @Volatile private var rateLimitedUntilMillis: Long = 0L

    /** Serializes Nominatim calls (its policy caps us at ~1 req/sec) and guards [regionCache]. */
    private val nominatimMutex = Mutex()

    /** In-memory localized-region cache, keyed by rounded coords + language. Guarded by
     *  [nominatimMutex] (all access happens inside its lock), so a plain map is safe here. */
    private val regionCache = HashMap<String, String?>()

    /** Wall-clock of the last Nominatim request, to space calls per the 1 req/sec usage policy. */
    @Volatile private var lastNominatimCallMillis: Long = 0L

    /** Every saved station as a domain forecast (extremes/conditions may be empty until fetched). */
    val allTideData: Flow<List<TideData>> = combine(
        dao.observeStations(),
        dao.observeAllExtremes(),
        dao.observeAllDaily(),
        dao.observeAllWeather(),
        dao.observeAllHourly(),
    ) { stations, extremes, daily, weather, hourly ->
        val extremesByStation = extremes.groupBy { it.stationId }
        val dailyByStation = daily.groupBy { it.stationId }
        val weatherByStation = weather.groupBy { it.stationId }
        val hourlyByStation = hourly.groupBy { it.stationId }
        stations.map { s ->
            TideData(
                stationId = s.id,
                stationName = s.name,
                placeName = s.placeName,
                region = s.region,
                country = s.country,
                lat = s.lat,
                lng = s.lng,
                datum = s.datum,
                isFavourite = s.isFavourite,
                fetchedAtMillis = s.fetchedAtMillis,
                extremes = (extremesByStation[s.id] ?: emptyList()).map { it.toDomain() },
                conditions = (dailyByStation[s.id] ?: emptyList()).map { it.toDomain() },
                weather = (weatherByStation[s.id] ?: emptyList()).map { it.toDomain() },
                hourly = (hourlyByStation[s.id] ?: emptyList()).map { it.toDomain() },
                currentAirC = s.currentAirC,
                currentSeaC = s.currentSeaC,
                currentWindSpeedKmh = s.currentWindSpeedKmh,
                currentWindDirectionDeg = s.currentWindDirectionDeg,
                timezone = s.timezone,
            )
        }
    }

    /** Id of the currently selected station (the one shown by the app and the widget). */
    val selectedStationId: Flow<String?> =
        dao.observeAppState().map { it?.selectedStationId }

    /** Selected theme palette id (drives app + widget). Defaults to "ocean". */
    val themeId: Flow<String> = dao.observeAppState().map { it?.themeId ?: "ocean" }

    /** Manual language override (BCP-47 tag), or null to follow the system locale. */
    val languageTag: Flow<String?> = dao.observeAppState().map { it?.languageTag }

    suspend fun setTheme(id: String) {
        dao.ensureAppState()
        dao.setThemeId(id)
    }

    suspend fun setLanguage(tag: String?) {
        dao.ensureAppState()
        dao.setLanguageTag(tag)
    }

    /**
     * The admin/region name (e.g. "Bretagne" for "fr", "Brittany" for "en") for [lat]/[lng] in the
     * language given by [languageTag].
     *
     * Resolved via **Nominatim** (`accept-language`), NOT the Android [android.location.Geocoder]:
     * `Geocoder.getAdminArea()` returns a single canonical form (usually English) regardless of the
     * Locale passed to it — the reason earlier locale-threading fixes never localized the region,
     * even though they localized the country (country has a pure-Java path via
     * [com.exilon.tides.ui.PlaceLocalization]). On a Nominatim failure (offline/blocked) we degrade
     * to the Geocoder so something shows, and don't cache that so a later call retries the
     * localizing source. Results are cached per (coords, language) and Nominatim calls are
     * serialized + spaced to respect its ~1 req/sec usage policy — and it costs no TideCheck quota.
     *
     * [languageTag] is taken explicitly (Room's stored value) rather than [Locale.getDefault],
     * which only updates on the next Activity recreation after a language switch.
     */
    suspend fun localizedRegion(lat: Double, lng: Double, languageTag: String?): String? {
        val lang = languageTag?.takeIf { it.isNotBlank() }?.substringBefore('-')
            ?: Locale.getDefault().language.ifBlank { "en" }
        val key = "%.3f,%.3f,%s".format(Locale.US, lat, lng, lang)
        return nominatimMutex.withLock {
            if (regionCache.containsKey(key)) return@withLock regionCache[key]
            // Space requests to honour Nominatim's max ~1/sec.
            val sinceLast = System.currentTimeMillis() - lastNominatimCallMillis
            if (sinceLast in 0 until NOMINATIM_MIN_INTERVAL_MS) {
                delay(NOMINATIM_MIN_INTERVAL_MS - sinceLast)
            }
            val result = runCatching {
                lastNominatimCallMillis = System.currentTimeMillis()
                nominatimApi.reverse(lat = lat, lng = lng, language = lang)
                    .address?.regionName()?.takeIf { it.isNotBlank() }
            }
            result.fold(
                onSuccess = { region ->
                    regionCache[key] = region // cache successes (incl. a legit "no region" null)
                    region
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    // Network failure: degrade to the Geocoder (may not localize) and don't cache,
                    // so the next emission retries Nominatim once connectivity returns.
                    location.placeInfo(lat, lng, Locale.forLanguageTag(lang)).region
                },
            )
        }
    }

    /**
     * Refreshes the **currently selected** station. With [force] = false (app open, permission
     * regained, etc.) this is cache-only unless the cached data is genuinely stale or missing —
     * no TideCheck call happens just because the app was reopened. [force] = true is reserved for
     * an explicit user pull-to-refresh. The "current location" station still re-resolves the
     * nearest station when the device has moved past [STATION_REUSE_RADIUS_M], and that resolved
     * station is always fetched once (it's new, so it has nothing cached yet).
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = guarded {
        dao.ensureAppState()
        evictExpiredHiddenStations()
        val state = dao.appStateSnapshot()
        val selected = state?.selectedStationId?.let { dao.stationById(it) }

        if (selected != null && selected.isFavourite) {
            return@guarded fetchAndStore(selected, force = force)
        }

        // Otherwise we own the "current location" station, which needs the device location.
        if (!location.hasPermission()) return@guarded RefreshResult.PermissionDenied
        val loc = location.currentLocation() ?: return@guarded RefreshResult.LocationUnavailable

        val current = dao.currentLocationStation()
        val lastLat = state?.lastDeviceLat
        val lastLng = state?.lastDeviceLng
        val movedFar = lastLat == null || lastLng == null ||
            distanceMeters(lastLat, lastLng, loc.latitude, loc.longitude) >= STATION_REUSE_RADIUS_M

        var isNewStation = false
        val station: StationEntity = if (current != null && !movedFar) {
            current // reuse the cached station id; no extra lookup call
        } else {
            isNewStation = true
            val nearest = api.nearestStations(loc.latitude, loc.longitude).minByOrNull { s ->
                if (s.lat != null && s.lng != null) {
                    distanceMeters(s.lat, s.lng, loc.latitude, loc.longitude)
                } else {
                    Double.MAX_VALUE
                }
            } ?: return@guarded RefreshResult.NoStationNearby(null)
            // Within range: use the nearest silently. Too far (mid-continent/ocean): surface it.
            val distM = if (nearest.lat != null && nearest.lng != null) {
                distanceMeters(nearest.lat, nearest.lng, loc.latitude, loc.longitude)
            } else {
                null
            }
            if (distM != null && distM > NO_STATION_RADIUS_M) {
                return@guarded RefreshResult.NoStationNearby(distM / 1000.0)
            }
            val place = location.placeInfo(loc.latitude, loc.longitude)
            val placeName = place.name ?: nearest.name ?: "Current location"
            // The nearest station changed: drop the previous current-location cache.
            if (current != null && current.id != nearest.id) dao.deleteStationAndTides(current.id)
            StationEntity(
                id = nearest.id,
                name = nearest.name ?: "Tide station",
                placeName = placeName,
                region = place.region ?: nearest.region?.takeIf { it.isNotBlank() },
                country = place.country ?: nearest.country?.takeIf { it.isNotBlank() },
                lat = nearest.lat ?: loc.latitude,
                lng = nearest.lng ?: loc.longitude,
                datum = DEFAULT_DATUM,
                isFavourite = false,
                sortOrder = CURRENT_LOCATION_ORDER,
                fetchedAtMillis = 0,
            )
        }

        dao.upsertStation(station)
        dao.updateDeviceLocation(loc.latitude, loc.longitude)
        if (state?.selectedStationId == null) dao.setSelectedStation(station.id)
        fetchAndStore(station, force = force || isNewStation)
    }

    /**
     * Free-text station search. The endpoint matches on the whole label (name/region/country); some
     * matches (incl. real coastal towns like Biarritz) carry no station id, so we keep anything with
     * either an id or coordinates and resolve the nearest station at add-time. Name matches are
     * surfaced above region/country-only matches.
     */
    suspend fun searchStations(query: String): Result<List<StationResult>> {
        if (System.currentTimeMillis() < rateLimitedUntilMillis) {
            return Result.failure(RateLimitExceededException())
        }
        return try {
            val q = query.trim()
            val list = api.searchStations(q)
                .filter { it.id.isNotBlank() || (it.lat != null && it.lng != null) }
                .map { it.toResult() }
                .sortedWith(
                    compareByDescending<StationResult> { it.name.contains(q, ignoreCase = true) }
                        .thenBy { it.name.lowercase() },
                )
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 429) {
                rateLimitedUntilMillis = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                Result.failure(RateLimitExceededException())
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saves [result] as a favourite (if not already saved), selects it, and fetches its forecast.
     * If it's already saved we simply select it and refresh when stale.
     */
    suspend fun addFavourite(result: StationResult): RefreshResult = guarded {
        dao.ensureAppState()
        // Geocoded results can lack a station id; resolve the nearest real station from its coords.
        val resolved = if (result.id.isNotBlank()) result else resolveNearestStation(result)
        if (resolved == null || resolved.id.isBlank()) {
            return@guarded RefreshResult.Error("No tide station near ${result.name}")
        }
        val existing = dao.stationById(resolved.id)
        if (existing != null) {
            dao.setSelectedStation(existing.id)
            // Re-adding a soft-deleted station: un-hide it. If its cached window is still within
            // range this costs no request at all (fetchAndStore only fetches when genuinely stale).
            return@guarded fetchAndStore(existing.copy(isHidden = false))
        }
        val station = StationEntity(
            id = resolved.id,
            name = result.name, // keep the searched place name
            placeName = result.placeName ?: result.name,
            region = result.region,
            country = result.country,
            lat = resolved.lat,
            lng = resolved.lng,
            datum = DEFAULT_DATUM,
            isFavourite = true,
            sortOrder = (dao.maxSortOrder() ?: CURRENT_LOCATION_ORDER) + 1,
            fetchedAtMillis = 0,
        )
        dao.upsertStation(station)
        dao.setSelectedStation(station.id)
        fetchAndStore(station)
    }

    /** Resolves a coords-only search result to the nearest real tide station. */
    private suspend fun resolveNearestStation(result: StationResult): StationResult? {
        if (result.lat == 0.0 && result.lng == 0.0) return null
        val nearest = api.nearestStations(result.lat, result.lng)
            .filter { it.id.isNotBlank() }
            .minByOrNull { s ->
                if (s.lat != null && s.lng != null) {
                    distanceMeters(s.lat, s.lng, result.lat, result.lng)
                } else {
                    Double.MAX_VALUE
                }
            } ?: return null
        return result.copy(
            id = nearest.id,
            lat = nearest.lat ?: result.lat,
            lng = nearest.lng ?: result.lng,
        )
    }

    /**
     * Switches the active station. Cache-only: only fetches if the newly-selected station's data
     * is genuinely stale or has never been fetched. Plain switching between saved stations never
     * touches the network.
     */
    suspend fun selectStation(id: String): RefreshResult = guarded {
        dao.ensureAppState()
        dao.setSelectedStation(id)
        val station = dao.stationById(id) ?: return@guarded RefreshResult.Error("Unknown station")
        if (station.isFavourite) fetchAndStore(station) else refresh()
    }

    /**
     * Soft-deletes a saved station: hides it from the list/picker but keeps its cached ~30-day
     * tide data in Room, so re-adding it within the window ([addFavourite]) costs no API call.
     * Re-selects another visible station if it was active.
     */
    suspend fun removeStation(id: String) {
        dao.ensureAppState()
        dao.hideStation(id)
        if (dao.appStateSnapshot()?.selectedStationId == id) {
            dao.setSelectedStation(dao.stationsSnapshot().firstOrNull()?.id)
        }
    }

    /**
     * Wipes all cached tide/weather/LiDAR data (Settings > Clear cache). Saved stations and their
     * settings are kept, but every fetch timestamp resets so the next use re-fetches fresh.
     * LiDAR zone files are also deleted from disk so the next open re-downloads the latest asset.
     */
    suspend fun clearCache() {
        val lidarPaths = dao.allLidarFilePaths()
        dao.clearAllCache()
        lidarPaths.forEach { runCatching { java.io.File(it).delete() } }
    }

    /**
     * Permanently deletes any soft-deleted station whose cached tide window has already expired —
     * keeps Room from growing unbounded with stations the user removed long ago. Cheap (no
     * network); safe to call on every [refresh].
     */
    private suspend fun evictExpiredHiddenStations() {
        val now = System.currentTimeMillis()
        dao.hiddenStationsSnapshot().forEach { hidden ->
            val lastExtremeMillis = dao.lastExtremeMillisForStation(hidden.id)
            if (lastExtremeMillis == null || lastExtremeMillis < now) {
                dao.deleteStationAndTides(hidden.id)
            }
        }
    }

    /**
     * Fetches tides and/or weather for [station] based on individual staleness. [force] skips both
     * checks (used by explicit user refresh or a brand-new station). Smart path: tides only when
     * < [MIN_DAYS_REMAINING] days remain in the 30-day window; weather independently every
     * [WEATHER_STALE_MS], with a short retry backoff (not the full window) if the last attempt
     * failed, so a transient/429 failure self-heals instead of being cached as permanent.
     */
    private suspend fun fetchAndStore(station: StationEntity, force: Boolean = false): RefreshResult {
        var updated = station

        if (force || station.needsTideRefresh()) {
            val response = api.tides(station.id, days = FORECAST_DAYS, datum = DEFAULT_DATUM)
            updated = updated.copy(
                name = response.station?.name ?: station.name,
                fetchedAtMillis = System.currentTimeMillis(),
            )
            dao.replaceTidesForStation(
                stationId = station.id,
                extremes = response.extremes.mapNotNull { it.toEntityOrNull(station.id) },
                daily = response.dailyConditions.map { it.toEntity(station.id) },
            )
        }

        if (force || station.isWeatherStale()) {
            val weather = fetchWeather(station.id, station.lat, station.lng)
            updated = if (weather.success) {
                dao.replaceWeatherForStation(station.id, weather.daily, weather.hourly)
                updated.copy(
                    currentAirC = weather.currentAirC,
                    currentSeaC = weather.currentSeaC,
                    currentWindSpeedKmh = weather.currentWindSpeedKmh,
                    currentWindDirectionDeg = weather.currentWindDirectionDeg,
                    timezone = weather.timezone ?: station.timezone,
                    weatherFetchedAtMillis = System.currentTimeMillis(),
                )
            } else {
                // Keep the previously cached temps and schedule a near-term retry instead of
                // either retrying instantly (tight loop) or waiting out the full stale window.
                updated.copy(
                    weatherFetchedAtMillis = System.currentTimeMillis() - WEATHER_STALE_MS + WEATHER_RETRY_BACKOFF_MS,
                )
            }
        }

        dao.upsertStation(updated)
        return RefreshResult.Success
    }

    /**
     * Open-Meteo air + sea at the station coordinates. Each call is independent; a missing marine
     * cell (null SST for an inland station) is a normal successful response, not a failure. Only an
     * actual request failure (network blip, 429, etc.) marks the result unsuccessful so the caller
     * retries soon rather than caching the failure as if it were fresh data.
     */
    private suspend fun fetchWeather(stationId: String, lat: Double, lng: Double): WeatherResult {
        var airFailed = false
        var seaFailed = false
        val air = try {
            weatherApi.airForecast(lat, lng)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            airFailed = true
            null
        }
        val sea = try {
            weatherApi.seaForecast(lat, lng)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            seaFailed = true
            null
        }

        val airDates = air?.daily?.time ?: emptyList()
        val airMax = air?.daily?.temperatureMax ?: emptyList()
        val airMin = air?.daily?.temperatureMin ?: emptyList()
        val windSpeedMax = air?.daily?.windSpeedMax ?: emptyList()
        val windDirDominant = air?.daily?.windDirectionDominant ?: emptyList()
        val seaByDate = (sea?.daily?.time ?: emptyList())
            .zip(sea?.daily?.seaSurfaceTemperatureMax ?: emptyList())
            .toMap()
        val daily = airDates.mapIndexed { i, date ->
            WeatherDailyEntity(
                stationId = stationId,
                date = date,
                airMaxC = airMax.getOrNull(i),
                airMinC = airMin.getOrNull(i),
                seaC = seaByDate[date],
                windSpeedMaxKmh = windSpeedMax.getOrNull(i),
                windDirectionDominantDeg = windDirDominant.getOrNull(i),
            )
        }

        val zone = air?.timezone
        val hourlyTimes = air?.hourly?.time ?: emptyList()
        val hourlyAir = air?.hourly?.temperature2m ?: emptyList()
        val hourlyWindSpeed = air?.hourly?.windSpeed10m ?: emptyList()
        val hourlyWindDir = air?.hourly?.windDirection10m ?: emptyList()
        val hourlySeaByTime = (sea?.hourly?.time ?: emptyList())
            .zip(sea?.hourly?.seaSurfaceTemperature ?: emptyList())
            .toMap()
        val hourly = hourlyTimes.mapIndexedNotNull { i, time ->
            val millis = parseLocalDateTimeMillis(time, zone) ?: return@mapIndexedNotNull null
            WeatherHourlyEntity(
                stationId = stationId,
                timeMillis = millis,
                airC = hourlyAir.getOrNull(i),
                seaC = hourlySeaByTime[time],
                windSpeedKmh = hourlyWindSpeed.getOrNull(i),
                windDirectionDeg = hourlyWindDir.getOrNull(i),
            )
        }

        return WeatherResult(
            currentAirC = air?.current?.temperature2m,
            currentSeaC = sea?.current?.seaSurfaceTemperature,
            currentWindSpeedKmh = air?.current?.windSpeed10m,
            currentWindDirectionDeg = air?.current?.windDirection10m,
            daily = daily,
            hourly = hourly,
            timezone = zone,
            success = !airFailed && !seaFailed,
        )
    }

    private data class WeatherResult(
        val currentAirC: Double?,
        val currentSeaC: Double?,
        val currentWindSpeedKmh: Double?,
        val currentWindDirectionDeg: Double?,
        val daily: List<WeatherDailyEntity>,
        val hourly: List<WeatherHourlyEntity> = emptyList(),
        val timezone: String? = null,
        val success: Boolean,
    )

    /**
     * True when the station has never been fetched, or one of the next [MIN_DAYS_REMAINING] (7)
     * days is missing from the cache — i.e. exactly the "Coming days" window the UI shows. We
     * still fetch a full [FORECAST_DAYS]-day window each time so the cache stays ahead, but the
     * trigger itself is tied to the visible week, not an artificial prefetch buffer.
     */
    private suspend fun StationEntity.needsTideRefresh(): Boolean {
        if (fetchedAtMillis == 0L) return true
        val lastMillis = dao.lastExtremeMillisForStation(id) ?: return true
        val daysRemaining = (lastMillis - System.currentTimeMillis()) / MILLIS_PER_DAY
        return daysRemaining < MIN_DAYS_REMAINING
    }

    private fun StationEntity.isWeatherStale(): Boolean =
        weatherFetchedAtMillis == 0L ||
            System.currentTimeMillis() - weatherFetchedAtMillis > WEATHER_STALE_MS

    /**
     * Wraps every network-touching entry point: short-circuits to [RefreshResult.RateLimited]
     * while a recent 429 backoff is in effect (no tight retry loop), and converts a fresh 429 into
     * the same friendly result instead of a raw "HTTP 429" message.
     */
    private suspend fun guarded(block: suspend () -> RefreshResult): RefreshResult {
        if (System.currentTimeMillis() < rateLimitedUntilMillis) {
            return RefreshResult.RateLimited
        }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 429) {
                rateLimitedUntilMillis = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                RefreshResult.RateLimited
            } else {
                RefreshResult.Error(e.message ?: "Couldn't refresh tides")
            }
        } catch (e: Exception) {
            RefreshResult.Error(e.message ?: "Couldn't refresh tides")
        }
    }

    private fun StationDto.toResult() = StationResult(
        id = id,
        name = name ?: slug ?: id,
        placeName = name,
        region = region?.takeIf { it.isNotBlank() },
        country = country?.takeIf { it.isNotBlank() },
        lat = lat ?: 0.0,
        lng = lng ?: 0.0,
    )

    companion object {
        const val FORECAST_DAYS = 30
        const val DEFAULT_DATUM = "LAT"

        /** Re-resolve the nearest station only after the device moves this far (metres). */
        const val STATION_REUSE_RADIUS_M = 10_000.0

        /** Beyond this, treat the user as having no coastal station near them. */
        const val NO_STATION_RADIUS_M = 40_000.0

        /** Refresh weather independently every 12h (tides are deterministic, weather isn't). */
        const val WEATHER_STALE_MS = 12 * 60 * 60 * 1000L

        /** On a failed weather fetch, retry this soon instead of waiting the full stale window. */
        const val WEATHER_RETRY_BACKOFF_MS = 15 * 60 * 1000L

        /** Minimum spacing between Nominatim requests (its policy allows ~1/sec). */
        const val NOMINATIM_MIN_INTERVAL_MS = 1_100L

        /** Re-fetch once fewer than this many days remain cached — matches the visible "Coming
         *  days" window, so a fetch happens only when that window would otherwise have a gap. */
        const val MIN_DAYS_REMAINING = 7L

        /** Cooldown after a TideCheck 429 before any further request is even attempted. */
        const val RATE_LIMIT_BACKOFF_MS = 5 * 60 * 1000L

        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        /** Sort key that pins the "current location" station ahead of saved favourites. */
        private const val CURRENT_LOCATION_ORDER = 0
    }
}

/** A station returned by search and offered to the user to save. */
data class StationResult(
    val id: String,
    val name: String,
    val placeName: String?,
    val region: String?,
    val country: String? = null,
    val lat: Double,
    val lng: Double,
)

/** Thrown by [TideRepository.searchStations] on a 429; carries no raw HTTP text to the UI. */
class RateLimitExceededException : Exception("Rate limit reached")

sealed interface RefreshResult {
    data object Success : RefreshResult
    data object PermissionDenied : RefreshResult
    data object LocationUnavailable : RefreshResult
    data object RateLimited : RefreshResult
    data class NoStationNearby(val distanceKm: Double?) : RefreshResult
    data class Error(val message: String) : RefreshResult
}
