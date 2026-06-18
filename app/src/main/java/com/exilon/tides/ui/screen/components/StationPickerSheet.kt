package com.exilon.tides.ui.screen.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.model.TideData
import com.exilon.tides.ui.PlaceLocalization

/**
 * Glassy in-app Locations panel (not a stock bottom sheet, so it gets real backdrop blur via
 * [FrostedCard] + [frost]). Switch between saved stations, remove favourites, or add a new one.
 */
@Composable
fun StationPickerPanel(
    places: List<TideData>,
    selectedId: String?,
    frost: FrostState?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    localizedRegions: Map<String, String?> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    FrostedCard(
        modifier = modifier.fillMaxWidth(),
        state = frost,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Text(
                stringResource(R.string.locations),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )
            places.forEach { place ->
                PickerItem(
                    place = place,
                    isSelected = place.stationId == selectedId,
                    localizedRegion = localizedRegions[place.stationId],
                    onSelect = { onSelect(place.stationId) },
                    onRemove = { onRemove(place.stationId) },
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.add_a_location),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.places_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun PickerItem(
    place: TideData,
    isSelected: Boolean,
    localizedRegion: String?,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isSelected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (!place.isFavourite) Icons.Filled.MyLocation else Icons.Filled.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place.placeName,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected) scheme.primary else scheme.onSurface,
                maxLines = 1,
            )
            val secondary = if (!place.isFavourite) {
                stringResource(R.string.current_location)
            } else {
                PlaceLocalization.subtitle(localizedRegion ?: place.region, place.country) ?: place.stationName
            }
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (place.isFavourite) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.remove),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
