package com.exilon.tides.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Overpass API response for an `out geom;` query — each element carries its geometry inline, so no
 * second resolve pass is needed. Decoded with `ignoreUnknownKeys = true`; only the fields the
 * estran overlay needs are declared. Data © OpenStreetMap contributors (ODbL).
 */
@Serializable
data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
)

@Serializable
data class OverpassElement(
    val type: String = "",
    val id: Long = 0,
    val tags: Map<String, String> = emptyMap(),
    // Present on ways (`out geom`): the ordered ring/line vertices.
    val geometry: List<OverpassPoint>? = null,
    // Present on relations: outer/inner member ways with their own inline geometry.
    val members: List<OverpassMember>? = null,
)

@Serializable
data class OverpassMember(
    val type: String = "",
    val role: String = "",
    val geometry: List<OverpassPoint>? = null,
)

@Serializable
data class OverpassPoint(
    val lat: Double,
    val lon: Double,
)
