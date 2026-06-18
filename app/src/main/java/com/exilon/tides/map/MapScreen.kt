package com.exilon.tides.map

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.exilon.tides.BuildConfig
import com.exilon.tides.R
import com.exilon.tides.data.model.TideMath
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.theme.LocalTidePalette
import com.exilon.tides.ui.theme.LocalTidePrefs

private const val WINDOW_HOURS = 12L
private const val WINDOW_MS = WINDOW_HOURS * 3_600_000L

/** Max H_LAT reachable by the debug slider. Intentionally above PHMA (13.59 m) so the v1.2+
 *  top buffer bands (z_max up to +8.301 m IGN69 = H_LAT 14.59) can be exercised in QA. */
private const val DEBUG_H_LAT_MAX_M = 14.6f

/** Saint-Malo SHOM RAM record 38: Plus Hautes/Basses Mers Astronomiques (metres above LAT/ZH).
 *  Used to flag predictions that fall outside the physically-observable range.
 *  FES2022 is a continuous model; it can produce H_LAT slightly outside these harmonic bounds. */
private const val PHMA_H_LAT_M = 13.59  // Plus Hautes Mers Astronomiques
private const val PBMA_H_LAT_M = 0.01   // Plus Basses Mers Astronomiques

private fun mapTilerStyleUrl(): String =
    "https://api.maptiler.com/maps/basic-v2/style.json?key=${BuildConfig.MAPTILER_KEY}"

/** Interactive tide map: themed basemap + GPS pin + a 12 h time slider that crossfades the OSM
 *  intertidal overlay (beach shown at low tide, covered by sea at high tide) via [TideMath]. */
@Composable
fun MapScreen(
    state: MapUiState,
    estran: EstranUiState,
    onLoadEstran: (stationId: String, lat: Double, lng: Double) -> Unit,
    lidar: LidarUiState,
    onLoadLidar: (stationId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is MapUiState.Ready -> MapContent(state, estran, onLoadEstran, lidar, onLoadLidar, onBack, modifier)
        MapUiState.Loading -> MapMessage(stringResource(R.string.fetching), onBack, modifier)
        MapUiState.Unavailable -> MapMessage(stringResource(R.string.map_unavailable), onBack, modifier)
    }
}

