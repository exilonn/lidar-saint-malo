package com.exilon.tides.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.exilon.tides.R
import com.exilon.tides.ui.screen.components.FrostedCard
import com.exilon.tides.ui.screen.components.SkyScene
import com.exilon.tides.ui.screen.components.frostSource
import com.exilon.tides.ui.screen.components.rememberFrostState
import com.exilon.tides.ui.theme.LocalTidePalette
import com.exilon.tides.ui.theme.Palettes
import com.exilon.tides.ui.theme.ThemeId
import com.exilon.tides.ui.theme.TidePalette

/** Settings: pick a curated theme, language, units and clock format. */
@Composable
fun SettingsScreen(
    currentTheme: ThemeId,
    currentLanguageTag: String?,
    useMetric: Boolean,
    use24h: Boolean,
    onSelectTheme: (ThemeId) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onSetMetric: (Boolean) -> Unit,
    onSet24h: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { System.currentTimeMillis() }
    val frost = rememberFrostState()
    val onScene = LocalTidePalette.current.onScene
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        SkyScene(nowMillis = now, sunriseMillis = null, sunsetMillis = null, modifier = Modifier.frostSource(frost))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, color = onScene)
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.theme), onScene)
            FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                Column {
                    Palettes.all.forEach { palette ->
                        ThemeRow(
                            palette = palette,
                            selected = palette.id == currentTheme,
                            onClick = { onSelectTheme(palette.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.units), onScene)
            FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                Column {
                    OptionRow(stringResource(R.string.units_metric), useMetric) { onSetMetric(true) }
                    OptionRow(stringResource(R.string.units_imperial), !useMetric) { onSetMetric(false) }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.clock_format), onScene)
            FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                Column {
                    OptionRow(stringResource(R.string.clock_24h), use24h) { onSet24h(true) }
                    OptionRow(stringResource(R.string.clock_12h), !use24h) { onSet24h(false) }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.language), onScene)
            FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                Column {
                    OptionRow(stringResource(R.string.language_system), currentLanguageTag == null) {
                        onSelectLanguage(null)
                    }
                    OptionRow(stringResource(R.string.language_english), currentLanguageTag == "en") {
                        onSelectLanguage("en")
                    }
                    OptionRow(stringResource(R.string.language_french), currentLanguageTag == "fr") {
                        onSelectLanguage("fr")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.storage), onScene)
            FrostedCard(modifier = Modifier.fillMaxWidth(), state = frost) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { showClearCacheConfirm = true })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clear_cache), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.clear_cache_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showClearCacheConfirm) {
            AlertDialog(
                onDismissRequest = { showClearCacheConfirm = false },
                title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
                text = { Text(stringResource(R.string.clear_cache_confirm_body)) },
                confirmButton = {
                    TextButton(onClick = { showClearCacheConfirm = false; onClearCache() }) {
                        Text(stringResource(R.string.clear_cache))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun ThemeRow(palette: TidePalette, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch(palette.skyBottom)
            Swatch(palette.rising)
            Swatch(palette.falling)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            palette.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(color))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
