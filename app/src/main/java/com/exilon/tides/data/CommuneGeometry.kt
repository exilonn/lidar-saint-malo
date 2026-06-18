package com.exilon.tides.data

import com.exilon.tides.data.remote.dto.OverpassElement
import com.exilon.tides.data.remote.dto.OverpassPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure Kotlin commune-boundary geometry helpers. No Android deps, no external geometry lib.
 *
 * Extracts outer-role member ways from an OSM admin_level=8 boundary relation and runs
 * ray-casting PIP directly over all edges from all ways — no ring-stitching required.
 *
 * Why no stitching: greedy stitching breaks on the first topology gap (floating-point junctions,
 * Overpass returning ways in arbitrary order), yielding a partial ring and wrong PIP results.
 * The crossing count over ALL edges from ALL outer ways is mathematically equivalent to testing
 * against a perfectly assembled ring: a valid closed boundary has odd parity for interior points
 * regardless of the order in which edges are visited.
 */
object CommuneGeometry {

    /**
     * Extracts all outer-role member way geometries from an Overpass boundary-relation response.
     * Returns null if no outer members were found (offshore / no admin_level=8 boundary found).
     */
    fun outerWays(elements: List<OverpassElement>): List<List<OverpassPoint>>? {
        val ways = elements
            .flatMap { it.members.orEmpty() }
            .filter { it.role == "outer" && !it.geometry.isNullOrEmpty() }
            .map { it.geometry!! }
        return ways.takeIf { it.isNotEmpty() }
    }

    /**
     * Ray-casting PIP over a commune boundary expressed as a list of outer ways. Counts edge
     * crossings across ALL edges from ALL ways — no assembly step required. An interior point
     * produces an odd crossing count for any topologically valid closed boundary.
     */
    fun pointInPolygon(lat: Double, lng: Double, ways: List<List<OverpassPoint>>): Boolean {
        var crossings = 0
        for (way in ways) {
            for (i in 0 until way.size - 1) {
                val xi = way[i].lon;     val yi = way[i].lat
                val xj = way[i + 1].lon; val yj = way[i + 1].lat
                if ((yi > lat) != (yj > lat) &&
                    lng < (xj - xi) * (lat - yi) / (yj - yi) + xi
                ) {
                    crossings++
                }
            }
        }
        return crossings % 2 == 1
    }

    /**
     * Compact JSON `[[[lat,lon],…],…]` — outer array is ways, each inner array is the ordered
     * points of one way. Written once per estran refresh cycle, read back on cache hit.
     */
    fun serialize(ways: List<List<OverpassPoint>>): String =
        buildJsonArray {
            for (way in ways) {
                addJsonArray {
                    for (p in way) addJsonArray { add(p.lat); add(p.lon) }
                }
            }
        }.toString()

    fun deserialize(json: String): List<List<OverpassPoint>>? = runCatching {
        (Json.parseToJsonElement(json) as JsonArray).map { wayEl ->
            (wayEl as JsonArray).map { pointEl ->
                val arr = pointEl.jsonArray
                OverpassPoint(lat = arr[0].jsonPrimitive.double, lon = arr[1].jsonPrimitive.double)
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
