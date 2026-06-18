package com.exilon.tides.ui.screen.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exilon.tides.R

/**
 * Location selector pill. The whole pill is a single tap target that opens the station picker.
 * The chevron sits at the far right; a location pin at the left.
 */
@Composable
fun LocationBar(
    placeName: String,
    onPickerClick: () -> Unit,
    modifier: Modifier = Modifier,
    frost: FrostState? = null,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    FrostedCard(modifier = modifier, state = frost, shape = pillShape) {
        Row(
            modifier = Modifier
                .clip(pillShape)
                .clickable(onClick = onPickerClick)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                placeName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.switch_location),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
