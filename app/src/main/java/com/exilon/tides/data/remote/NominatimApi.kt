package com.exilon.tides.data.remote

import com.exilon.tides.data.remote.dto.NominatimReverseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OpenStreetMap Nominatim reverse geocoding, used solely to resolve a **localized admin/region
 * name** via `accept-language` — Android's [android.location.Geocoder] won't translate the admin
 * area regardless of the Locale passed to it. Absolute URL so it overrides the placeholder base
 * URL; runs on its own client (no TideCheck key, dedicated User-Agent — see the usage policy at
 * https://operations.osmfoundation.org/policies/nominatim/). Free, no key. © OpenStreetMap.
 */
interface NominatimApi {

    @GET("https://nominatim.openstreetmap.org/reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lng: Double,
        @Query("accept-language") language: String,
        @Query("format") format: String = "jsonv2",
        @Query("zoom") zoom: Int = 10, // ~admin level; we only need the region, not a street address
        @Query("addressdetails") addressDetails: Int = 1,
    ): NominatimReverseDto
}
