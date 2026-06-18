package com.exilon.tides.map

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import com.exilon.tides.ui.theme.Palettes
import com.exilon.tides.ui.theme.TidePalette
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos

/**
 * The only place that talks to MapLibre. Loads the MapTiler basemap, re-skins it from the active
 * [TidePalette] (D6), frames the camera on a fixed ~4 km box around the station (D4), shows the GPS
 * pin, and renders the intertidal overlay: two fill layers over the same estran geometry — substrate
 * (opacity 1−f) under water (opacity f) — so the beach shows at low tide and is covered by sea at
 * high tide as the slider moves (the per-polygon crossfade, D2).
 */
class TideMapController(private val styleUrl: String) {

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var palette: TidePalette = Palettes.Ocean
    private var attached = false

    // Level-2 estran overlay state (set may arrive before/after style load).
    private var pendingGeoJson: String? = null
    private var fraction: Float = 0f

    // Level-3 LiDAR overlay state.
    private var pendingLidarGeoJson: String? = null
    private var lidarZhRef: Double = -6.2890   // chart-datum IGN69 (Saint-Malo default; overridden in loadLidar)
    private var lidarActive = false

    /** Loads the style and frames the station. Idempotent — safe to call once per screen. */
    @SuppressLint("MissingPermission") // location guarded by [hasLocationPermission]
    fun attach(
        mapView: MapView,
        lat: Double,
        lng: Double,
        palette: TidePalette,
        hasLocationPermission: Boolean,
    ) {
        this.palette = palette
        if (attached) return
        attached = true
        // Surface tile/style/GL failures (e.g. a 403 from a missing/disallowed MapTiler key) in
        // logcat under "MapDebug" — these otherwise fail silently to a blank map.
        mapView.addOnDidFailLoadingMapListener { error ->
            Log.e("MapDebug", "MapLibre didFailLoadingMap: $error")
        }
        Log.d("MapDebug", "Loading style: ${styleUrl.substringBefore("key=")}key=<redacted>")
        mapView.getMapAsync { maplibre ->
            map = maplibre
            // Fix 2: north-up always — rotation gesture disabled, compass meaningless.
            maplibre.uiSettings.isRotateGesturesEnabled = false
            maplibre.uiSettings.isCompassEnabled = false
            maplibre.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                Log.d("MapDebug", "Style loaded OK (${loaded.layers.size} layers)")
                style = loaded
                applyBasemapTheme(loaded, this.palette)
                addEstranLayers(loaded)
                pendingLidarGeoJson?.let { applyLidarLayers(loaded, it) }
                centerOn(maplibre, lat, lng)
                if (hasLocationPermission) enableLocation(maplibre, loaded, mapView)
            }
        }
    }

    /** Re-skin the basemap (and overlay water colours) when the app theme changes. */
    fun setPalette(palette: TidePalette) {
        this.palette = palette
        val s = style ?: return
        applyBasemapTheme(s, palette)
        val waterArgb = palette.mapWater.toArgb()
        (s.getLayer(WATER_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillColor(waterArgb))
        (s.getLayer(LIDAR_WATER_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillColor(waterArgb))
    }

    /** Supply the station's intertidal GeoJSON (from [com.exilon.tides.data.EstranRepository]). */
    fun setEstran(geoJson: String) {
        pendingGeoJson = geoJson
        (style?.getSource(ESTRAN_SOURCE) as? GeoJsonSource)?.setGeoJson(geoJson)
    }

    /**
     * Activate Level-3 LiDAR rendering for the current station. Replaces the Level-2 opacity
     * crossfade with a filter-driven waterline: bands with z_min_m < targetElev render as water,
     * others show the OSM substrate underneath. Safe to call before or after style load.
     *
     * [zhRef] is the chart-datum elevation in IGN69 metres (e.g. −6.2890 for Saint-Malo).
     * The waterline is then driven by [setTideHeight] using water_IGN69 = H_LAT + zhRef.
     */
    fun loadLidar(geoJson: String, zhRef: Double) {
        lidarZhRef = zhRef
        lidarActive = true
        pendingLidarGeoJson = geoJson
        val s = style ?: return
        applyLidarLayers(s, geoJson)
    }

    /**
     * Drive the Level-3 waterline from the absolute predicted tide height (metres above LAT/ZH).
     * Computes target IGN69 elevation as water_IGN69 = hZH + zhRef, then updates the filter on
     * the lidar-water fill layer. No-op if Level-3 is not active or the style isn't loaded yet.
     */
    fun setTideHeight(hZH: Double) {
        val s = style ?: return
        if (!lidarActive) return
        (s.getLayer(LIDAR_WATER_LAYER) as? FillLayer)
            ?.setFilter(lidarWaterFilter(hZH + lidarZhRef))
    }

    /**
     * Drive the Level-2 intertidal crossfade from the tide fraction (0 = low water, 1 = high).
     * No-op when Level-3 LiDAR is active — the crossfade layers are frozen; [setTideHeight]
     * drives the waterline instead.
     *
     * Fix 4 — rock partial opacity: bare_rock/reef use a capped water-layer opacity
     * ([ROCK_WATER_MAX_OPACITY]) as a best-effort hint that rocks may still protrude at high tide.
     */
    fun setTideFraction(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        if (lidarActive) return   // Level-2 layers are frozen; waterline driven by setTideHeight
        val s = style ?: return
        (s.getLayer(SUBSTRATE_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillOpacity(substrateOpacityExpr(fraction)))
        (s.getLayer(WATER_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillOpacity(waterOpacityExpr(fraction)))
    }

    /**
     * Sets up (or updates) the LiDAR source and water layer. Freezes the Level-2 crossfade:
     * substrate at fixed [LIDAR_SUBSTRATE_OPACITY], estran-water hidden. The lidar-water layer
     * sits above the substrate and filters bands by z_min_m vs the computed IGN69 target elevation.
     */
    private fun applyLidarLayers(style: Style, geoJson: String) {
        // Freeze Level-2 crossfade layers.
        (style.getLayer(SUBSTRATE_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillOpacity(LIDAR_SUBSTRATE_OPACITY))
        (style.getLayer(WATER_LAYER) as? FillLayer)
            ?.setProperties(PropertyFactory.fillOpacity(0f))

        // Add or refresh the LiDAR GeoJSON source.
        val source = style.getSource(LIDAR_SOURCE) as? GeoJsonSource
        if (source == null) {
            style.addSource(GeoJsonSource(LIDAR_SOURCE, geoJson))
        } else {
            source.setGeoJson(geoJson)
        }

        // Add the lidar-water fill layer once, below the first symbol (label) layer.
        if (style.getLayer(LIDAR_WATER_LAYER) == null) {
            val firstSymbolId = style.layers.firstOrNull { it.isSymbolLayer() }?.id
            val lidarFill = FillLayer(LIDAR_WATER_LAYER, LIDAR_SOURCE).withProperties(
                PropertyFactory.fillColor(palette.mapWater.toArgb()),
                PropertyFactory.fillOpacity(1.0f),
                PropertyFactory.fillAntialias(true),
            )
            lidarFill.setFilter(lidarWaterFilter(lidarZhRef))  // initial: permanent floor shown; tide bands update via setTideHeight
            if (firstSymbolId != null) style.addLayerBelow(lidarFill, firstSymbolId)
            else style.addLayer(lidarFill)
        }
    }

    /**
     * Filter expression for the lidar-water layer. Shows a feature when EITHER:
     *   - its floor elevation (z_min_m) is below the current IGN69 tide surface, OR
     *   - it is tagged permanent=true (deep channel floor, always submerged regardless of H_LAT).
     * Null z_min_m (permanent floor feature) evaluates to false in the lt() branch, so the
     * permanent branch is the sole reason those features are shown.
     */
    private fun lidarWaterFilter(targetElevIgn69: Double): Expression =
        Expression.any(
            Expression.lt(Expression.get("z_min_m"), Expression.literal(targetElevIgn69)),
            Expression.eq(Expression.get("permanent"), Expression.literal(true)),
        )

    private fun addEstranLayers(style: Style) {
        if (style.getSource(ESTRAN_SOURCE) != null) return
        style.addSource(GeoJsonSource(ESTRAN_SOURCE))

        // Fix 1: insert our layers BELOW the first symbol (text/icon) layer so basemap labels
        // remain visible above the substrate overlay. Falls back to addLayer (on top of everything)
        // if the style has no symbol layers — better than crashing.
        val firstSymbolId = style.layers.firstOrNull { it.isSymbolLayer() }?.id

        val substrateFill = FillLayer(SUBSTRATE_LAYER, ESTRAN_SOURCE).withProperties(
            PropertyFactory.fillColor(EstranStyle.substrateFillColor()),
            PropertyFactory.fillOpacity(substrateOpacityExpr(fraction)),
            PropertyFactory.fillAntialias(true),
        )
        val waterFill = FillLayer(WATER_LAYER, ESTRAN_SOURCE).withProperties(
            PropertyFactory.fillColor(palette.mapWater.toArgb()),
            PropertyFactory.fillOpacity(waterOpacityExpr(fraction)),
            PropertyFactory.fillAntialias(true),
        )

        if (firstSymbolId != null) {
            style.addLayerBelow(substrateFill, firstSymbolId)
            style.addLayerBelow(waterFill, firstSymbolId)
        } else {
            style.addLayer(substrateFill)
            style.addLayer(waterFill)
        }

        pendingGeoJson?.let { (style.getSource(ESTRAN_SOURCE) as? GeoJsonSource)?.setGeoJson(it) }
    }

    /**
     * Substrate (sand/gravel/rock/mud) fades out as the tide rises — EXCEPT for rock, which stays
     * partially visible underneath the water layer as a hint of emergence. 1.0 at low tide for all.
     */
    private fun substrateOpacityExpr(f: Float): Expression {
        val base = (1f - f).coerceIn(0f, 1f)
        val rockBase = base.coerceAtLeast(ROCK_SUBSTRATE_MIN_OPACITY)
        return Expression.match(
            Expression.get("substrate"),
            Expression.literal(com.exilon.tides.data.Substrate.ROCK.name),
            Expression.literal(rockBase),
            Expression.literal(base), // default for SAND, GRAVEL, MUD
        )
    }

    /**
     * Water opacity rises with the tide — capped at [ROCK_WATER_MAX_OPACITY] for rock features so
     * they remain partially visible at high tide (best-effort; no DEM available).
     */
    private fun waterOpacityExpr(f: Float): Expression {
        val base = f.coerceIn(0f, 1f)
        val rockCapped = base.coerceAtMost(ROCK_WATER_MAX_OPACITY)
        return Expression.match(
            Expression.get("substrate"),
            Expression.literal(com.exilon.tides.data.Substrate.ROCK.name),
            Expression.literal(rockCapped),
            Expression.literal(base), // default for SAND, GRAVEL, MUD
        )
    }

    private fun Layer.isSymbolLayer(): Boolean =
        this::class.simpleName?.contains("Symbol", ignoreCase = true) == true

    private fun centerOn(map: MapLibreMap, lat: Double, lng: Double) {
        val dLat = HALF_SPAN_M / METERS_PER_DEG_LAT
        val dLng = HALF_SPAN_M / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        val bounds = LatLngBounds.Builder()
            .include(LatLng(lat + dLat, lng + dLng))
            .include(LatLng(lat - dLat, lng - dLng))
            .build()
        // newLatLngBounds needs a measured map; fall back to a fixed zoom if it isn't yet.
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING_PX))
        }.onFailure {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), FALLBACK_ZOOM))
        }
    }

    /**
     * D6: defensively override a short, known list of MapTiler basemap layer ids with theme colours.
     * Every lookup is null-safe and type-checked because layer ids are style-version-dependent —
     * a missing id just means that layer keeps its default colour, never a crash.
     */
    private fun applyBasemapTheme(style: Style, palette: TidePalette) {
        val water = palette.mapWater.toArgb()
        val land = palette.mapLand.toArgb()
        (style.getLayer("background") as? BackgroundLayer)
            ?.setProperties(PropertyFactory.backgroundColor(land))
        for (id in WATER_LAYER_IDS) {
            (style.getLayer(id) as? FillLayer)?.setProperties(PropertyFactory.fillColor(water))
        }
        for (id in LAND_LAYER_IDS) {
            (style.getLayer(id) as? FillLayer)?.setProperties(PropertyFactory.fillColor(land))
        }
    }

    @SuppressLint("MissingPermission") // caller checked the coarse-location permission
    private fun enableLocation(map: MapLibreMap, style: Style, mapView: MapView) {
        val lc = map.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions
                .builder(mapView.context, style)
                .useDefaultLocationEngine(true)
                .build(),
        )
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.NONE // we frame the station, not the user
        lc.renderMode = RenderMode.NORMAL
    }

    private companion object {
        const val HALF_SPAN_M = 2_000.0 // ~4 km box around the station (D4)
        const val METERS_PER_DEG_LAT = 111_320.0
        const val CAMERA_PADDING_PX = 48
        const val FALLBACK_ZOOM = 13.5

        // MapTiler "basic-v2" style layer ids (D6) — defensive; absent ids are skipped.
        val WATER_LAYER_IDS = listOf("water", "water_shadow", "waterway")
        val LAND_LAYER_IDS = listOf("landcover", "landuse", "land")

        // Level-2 estran overlay ids.
        const val ESTRAN_SOURCE = "estran-src"
        const val SUBSTRATE_LAYER = "estran-substrate"
        const val WATER_LAYER = "estran-water"

        // Rock opacity caps (fix 4). Rocks may still protrude at high tide; applying a lower water
        // cap gives a best-effort visual hint. Accurate submergence requires Level 3 DEM.
        const val ROCK_WATER_MAX_OPACITY = 0.6f
        const val ROCK_SUBSTRATE_MIN_OPACITY = 0.35f

        // Level-3 LiDAR overlay ids.
        const val LIDAR_SOURCE = "lidar-src"
        const val LIDAR_WATER_LAYER = "lidar-water"
        const val LIDAR_SUBSTRATE_OPACITY = 0.85f
    }
}
