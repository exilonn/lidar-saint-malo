package com.exilon.tides.data

import android.util.Log
import com.exilon.tides.data.local.TideDao
import com.exilon.tides.data.local.entity.EstranCacheEntity
import com.exilon.tides.data.remote.OverpassApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.cos

/** Outcome of resolving a station's intertidal overlay. */
sealed interface EstranResult {
    /** GeoJSON ready to hand to the map. [hasIntertidal] = false means OSM has no estran here. */
    data class Data(val geoJson: String, val hasIntertidal: Boolean) : EstranResult

    /** Nothing cached and the fetch failed (offline / Overpass down / backing off). */
    data object Unavailable : EstranResult
}

/**
 * Fetches and caches the intertidal overlay for the tide map.
 *
 * **Primary path** — `buildCommuneQuery`: a single Overpass request that (1) tests five points
 * around the station with `is_in` to locate the admin_level=8 commune (testing five points handles
 * stations whose coordinates land in the harbor/sea where a single `is_in` returns nothing), then
 * (2) returns only features within the commune via `(area.commune)`. Spatial filtering is done
 * entirely server-side — no client-side PIP.
 *
 * **Fallback** — `buildBboxQuery`: if the primary returns 0 elements (no admin_level=8 found, or
 * offshore station with no municipality), a bbox query runs and features are filtered by centroid
 * distance (≤ [FALLBACK_DIST_M] m from the station).
 *
 * Results are cached as a GeoJSON file in `filesDir/estran/` with a 60-day TTL tracked in Room.
 * On any Overpass failure the last-cached GeoJSON is served and a 5-min back-off prevents retries.
 */
class EstranRepository(
    private val overpassApi: OverpassApi,
    private val dao: TideDao,
    private val filesDir: File,
) {
    @Volatile private var cooldownUntilMillis: Long = 0L

    suspend fun estran(stationId: String, lat: Double, lng: Double): EstranResult =
        withContext(Dispatchers.IO) {
            val cache = dao.estranCache(stationId)
            val estranFile = estranFile(stationId)

            val fresh = cache != null &&
                cache.schemaVersion == SCHEMA_VERSION &&
                System.currentTimeMillis() - cache.fetchedAtMillis < TTL_MS &&
                estranFile.exists()
            if (fresh) {
                return@withContext EstranResult.Data(estranFile.readText(), cache!!.hasIntertidal)
            }

            if (System.currentTimeMillis() < cooldownUntilMillis) {
                return@withContext staleOrUnavailable(estranFile, cache)
            }

            try {
                // Primary: commune-filtered fetch (server-side spatial filter via area.commune).
                var response = overpassApi.query(buildCommuneQuery(lat, lng))
                var communeFiltered = response.elements.isNotEmpty()

                if (communeFiltered) {
                    Log.d("MapDebug", "Commune query: ${response.elements.size} elements")
                } else {
                    // Commune not found (offshore / no admin_level=8) — fall back to bbox.
                    Log.d("MapDebug", "No commune found — falling back to bbox query")
                    response = overpassApi.query(buildBboxQuery(lat, lng))
                }

                val converted = OsmGeoJsonConverter.toGeoJson(
                    response, lat, lng, communeFiltered = communeFiltered,
                )

                estranFile.parentFile?.mkdirs()
                estranFile.writeText(converted.geoJson)

                dao.upsertEstranCache(
                    EstranCacheEntity(
                        stationId = stationId,
                        fetchedAtMillis = System.currentTimeMillis(),
                        hasIntertidal = converted.featureCount > 0,
                        schemaVersion = SCHEMA_VERSION,
                    ),
                )
                EstranResult.Data(converted.geoJson, converted.featureCount > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cooldownUntilMillis = System.currentTimeMillis() + COOLDOWN_MS
                Log.w("MapDebug", "Estran fetch failed: ${e.message}")
                staleOrUnavailable(estranFile, cache)
            }
        }

    private fun staleOrUnavailable(file: File, cache: EstranCacheEntity?): EstranResult =
        if (file.exists()) EstranResult.Data(file.readText(), cache?.hasIntertidal ?: false)
        else EstranResult.Unavailable

    /**
     * Single combined Overpass query: five `is_in` probes around the station (to handle stations
     * whose coordinate lands in harbor/sea where a single `is_in` may return nothing), unioned into
     * one admin_level=8 commune area set, then all intertidal features within that area. Server-side
     * `(area.commune)` filtering means no client-side PIP is needed.
     *
     * Returns 0 elements if no admin_level=8 boundary contains any of the five probe points
     * (offshore station or countries without that admin level).
     */
    private fun buildCommuneQuery(lat: Double, lng: Double): String {
        val d = 0.002          // ~220 m — large enough to clear a harbor basin, small enough to
        val dL = d / cos(Math.toRadians(lat)).coerceAtLeast(1e-6)  // stay well within the commune
        val p = fun(la: Double, lo: Double) =
            "is_in(${f(la)},${f(lo)})->.tmp;(.prev;.tmp;)->.prev;"
        return """
            [out:json][timeout:40];
            is_in(${f(lat)},${f(lng)})->.prev;
            ${p(lat + d, lng)}
            ${p(lat - d, lng)}
            ${p(lat, lng + dL)}
            ${p(lat, lng - dL)}
            area.prev[boundary=administrative][admin_level=8]->.commune;
            (
              way["natural"="beach"](area.commune);
              way["natural"="shingle"](area.commune);
              way["natural"="mud"](area.commune);
              way["natural"="bare_rock"](area.commune);
              way["natural"="reef"](area.commune);
              way["natural"="wetland"]["wetland"="tidal_flat"](area.commune);
              relation["natural"="wetland"]["wetland"="tidal_flat"](area.commune);
              relation["natural"="mud"](area.commune);
            );
            out geom;
        """.trimIndent()
    }

    /** Fallback bbox query when no commune is found. Client applies distance filter. */
    private fun buildBboxQuery(lat: Double, lng: Double): String {
        val dLat = HALF_SPAN_M / METERS_PER_DEG_LAT
        val dLng = HALF_SPAN_M / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        val bbox = String.format(
            Locale.US, "%.6f,%.6f,%.6f,%.6f",
            lat - dLat, lng - dLng, lat + dLat, lng + dLng,
        )
        return """
            [out:json][timeout:30];
            (
              way["natural"="beach"]($bbox);
              way["natural"="shingle"]($bbox);
              way["natural"="mud"]($bbox);
              way["natural"="bare_rock"]($bbox);
              way["natural"="reef"]($bbox);
              way["natural"="wetland"]["wetland"="tidal_flat"]($bbox);
              relation["natural"="wetland"]["wetland"="tidal_flat"]($bbox);
              relation["natural"="mud"]($bbox);
            );
            out geom;
        """.trimIndent()
    }

    private fun estranFile(stationId: String): File {
        val safeId = stationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(File(filesDir, "estran"), "$safeId.geojson")
    }

    private fun f(v: Double) = String.format(Locale.US, "%.6f", v)

    private companion object {
        /** Bump whenever query, classification, or filtering logic changes to invalidate caches. */
        const val SCHEMA_VERSION = 5  // combined commune query; server-side area.commune filter
        const val TTL_MS = 60L * 24 * 60 * 60 * 1000
        const val COOLDOWN_MS = 5L * 60 * 1000
        const val HALF_SPAN_M = 3_500.0  // bbox fallback only; commune path covers the whole area
        const val METERS_PER_DEG_LAT = 111_320.0
    }
}
