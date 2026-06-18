package com.exilon.tides.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.data.StationResult
import com.exilon.tides.ui.PlaceLocalization
import com.exilon.tides.ui.SearchUiState
import com.exilon.tides.ui.screen.components.FrostedCard
import com.exilon.tides.ui.screen.components.SkyScene
import com.exilon.tides.ui.screen.components.frostSource
import com.exilon.tides.ui.screen.components.rememberFrostState

/**
 * Station search, styled to match the main screen: the same time-of-day sky gradient, frosted-glass
 * surfaces and Space Grotesk / Manrope type. Backed by TideCheck's /stations/search; tap a result
 * to save it.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onPick: (StationResult) -> Unit,
    onBack: () -> Unit,
    localizeRegion: suspend (Double, Double) -> String? = { _, _ -> null },
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val now = remember { System.currentTimeMillis() }
    val frost = rememberFrostState()
    val onScene = Color.White

    Box(modifier.fillMaxSize()) {
        SkyScene(
            nowMillis = now,
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
                Text(stringResource(R.string.add_location), style = MaterialTheme.typography.titleLarge, color = onScene)
            }

            Spacer(Modifier.height(8.dp))

            FrostedCard(
                modifier = Modifier.fillMaxWidth(),
                state = frost,
                shape = RoundedCornerShape(28.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it; onQueryChange(it) },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))

            when (state) {
                SearchUiState.Idle -> Hint(stringResource(R.string.search_idle), onScene)
                SearchUiState.Loading -> Centered { CircularProgressIndicator(color = onScene) }
                SearchUiState.RateLimited -> Hint(stringResource(R.string.search_rate_limited), onScene)
                is SearchUiState.Error -> Hint(stringResource(R.string.search_error, state.message), onScene)
                is SearchUiState.Results ->
                    if (state.stations.isEmpty()) {
                        Hint(stringResource(R.string.search_empty, state.query), onScene)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.stations, key = { it.id }) { station ->
                                ResultItem(station, frost, localizeRegion) { onPick(station) }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun ResultItem(
    station: StationResult,
    frost: com.exilon.tides.ui.screen.components.FrostState?,
    localizeRegion: suspend (Double, Double) -> String?,
    onClick: () -> Unit,
) {
    // Search results carry the raw (usually English) region from TideCheck; re-resolve it via the
    // on-device geocoder in the app's current language so it matches the saved-station subtitle.
    val region by produceState(initialValue = station.region, station.lat, station.lng) {
        if (station.lat != 0.0 || station.lng != 0.0) {
            value = localizeRegion(station.lat, station.lng) ?: station.region
        }
    }
    FrostedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        state = frost,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                PlaceLocalization.subtitle(region, station.country)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Centered {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
