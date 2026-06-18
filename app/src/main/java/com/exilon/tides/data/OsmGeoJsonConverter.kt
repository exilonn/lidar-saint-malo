package com.exilon.tides.data

import com.exilon.tides.data.remote.dto.OverpassElement
import com.exilon.tides.data.remote.dto.OverpassPoint
import com.exilon.tides.data.remote.dto.OverpassResponse
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.cos
import kotlin.math.sqrt

/** The intertidal substrate classes the tide map recolours, derived from OSM tags. */
enum class Substrate { SAND, GRAVEL, ROCK, MUD }

/**
 * Converts an Overpass `out geom` response into a GeoJSON `FeatureCollection` **string** (kept as a
 * plain string so the data layer stays free of MapLibre types and the result caches straight to a
 * file). Only intertidal substrate features are emitted; each gets a `substrate` property the map
 * styles by. Multipolygon relations are flattened to one Polygon per `outer` ring — inner holes are
 * dropped (rare for tidal areas; a filled hole is a negligible v1 inaccuracy).
 */
object OsmGeoJsonConverter {

    data class Result(val geoJson: String, val featureCount: Int)

    /** OSM tags → [Substrate], or null for anything that isn't an intertidal substrate we render.
     *  Features tagged natural=water (without an intertidal qualifier) are excluded: they are
     *  permanent water in OSM and the crossfade model cannot simulate drainage without bathymetry.
     *  Accurate port/harbour drying requires Level 3 DEM data. */
    fun classify(tags: Map<String, String>): Substrate? {
        val natural = tags["natural"]
        val surface = tags["surface"]
        val wetland = tags["wetland"]
        return when {
            // Permanent water (harbour, dock, sea, lake…) — no intertidal tag means it doesn't
            // drain with the tide in OSM's model. Exclude entirely rather than wrongly crossfade.
            natural == "water" && wetland == null -> null
            natural == "beach" && surface in GRAVEL_SURFACES -> Substrate.GRAVEL
            natural == "beach" -> Substrate.SAND // defaults to sand when surface is untagged
            natural == "shingle" -> Substrate.GRAVEL
            natural == "bare_rock" || natural == "rock" || natural == "reef" -> Substrate.ROCK
            natural == "mud" -> Substrate.MUD
            wetland == "tidal_flat" -> Substrate.MUD
            else -> null
        }
    }

    /**
     * Convert and spatially filter the Overpass response.
     *
     * [communeFiltered] = true: Overpass already filtered features to the station's commune via
     * `(area.commune)` — no client-side spatial check is applied.
     *
     * [communeFiltered] = false (fallback bbox path): keep features whose centroid is within
     * [FALLBACK_DIST_M] metres of the station.
     */
    fun toGeoJson(
        response: OverpassResponse,
        stationLat: Double,
        stationLng: Double,
        communeFiltered: Boolean = false,
    ): Result {
        var count = 0
        val features = buildJsonArray {
            for (element in response.elements) {
                val substrate = classify(element.tags) ?: continue
                for (ring in ringsOf(element)) {
                    if (!communeFiltered && !inScope(ring, stationLat, stationLng)) continue
                    val feature = polygonFeature(ring, substrate) ?: continue
                    add(feature)
                    count++
                }
            }
        }
        val fc = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", features)
        }
        return Result(fc.toString(), count)
    }

    /** All outer rings of an element: a way is one ring; a relation contributes each outer member. */
    private fun ringsOf(element: OverpassElement): List<List<OverpassPoint>> = when (element.type) {
        "way" -> element.geometry?.let { listOf(it) } ?: emptyList()
        "relation" -> element.members
            ?.filter { it.role == "outer" && it.geometry != null }
            ?.map { it.geometry!! }
            ?: emptyList()
        else -> emptyList()
    }

    /** True iff the polygon centroid is within [FALLBACK_DIST_M] metres of the station. */
    private fun inScope(
        points: List<OverpassPoint>,
        stationLat: Double,
        stationLng: Double,
    ): Boolean {
        if (points.isEmpty()) return false
        val cLat = points.sumOf { it.lat } / points.size
        val cLng = points.sumOf { it.lon } / points.size
        return distM(cLat, cLng, stationLat, stationLng) <= FALLBACK_DIST_M
    }

    /** Fast equirectangular distance in metres (accurate enough for the sub-5 km scale we use). */
    private fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dLng = (lng2 - lng1) * METERS_PER_DEG_LAT * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    private fun polygonFeature(points: List<OverpassPoint>, substrate: Substrate) =
        if (points.size < 3) {
            null
        } else {
            buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") { put("substrate", substrate.name) }
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") {
                        addJsonArray {
                            for (p in points) addJsonArray { add(p.lon); add(p.lat) }
                            // GeoJSON rings must be closed: repeat the first vertex if needed.
                            val first = points.first()
                            val last = points.last()
                            if (first.lat != last.lat || first.lon != last.lon) {
                                addJsonArray { add(first.lon); add(first.lat) }
                            }
                        }
                    }
                }
            }
        }

    private val GRAVEL_SURFACES = setOf("gravel", "fine_gravel", "shingle", "pebbles", "pebblestone")
    // Fallback distance when commune boundary is unavailable (offshore / no admin_level=8 coverage).
    private const val FALLBACK_DIST_M = 2_000.0
    private const val METERS_PER_DEG_LAT = 111_320.0
}