@Composable
private fun MapContent(
    state: MapUiState.Ready,
    estran: EstranUiState,
    onLoadEstran: (stationId: String, lat: Double, lng: Double) -> Unit,
    lidar: LidarUiState,
    onLoadLidar: (stationId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalTidePalette.current
    val prefs = LocalTidePrefs.current
    val context = LocalContext.current

    // Diagnostic: confirm the key is actually present at runtime (length only — never log the
    // secret itself to logcat). A blank key is the #1 cause of a blank map.
    LaunchedEffect(Unit) {
        Log.d("MapDebug", "MAPTILER_KEY blank=${BuildConfig.MAPTILER_KEY.isBlank()} length=${BuildConfig.MAPTILER_KEY.length}")
    }
    // Without a key the MapTiler style 403s and nothing renders — surface a clear, actionable
    // message instead of an unexplained blank map.
    if (BuildConfig.MAPTILER_KEY.isBlank()) {
        MapMessage(stringResource(R.string.map_key_missing), onBack, modifier)
        return
    }

    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    val mapView = rememberMapViewWithLifecycle()
    val controller = remember { TideMapController(mapTilerStyleUrl()) }

    // Load the basemap + frame the station once. Re-skin on theme change.
    LaunchedEffect(state.stationId) {
        controller.attach(mapView, state.lat, state.lng, palette, hasLocationPermission)
    }
    LaunchedEffect(palette) { controller.setPalette(palette) }

    // Fetch (or serve cached) intertidal data for this station, and hand it to the map when ready.
    LaunchedEffect(state.stationId) { onLoadEstran(state.stationId, state.lat, state.lng) }
    LaunchedEffect(estran) {
        if (estran is EstranUiState.Ready) controller.setEstran(estran.geoJson)
    }

    // Fetch (or serve cached) LiDAR zone asset; if available, activates Level-3 rendering.
    LaunchedEffect(state.stationId) { onLoadLidar(state.stationId) }
    LaunchedEffect(lidar) {
        if (lidar is LidarUiState.Ready) controller.loadLidar(lidar.geoJson, lidar.zhRef)
    }

    // Slider window: now → now + 12 h, anchored once per screen session.
    val nowAnchor = remember { System.currentTimeMillis() }
    val windowEnd = nowAnchor + WINDOW_MS
    var sliderValue by remember { mutableFloatStateOf(0f) } // 0 = now, 1 = +12 h
    val selectedMillis = nowAnchor + (sliderValue * WINDOW_MS).toLong()

    val height = remember(state.extremes, selectedMillis) {
        TideMath.heightAt(state.extremes, selectedMillis)
    }
    val rising = remember(state.extremes, selectedMillis) {
        TideMath.isRising(state.extremes, selectedMillis)
    }
    val fraction = remember(state.extremes, selectedMillis) {
        TideMath.fractionAt(state.extremes, selectedMillis, nowAnchor, windowEnd)
    }

    // Debug height override — set by the debug slider (DEBUG builds only). Declared unconditionally
    // to satisfy Compose's no-conditional-remember rule; always null in release since no UI sets it.
    var debugHeightOverride by remember { mutableStateOf<Float?>(null) }

    // Effective height fed to the map: debug slider overrides predictions when active.
    val effectiveHeight: Double? = if (BuildConfig.DEBUG) {
        debugHeightOverride?.toDouble() ?: height
    } else {
        height
    }

    // Level-3 (LiDAR): drive the waterline from absolute IGN69 height = H_LAT + ZH_Ref.
    // Uses effectiveHeight so the debug override (DEBUG builds) feeds the same absolute path
    // as real predictions — it must NOT go through the fraction mapping below.
    // Level-2 (crossfade): drive from the normalized fraction. setTideFraction is a no-op when
    // LiDAR is active (those layers are frozen), so both effects can coexist safely.
    LaunchedEffect(effectiveHeight, lidar) {
        if (lidar is LidarUiState.Ready) effectiveHeight?.let { controller.setTideHeight(it) }
    }
    LaunchedEffect(fraction) { controller.setTideFraction(fraction ?: 0f) }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Back affordance over the map.
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.placeName,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onScene,
            )
        }

        // Bottom control panel: disclaimer (always visible) + time slider + live readout.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.map_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Graceful degradation: tell the user when OSM has no intertidal data here, so the
                // unmoving overlay isn't mistaken for a bug. Map + slider readout still work.
                val noIntertidal = estran is EstranUiState.Unavailable ||
                    (estran is EstranUiState.Ready && !estran.hasIntertidal)
                if (noIntertidal) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.map_no_intertidal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))

                // DEBUG ONLY: absolute H_LAT override slider, above the production controls.
                // Feeds setTideHeight directly — same path as real predictions, never via fraction.
                // Touching the production slider clears the override and returns to live data.
                if (BuildConfig.DEBUG) {
                    DebugTideSlider(
                        lidar = lidar,
                        debugHeight = debugHeightOverride,
                        onDebugHeightChange = { debugHeightOverride = it },
                        onReset = { debugHeightOverride = null },
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = TideFormat.time(selectedMillis, use24h = prefs.use24h, zone = state.zone),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = height?.let { TideFormat.height(it, useMetric = prefs.useMetric) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.tideAccent(rising ?: false),
                    )
                    Text(
                        text = when (rising) {
                            true -> stringResource(R.string.rising)
                            false -> stringResource(R.string.falling)
                            null -> "—"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // FES2022 is a continuous model and can predict H_LAT slightly outside the
                // harmonic SHOM RAM PBMA/PHMA bounds. Flag (don't clamp) so the user sees it.
                val physicalFlag: String? = height?.let { h ->
                    when {
                        h > PHMA_H_LAT_M -> ">PHMA — non physique"
                        h < PBMA_H_LAT_M -> "<PBMA — niveau improbable"
                        else -> null
                    }
                }
                physicalFlag?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                    )
                }
                Slider(
                    value = sliderValue,
                    // Touching the production slider clears any active debug override.
                    onValueChange = { sliderValue = it; if (BuildConfig.DEBUG) debugHeightOverride = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * DEBUG-ONLY widget: absolute H_LAT override slider with live readout.
 * Thumb and track turn error-red when the override is active. "LIVE" button clears the override.
 * Only composed inside a `if (BuildConfig.DEBUG)` block — never reaches release builds.
 */
@Composable
private fun DebugTideSlider(
    lidar: LidarUiState,
    debugHeight: Float?,
    onDebugHeightChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val zhRef = (lidar as? LidarUiState.Ready)?.zhRef
    val active = debugHeight != null

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DBG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = when {
                    active && zhRef != null -> {
                        val base = "H_LAT %.2f m  IGN69 %.2f m  ZH_Ref %.4f m"
                            .format(debugHeight, debugHeight!! + zhRef, zhRef)
                        val flag = when {
                            debugHeight!! > PHMA_H_LAT_M.toFloat() -> "  >PHMA — non physique"
                            debugHeight < PBMA_H_LAT_M.toFloat()   -> "  <PBMA — niveau improbable"
                            else -> ""
                        }
                        base + flag
                    }
                    active ->
                        "H_LAT %.2f m  (no LiDAR zone loaded)".format(debugHeight)
                    else -> "— touch to override —"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            if (active) {
                TextButton(
                    onClick = onReset,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Slider(
            value = debugHeight ?: (DEBUG_H_LAT_MAX_M / 2f),
            onValueChange = onDebugHeightChange,
            valueRange = 0f..DEBUG_H_LAT_MAX_M,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = if (active) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                activeTrackColor = if (active) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            ),
        )
    }
}

@Composable
private fun MapMessage(message: String, onBack: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }
    }
}
