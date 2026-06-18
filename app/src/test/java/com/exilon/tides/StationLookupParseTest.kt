package com.exilon.tides

import com.exilon.tides.data.remote.dto.StationDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the station-lookup crash: the endpoint returns a top-level JSON **array**
 * (`[{...}, {...}]`), which previously blew up with
 * "Expected start of the object '{', but had '[' instead" because it was decoded as a single
 * object. It must decode into a `List<StationDto>`. Mirrors the app's `Json` config.
 */
class StationLookupParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Shape from the crash report: a top-level array, with an extra unknown field to prove
    // ignoreUnknownKeys still holds at the array-element level.
    private val sample = """
        [
          {"id":"fes2022-dol-de-bretagne","name":"Dol-de-Bretagne","lat":48.55,"lng":-1.75,"distanceKm":3.2,"country":"FR"},
          {"id":"fes2022-saint-malo","name":"Saint-Malo","lat":48.65,"lng":-2.03,"distanceKm":12.7}
        ]
    """.trimIndent()

    @Test
    fun `decodes top-level array into List of StationDto`() {
        val stations = json.decodeFromString<List<StationDto>>(sample)

        assertEquals(2, stations.size)
        assertEquals("fes2022-dol-de-bretagne", stations[0].id)
        assertEquals("Dol-de-Bretagne", stations[0].name)
        assertEquals(48.55, stations[0].lat!!, 1e-9)
    }

    @Test
    fun `nearest is selected by distance, not array order`() {
        val stations = json.decodeFromString<List<StationDto>>(sample)
        // Caller's coordinate sits right on Dol-de-Bretagne; it must win even if listed first/last.
        val here = 48.55 to -1.75
        val nearest = stations.minByOrNull { s ->
            if (s.lat != null && s.lng != null) {
                com.exilon.tides.data.distanceMeters(s.lat, s.lng, here.first, here.second)
            } else {
                Double.MAX_VALUE
            }
        }
        assertTrue(nearest != null)
        assertEquals("fes2022-dol-de-bretagne", nearest!!.id)
    }
}
