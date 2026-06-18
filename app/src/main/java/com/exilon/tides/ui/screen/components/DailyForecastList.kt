package com.exilon.tides.ui.screen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.model.DayTides
import com.exilon.tides.data.model.DayWeather
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.data.model.TideType
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.theme.LocalTidePalette
import com.exilon.tides.ui.theme.LocalTidePrefs
import com.exilon.tides.ui.theme.SpaceGrotesk
import java.time.LocalDate
import java.time.ZoneId

/** The "coming days" section: per day, air/sea temps (left) and chronological tides (right). */
@Composable
fun DailyForecastList(
    days: List<DayTides>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    onDayClick: (DayTides) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.coming_days),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.size(8.dp))
        days.forEachIndexed { index, day ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DayRow(day, nowMillis = nowMillis, zone = zone, onClick = { onDayClick(day) })
        }
        Text(
            text = stringResource(R.string.weather_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
        )
    }
}

@Composable
private fun DayRow(day: DayTides, nowMillis: Long, zone: ZoneId, onClick: () -> Unit) {
    val sunrise = TideFormat.minutesOfDay(day.condition?.sunrise, zone = zone)
    val sunset = TideFormat.minutesOfDay(day.condition?.sunset, zone = zone)
    val isToday = day.date == LocalDate.now(zone).toString()
    // All events in chronological order. Prominence (daylight bold, night dimmed) is applied
    // per-event. For today only, already-passed tides are dropped — only upcoming ones are useful;
    // other days always show every tide.
    val chronological = day.extremes
        .filter { !isToday || it.timeMillis >= nowMillis }
        .sortedBy { it.timeMillis }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Left column takes the remaining width; conditions stack vertically so they can never
        // wrap into / collide with the tide column on the right.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                TideFormat.dayLabel(LocalContext.current, day.date, zone = zone),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Spacer(Modifier.size(4.dp))
            DayMeta(day.weather)
        }
        Column(horizontalAlignment = Alignment.End) {
            chronological.forEach { extreme ->
                val prominent = isDaytime(extreme.timeMillis, sunrise, sunset, zone)
                TideExtremeLine(extreme, prominent = prominent, zone = zone)
            }
        }
    }
}

/** One high/low line. Daytime entries are larger Space Grotesk at full opacity; night dimmed/small. */
@Composable
private fun TideExtremeLine(extreme: TideExtreme, prominent: Boolean, zone: ZoneId) {
    val isHigh = extreme.type == TideType.HIGH
    val palette = LocalTidePalette.current
    val prefs = LocalTidePrefs.current
    val accent = if (isHigh) palette.rising else palette.falling
    val alpha = if (prominent) 1f else 0.62f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isHigh) TideArrowUp else TideArrowDown,
                contentDescription = stringResource(if (isHigh) R.string.high else R.string.low),
                modifier = Modifier.size(if (prominent) 16.dp else 13.dp),
                tint = accent.copy(alpha = alpha),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = TideFormat.time(extreme.timeMillis, use24h = prefs.use24h, zone = zone),
            style = if (prominent) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodySmall.copy(fontFamily = SpaceGrotesk),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
            modifier = Modifier.width(78.dp),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = TideFormat.height(extreme.heightMeters, useMetric = prefs.useMetric),
            style = if (prominent) MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceGrotesk)
                    else MaterialTheme.typography.labelSmall.copy(fontFamily = SpaceGrotesk),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 1,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** Air (high/low) + sea temperature only — no moon/percentage. */
@Composable
private fun DayMeta(weather: DayWeather?) {
    if (weather == null) return
    val prefs = LocalTidePrefs.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TempRow(Icons.Filled.Thermostat, airText(weather, prefs.useMetric), color)
        TempRow(Icons.Filled.Waves, TideFormat.temp(weather.seaC, useMetric = prefs.useMetric), color)
        if (weather.windSpeedMaxKmh != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WindArrow(directionDeg = weather.windDirectionDominantDeg, size = 14.dp, tint = color)
                Spacer(Modifier.width(5.dp))
                Text(
                    TideFormat.windSpeed(weather.windSpeedMaxKmh, prefs.useMetric),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TempRow(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

private fun airText(w: DayWeather, useMetric: Boolean): String {
    val max = w.airMaxC
    val min = w.airMinC
    return when {
        max != null && min != null -> "${TideFormat.temp(max, useMetric)} / ${TideFormat.temp(min, useMetric)}"
        max != null -> TideFormat.temp(max, useMetric)
        else -> "—"
    }
}

private fun isDaytime(millis: Long, sunriseMin: Int?, sunsetMin: Int?, zone: ZoneId): Boolean {
    val m = TideFormat.minutesOfDay(millis, zone)
    val sr = sunriseMin ?: (6 * 60)
    val ss = sunsetMin ?: (20 * 60)
    return m in sr until ss
}
