package com.exilon.tides.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.model.DayTides
import com.exilon.tides.data.model.HourlyWeather
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.screen.components.FrostState
import com.exilon.tides.ui.screen.components.FrostedCard
import com.exilon.tides.ui.screen.components.SkyScene
import com.exilon.tides.ui.screen.components.WindArrow
import com.exilon.tides.ui.screen.components.frostSource
import com.exilon.tides.ui.screen.components.rememberFrostState
import java.time.LocalDate
import java.time.ZoneId

/**
 * Hourly air/sea temperature + wind for one day, opened from the "Details" affordance on today's
 * hero or by tapping any "Coming days" row. Styled to match the active theme (same sky scene,
 * frosted-glass cards and type as the rest of the app).
 */
@Composable
fun DayDetailsScreen(
    day: DayTides,
    nowMillis: Long,
    zone: ZoneId,
    useMetric: Boolean,
    use24h: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frost = rememberFrostState()
    val onScene = Color.White
    val isToday = day.date == LocalDate.now(zone).toString()
    // Today: only the current hour onward is useful — an hour that has fully elapsed is dropped.
    // Future days keep every hour.
    val visibleHourly = remember(day, nowMillis, isToday) {
        if (isToday) day.hourly.filter { it.timeMillis + HOUR_MS > nowMillis } else day.hourly
    }

    Box(modifier.fillMaxSize()) {
        SkyScene(
            nowMillis = nowMillis,
            sunriseMillis = null,
            sunsetMillis = null,
            modifier = Modifier.frostSource(frost),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = onScene,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        TideFormat.dayLabel(LocalContext.current, day.date, zone = zone),
                        style = MaterialTheme.typography.titleLarge,
                        color = onScene,
                    )
                    Text(
                        stringResource(R.string.day_details_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = onScene.copy(alpha = 0.75f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (day.weather != null) {
                DaySummaryRow(day.weather.airMaxC, day.weather.airMinC, day.weather.seaC, useMetric, frost)
                Spacer(Modifier.height(16.dp))
            }

            if (visibleHourly.isEmpty()) {
                FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost, shape = RoundedCornerShape(24.dp)) {
                    Text(
                        stringResource(R.string.no_hourly_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                FrostedCard(
                    modifier = Modifier.fillMaxWidth(),
                    state = frost,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column {
                        HourColumnHeaders()
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                            itemsIndexed(visibleHourly, key = { _, h -> h.timeMillis }) { index, hour ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                HourRow(hour, zone, useMetric, use24h)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Air and sea temperature as two separate peer cards side by side, instead of one shared card. */
@Composable
private fun DaySummaryRow(airMaxC: Double?, airMinC: Double?, seaC: Double?, useMetric: Boolean, frost: FrostState?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(
            icon = Icons.Filled.Thermostat,
            label = stringResource(R.string.hourly_air),
            value = airHighLow(airMaxC, airMinC, useMetric),
            frost = frost,
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            icon = Icons.Filled.Waves,
            label = stringResource(R.string.hourly_sea),
            value = TideFormat.temp(seaC, useMetric),
            frost = frost,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCard(icon: ImageVector, label: String, value: String, frost: FrostState?, modifier: Modifier = Modifier) {
    FrostedCard(modifier = modifier, state = frost, shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun airHighLow(max: Double?, min: Double?, useMetric: Boolean): String = when {
    max != null && min != null -> "${TideFormat.temp(max, useMetric)} / ${TideFormat.temp(min, useMetric)}"
    max != null -> TideFormat.temp(max, useMetric)
    else -> "—"
}

/**
 * Shared column widths for [HourColumnHeaders] and [HourRow] — every cell across both reuses the
 * exact same width and alignment per column, so header and values land in the same x position
 * regardless of how wide any individual value happens to render (e.g. "8 km/h" vs "24 km/h").
 * Wind is wider than Air/Mer (not equal weight): it packs an arrow + number + unit, so a
 * double-digit speed needs more room than a bare temperature does.
 */
private val TimeColWidth: Dp = 56.dp
private val TempColWidth: Dp = 56.dp
private val WindColWidth: Dp = 96.dp

@Composable
private fun HourColumnHeaders() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        val labelStyle = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Spacer(Modifier.width(TimeColWidth))
        Text(
            stringResource(R.string.hourly_air),
            style = labelStyle,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(TempColWidth),
        )
        Text(
            stringResource(R.string.hourly_sea),
            style = labelStyle,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(TempColWidth),
        )
        Text(
            stringResource(R.string.hourly_wind),
            style = labelStyle,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(WindColWidth),
        )
    }
}

@Composable
private fun HourRow(hour: HourlyWeather, zone: ZoneId, useMetric: Boolean, use24h: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        Text(
            TideFormat.time(hour.timeMillis, use24h = use24h, zone = zone),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.width(TimeColWidth),
        )
        Text(
            TideFormat.temp(hour.airC, useMetric),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(TempColWidth),
        )
        Text(
            TideFormat.temp(hour.seaC, useMetric),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(TempColWidth),
        )
        Row(
            modifier = Modifier.width(WindColWidth),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindArrow(directionDeg = hour.windDirectionDeg, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(
                TideFormat.windSpeed(hour.windSpeedKmh, useMetric),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private const val HOUR_MS = 3_600_000L
