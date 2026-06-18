package com.exilon.tides.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.model.DayTides
import com.exilon.tides.data.model.TideData
import com.exilon.tides.data.model.TideSnapshot
import com.exilon.tides.data.model.TideType
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.TideUiState
import com.exilon.tides.ui.theme.LocalTidePalette
import com.exilon.tides.ui.theme.LocalTidePrefs
import java.time.ZoneId
import com.exilon.tides.ui.screen.components.DailyForecastList
import com.exilon.tides.ui.screen.components.FrostedCard
import com.exilon.tides.ui.screen.components.LocationBar
import com.exilon.tides.ui.screen.components.NextTidesRow
import com.exilon.tides.ui.screen.components.SkyScene
import com.exilon.tides.ui.screen.components.StationPickerPanel
import com.exilon.tides.ui.screen.components.TideArrowDown
import com.exilon.tides.ui.screen.components.TideArrowUp
import com.exilon.tides.ui.screen.components.TideCurveCanvas
import com.exilon.tides.ui.screen.components.WaterSurface
import com.exilon.tides.ui.screen.components.WaveLoadingIndicator
import com.exilon.tides.ui.screen.components.WindArrow
import com.exilon.tides.ui.screen.components.frostSource
import com.exilon.tides.ui.screen.components.rememberFrostState
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun TideScreen(
    state: TideUiState,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMap: () -> Unit,
    onSelectStation: (String) -> Unit,
    onRemoveStation: (String) -> Unit,
    localizedRegions: Map<String, String?> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        when (state) {
            TideUiState.Loading -> LoadingView()
            TideUiState.PermissionRequired -> PermissionView(onRequestPermission, onOpenLocationSettings)
            is TideUiState.Error -> ErrorView(state.message, onRefresh)
            is TideUiState.NoStation -> NoStationView(state.distanceKm, onOpenSearch)
            is TideUiState.Ready -> ReadyContent(
                state = state,
                onRefresh = onRefresh,
                onOpenSearch = onOpenSearch,
                onOpenSettings = onOpenSettings,
                onOpenMap = onOpenMap,
                onSelectStation = onSelectStation,
                onRemoveStation = onRemoveStation,
                localizedRegions = localizedRegions,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    state: TideUiState.Ready,
    onRefresh: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onSelectStation: (String) -> Unit,
    onRemoveStation: (String) -> Unit,
    localizedRegions: Map<String, String?>,
) {
    val places = state.places
    val selected = state.selected
    var showPicker by remember { mutableStateOf(false) }
    var detailsDay by remember(selected?.stationId) { mutableStateOf<DayTides?>(null) }
    val now by rememberNow()

    val rootFrost = rememberFrostState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().frostSource(rootFrost)) {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (selected != null) {
                    PlacePage(
                        data = selected,
                        now = now,
                        transientError = state.transientError,
                        onOpenPicker = { showPicker = true },
                        onOpenSettings = onOpenSettings,
                        onOpenMap = onOpenMap,
                        onOpenDetails = { detailsDay = it },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = detailsDay != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            val day = detailsDay
            val prefs = LocalTidePrefs.current
            if (day != null && selected != null) {
                DayDetailsScreen(
                    day = day,
                    nowMillis = now,
                    zone = selected.stationZone,
                    useMetric = prefs.useMetric,
                    use24h = prefs.use24h,
                    onBack = { detailsDay = null },
                )
            }
        }

        AnimatedVisibility(
            visible = showPicker,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showPicker = false },
            )
        }
        AnimatedVisibility(
            visible = showPicker,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            StationPickerPanel(
                places = places,
                selectedId = state.selected?.stationId,
                frost = rootFrost,
                onSelect = { showPicker = false; onSelectStation(it) },
                onRemove = onRemoveStation,
                onAdd = { showPicker = false; onOpenSearch() },
                localizedRegions = localizedRegions,
            )
        }
    }
}

