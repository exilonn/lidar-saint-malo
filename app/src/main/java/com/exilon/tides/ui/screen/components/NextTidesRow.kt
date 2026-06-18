package com.exilon.tides.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.model.TideExtreme
import com.exilon.tides.ui.TideFormat
import com.exilon.tides.ui.theme.LocalTidePrefs
import com.exilon.tides.ui.theme.SpaceGrotesk
import java.time.ZoneId

/** Two frosted cards: the next high and the next low, with time, height and countdown. */
@Composable
fun NextTidesRow(
    nextHigh: TideExtreme?,
    nextLow: TideExtreme?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
    frost: FrostState? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NextTideCard(
            title = R.string.next_high,
            icon = Icons.Filled.ArrowUpward,
            extreme = nextHigh,
            nowMillis = nowMillis,
            zone = zone,
            frost = frost,
            modifier = Modifier.weight(1f),
        )
        NextTideCard(
            title = R.string.next_low,
            icon = Icons.Filled.ArrowDownward,
            extreme = nextLow,
            nowMillis = nowMillis,
            zone = zone,
            frost = frost,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NextTideCard(
    title: Int,
    icon: ImageVector,
    extreme: TideExtreme?,
    nowMillis: Long,
    zone: ZoneId,
    frost: FrostState?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = LocalTidePrefs.current
    FrostedCard(modifier = modifier, state = frost) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.size(8.dp))
            if (extreme == null) {
                Text("—", style = MaterialTheme.typography.headlineSmall)
            } else {
                Text(
                    TideFormat.time(extreme.timeMillis, use24h = prefs.use24h, zone = zone),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = TideFormat.height(extreme.heightMeters, useMetric = prefs.useMetric) + " · " +
                        stringResource(R.string.in_time, TideFormat.countdown(context, nowMillis, extreme.timeMillis)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceGrotesk),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
