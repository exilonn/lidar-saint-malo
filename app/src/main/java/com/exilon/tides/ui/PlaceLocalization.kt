package com.exilon.tides.ui

import java.util.Locale

/**
 * Renders a station's "region, country" subtitle live in the app's current locale.
 *
 * [com.exilon.tides.data.local.entity.StationEntity.country] is stored as the raw name TideCheck
 * (or, for the current-location station, the Geocoder) returned at fetch time — for TideCheck
 * that's always English ("United States", "Japan"). Rather than freeze a pre-formatted English
 * string, we reverse-map that name to an ISO country code and re-render the display name in
 * [Locale.getDefault] every time this is called, so it updates immediately when the user switches
 * the app's language (no re-fetch needed). The region/admin part is localized separately and
 * upstream — callers pass the already-localized region (resolved via Nominatim by
 * [com.exilon.tides.data.TideRepository.localizedRegion]); [subtitle] renders it as-is.
 */
object PlaceLocalization {

    private val countryCodeByEnglishName: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { code ->
            Locale("", code).getDisplayCountry(Locale.US).lowercase(Locale.US)
        }
    }

    /** Best-effort localization of a country name captured in English; returns it unchanged if unmapped. */
    fun localizedCountry(rawCountry: String?): String? {
        val name = rawCountry?.takeIf { it.isNotBlank() } ?: return null
        val code = countryCodeByEnglishName[name.lowercase(Locale.US)] ?: return name
        return Locale("", code).getDisplayCountry(Locale.getDefault())
    }

    /** "Region, Country" (whichever parts are present, country localized live), or null. */
    fun subtitle(region: String?, country: String?): String? =
        listOfNotNull(region?.takeIf { it.isNotBlank() }, localizedCountry(country))
            .joinToString(", ")
            .ifBlank { null }
}