@Composable
private fun PlacePage(
    data: TideData,
    now: Long,
    transientError: String?,
    onOpenPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenDetails: (DayTides) -> Unit,
) {
    val zone = data.stationZone
    val snapshot = remember(data, now) { data.snapshotAt(now) }
    val days = remember(data, zone) { data.byDay(zone = zone) }

    // Water fills to the current level between *today's* min and max extreme.
    val todayDay = remember(days, zone) {
        val today = LocalDate.now(zone).toString()
        days.firstOrNull { it.date == today }
    }
    val level = remember(snapshot, todayDay, data) {
        val ext = todayDay?.extremes ?: data.extremes
        val lo = ext.minOfOrNull { it.heightMeters }
        val hi = ext.maxOfOrNull { it.heightMeters }
        if (snapshot != null && lo != null && hi != null && hi > lo) {
            ((snapshot.currentHeightMeters - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        } else 0.5f
    }

    val todayCond = remember(data, zone) {
        val today = LocalDate.now(zone).toString()
        data.conditions.firstOrNull { it.date == today }
    }
    val sunrise = remember(todayCond, zone) { TideFormat.sunEventMillis(todayCond?.sunrise, zone) }
    val sunset = remember(todayCond, zone) { TideFormat.sunEventMillis(todayCond?.sunset, zone) }

    val rising = snapshot?.isRising ?: false
    val accent = LocalTidePalette.current.tideAccent(rising)
    val frost = rememberFrostState()

    Box(Modifier.fillMaxSize()) {
        // The procedural hero scene, captured once for the frosted-glass backdrops above it.
        Box(Modifier.fillMaxSize().frostSource(frost)) {
            SkyScene(nowMillis = now, sunriseMillis = sunrise, sunsetMillis = sunset)
            if (snapshot != null) {
                WaterSurface(level = level, rising = rising, accent = accent)
            }
        }

        // Readings float over the scene and scroll.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            transientError?.let { TransientBanner(it) }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LocationBar(
                    placeName = data.placeName,
                    onPickerClick = onOpenPicker,
                    frost = frost,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onOpenMap) {
                    Icon(
                        Icons.Filled.Map,
                        contentDescription = stringResource(R.string.map),
                        tint = LocalTidePalette.current.onScene,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = LocalTidePalette.current.onScene,
                    )
                }
            }

            if (snapshot != null) {
                Spacer(Modifier.height(40.dp))
                HeroReadout(
                    snapshot = snapshot,
                    nowMillis = now,
                    zone = zone,
                    accent = accent,
                    airC = data.currentAirC,
                    seaC = data.currentSeaC,
                    windSpeedKmh = data.currentWindSpeedKmh,
                    windDirectionDeg = data.currentWindDirectionDeg,
                    onTempClick = todayDay?.let { day -> { onOpenDetails(day) } },
                )
                Spacer(Modifier.height(36.dp))
                FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                    TideCurveCanvas(
                        extremes = data.extremes,
                        nowMillis = now,
                        zone = zone,
                        accent = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                NextTidesRow(
                    nextHigh = snapshot.nextHigh,
                    nextLow = snapshot.nextLow,
                    nowMillis = now,
                    zone = zone,
                    frost = frost,
                )
            } else {
                Spacer(Modifier.height(48.dp))
                FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                    Text(
                        stringResource(R.string.tide_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            if (days.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                    DailyForecastList(
                        days = days,
                        nowMillis = now,
                        zone = zone,
                        onDayClick = onOpenDetails,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The focal block floating over the scene: rising/falling + the big tide-height numeral. */
@Composable
private fun HeroReadout(
    snapshot: TideSnapshot,
    nowMillis: Long,
    zone: ZoneId,
    accent: Color,
    airC: Double?,
    seaC: Double?,
    windSpeedKmh: Double?,
    windDirectionDeg: Double?,
    onTempClick: (() -> Unit)? = null,
) {
    val onScene = LocalTidePalette.current.onScene
    val prefs = LocalTidePrefs.current
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.35f), offset = Offset(0f, 2f), blurRadius = 12f)
    val context = LocalContext.current
    // Even rhythm between the three secondary lines below the big height numeral (temp, wind,
    // next-tide countdown) — each renders conditionally, so each carries its own leading gap.
    val lineGap = 6.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (snapshot.isRising) TideArrowUp else TideArrowDown,
                contentDescription = null,
                tint = accent,
            )
            Text(
                text = stringResource(if (snapshot.isRising) R.string.rising else R.string.falling),
                style = MaterialTheme.typography.titleLarge.copy(shadow = shadow),
                color = onScene,
            )
        }
        Text(
            text = TideFormat.height(snapshot.currentHeightMeters, useMetric = prefs.useMetric),
            style = MaterialTheme.typography.displayLarge.copy(shadow = shadow),
            fontWeight = FontWeight.SemiBold,
            color = onScene,
        )
        if (airC != null || seaC != null) {
            Spacer(Modifier.height(lineGap))
            Text(
                text = stringResource(R.string.hero_air_sea, TideFormat.temp(airC, prefs.useMetric), TideFormat.temp(seaC, prefs.useMetric)),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                color = onScene,
                modifier = if (onTempClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.details), onClick = onTempClick)
                } else {
                    Modifier
                },
            )
        }
        if (windSpeedKmh != null) {
            Spacer(Modifier.height(lineGap))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WindArrow(directionDeg = windDirectionDeg, size = 16.dp, tint = onScene)
                Text(
                    text = stringResource(
                        R.string.hero_wind,
                        TideFormat.windSpeed(windSpeedKmh, prefs.useMetric),
                        windDirectionDeg?.let { TideFormat.compassLabel(it) } ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                    color = onScene,
                )
            }
        }
        snapshot.nextExtreme?.let { next ->
            val template = if (next.type == TideType.HIGH) R.string.hero_next_high else R.string.hero_next_low
            Spacer(Modifier.height(lineGap))
            Text(
                text = stringResource(
                    template,
                    TideFormat.countdown(context, nowMillis, next.timeMillis),
                    TideFormat.time(next.timeMillis, use24h = prefs.use24h, zone = zone),
                ),
                style = MaterialTheme.typography.bodyMedium.copy(shadow = shadow),
                color = onScene.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
private fun TransientBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.showing_saved, message), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Centered placeholder content over the themed hero gradient. */
@Composable
private fun PlaceholderScene(content: @Composable ColumnScope.() -> Unit) {
    val now = remember { System.currentTimeMillis() }
    Box(Modifier.fillMaxSize()) {
        SkyScene(nowMillis = now, sunriseMillis = null, sunsetMillis = null)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun LoadingView() {
    val palette = LocalTidePalette.current
    PlaceholderScene {
        WaveLoadingIndicator(color = palette.rising)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.fetching),
            style = MaterialTheme.typography.titleMedium,
            color = palette.onScene,
        )
    }
}

@Composable
private fun PermissionView(onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    ThemedMessage(
        icon = Icons.Filled.LocationOn,
        title = stringResource(R.string.location_needed_title),
        body = stringResource(R.string.location_needed_body),
    ) {
        Button(onClick = onRequestPermission) { Text(stringResource(R.string.grant_location)) }
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    ThemedMessage(icon = Icons.Filled.CloudOff, title = stringResource(R.string.cant_load_title), body = message) {
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun NoStationView(distanceKm: Double?, onOpenSearch: () -> Unit) {
    val body = if (distanceKm != null) {
        stringResource(R.string.no_station_body_distance, distanceKm.roundToInt())
    } else {
        stringResource(R.string.no_station_body)
    }
    ThemedMessage(
        icon = Icons.Filled.LocationOff,
        title = stringResource(R.string.no_station_title),
        body = body,
    ) {
        Button(onClick = onOpenSearch) { Text(stringResource(R.string.search_a_location)) }
    }
}

@Composable
private fun ThemedMessage(
    icon: ImageVector,
    title: String,
    body: String,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTidePalette.current
    PlaceholderScene {
        Icon(icon, null, Modifier.size(48.dp), tint = palette.onScene)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.onScene)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onScene.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

/** A clock that ticks every [periodMs] so countdowns and the "now" marker stay live. */
@Composable
private fun rememberNow(periodMs: Long = 30_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
