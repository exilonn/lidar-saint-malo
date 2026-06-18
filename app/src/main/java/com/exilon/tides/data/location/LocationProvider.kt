package com.exilon.tides.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Thin wrapper over FusedLocationProvider (COARSE is enough to pick a tide station) plus the
 * built-in [Geocoder] for a human place name. Callers must check [hasPermission] first.
 */
class LocationProvider(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by the repository via hasPermission()
    suspend fun currentLocation(): Location? {
        // Last known location is instant and usually fine; fall back to a fresh balanced fix.
        fused.lastLocation.await()?.let { return it }
        val cts = CancellationTokenSource()
        return fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
    }

    /** Best-effort reverse geocode to a city/area name; null if unavailable. */
    suspend fun placeName(lat: Double, lng: Double): String? = placeInfo(lat, lng).name

    /**
     * Reverse-geocodes [lat]/[lng] to a place name plus admin area ("region") and country, all
     * requested in [locale]. Callers should pass an *explicit* locale derived from Room's stored
     * language tag (the source of truth) rather than relying on [Locale.getDefault] — that process
     * default is only updated by [com.exilon.tides.ui.LocaleManager.wrap] on the *next* Activity
     * recreation, so a reactive recompute that fires the instant the language changes (before the
     * recreation happens) would otherwise capture the *old* locale and never retry.
     */
    suspend fun placeInfo(lat: Double, lng: Double, locale: Locale = Locale.getDefault()): PlaceInfo =
        withContext(Dispatchers.IO) {
            runCatching {
                // The async getFromLocation overload is API 33+; the synchronous one still works on
                // all our min-26 targets and is simplest off the main thread.
                @Suppress("DEPRECATION")
                val results = Geocoder(context, locale).getFromLocation(lat, lng, 1)
                val address = results?.firstOrNull()
                PlaceInfo(
                    name = address?.locality ?: address?.subAdminArea ?: address?.adminArea,
                    region = address?.adminArea?.takeIf { it.isNotBlank() },
                    country = address?.countryName?.takeIf { it.isNotBlank() },
                )
            }.getOrDefault(PlaceInfo(null, null, null))
        }
}

/** Locale-aware reverse-geocode result; [region]/[country] come back already in [Locale.getDefault]. */
data class PlaceInfo(val name: String?, val region: String?, val country: String?)
