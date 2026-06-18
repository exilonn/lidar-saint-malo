package com.exilon.tides.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nominatim (OpenStreetMap) reverse-geocoding response, `format=jsonv2`. Used **only** to obtain a
 * localized admin/region name (e.g. "Bretagne" vs "Brittany") — the one thing Android's built-in
 * [android.location.Geocoder] won't translate, since `getAdminArea()` ignores the requested Locale.
 * Decoded with `ignoreUnknownKeys = true`, so only the address fields we use are declared.
 *
 * Data © OpenStreetMap contributors (ODbL). Requests must carry an identifying User-Agent and stay
 * within ~1 request/second — both enforced in [com.exilon.tides.di.ServiceLocator] and
 * [com.exilon.tides.data.TideRepository].
 */
@Serializable
data class NominatimReverseDto(
    val address: NominatimAddressDto? = null,
)

@Serializable
data class NominatimAddressDto(
    // The admin level that reads as the "region" varies by country; try the most-specific
    // region-ish field first and fall back. ("state" = région in FR, state in US, etc.)
    val state: String? = null,
    val region: String? = null,
    val province: String? = null,
    val county: String? = null,
    @SerialName("state_district") val stateDistrict: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
) {
    /** The best available localized region/admin label, or null if none. */
    fun regionName(): String? =
        listOf(state, region, province, county, stateDistrict)
            .firstOrNull { !it.isNullOrBlank() }
}
