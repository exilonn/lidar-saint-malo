package com.exilon.tides.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.exilon.tides.TideApp
import com.exilon.tides.map.MapScreen
import com.exilon.tides.map.MapViewModel
import com.exilon.tides.ui.screen.SearchScreen
import com.exilon.tides.ui.screen.SettingsScreen
import com.exilon.tides.ui.screen.TideScreen
import com.exilon.tides.ui.theme.LocalTidePrefs
import com.exilon.tides.ui.theme.Palettes
import com.exilon.tides.ui.theme.TidePrefs
import com.exilon.tides.ui.theme.TideTheme
import com.exilon.tides.widget.TideWidget
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val serviceLocator = TideApp.from(this).serviceLocator
        val repository = serviceLocator.repository
        val estranRepository = serviceLocator.estranRepository
        val lidarRepository = serviceLocator.lidarRepository

        setContent {
            val viewModel: TideViewModel = viewModel(
                factory = TideViewModel.Factory(
                    repository = repository,
                    onRefreshed = { TideWidget().updateAll(applicationContext) },
                ),
            )
            val mapViewModel: MapViewModel = viewModel(
                factory = MapViewModel.Factory(repository, estranRepository, lidarRepository),
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val searchState by viewModel.search.collectAsStateWithLifecycle()
            val mapState by mapViewModel.mapState.collectAsStateWithLifecycle()
            val estranState by mapViewModel.estran.collectAsStateWithLifecycle()
            val lidarState by mapViewModel.lidar.collectAsStateWithLifecycle()
            val themeId by viewModel.themeId.collectAsStateWithLifecycle()
            val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
            val localizedRegions by viewModel.localizedRegions.collectAsStateWithLifecycle()
            val context = LocalContext.current

            // Units and clock format: stored in SharedPreferences, held as saveable state so the
            // values survive recomposition and configuration changes without a DB migration.
            var useMetric by rememberSaveable { mutableStateOf(UserPrefs.useMetric(applicationContext)) }
            var use24h by rememberSaveable { mutableStateOf(UserPrefs.use24h(applicationContext)) }

            var showSearch by rememberSaveable { mutableStateOf(false) }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showMap by rememberSaveable { mutableStateOf(false) }

            // Persist the language selection to SharedPreferences so attachBaseContext can read it
            // synchronously on the next activity recreation.
            LaunchedEffect(languageTag) {
                LocaleManager.store(context, languageTag)
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
            }

            // Re-check once on resume while still blocked on permission so a round-trip to
            // Settings doesn't need a manual refresh.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                if (viewModel.uiState.value is TideUiState.PermissionRequired) viewModel.refresh()
            }

            // Going to background is exactly when the widget becomes the visible surface for the
            // tide — nudge it to re-render now rather than waiting for the next tick alarm.
            val widgetUpdateScope = rememberCoroutineScope()
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                widgetUpdateScope.launch { TideWidget().updateAll(applicationContext) }
            }

            BackHandler(enabled = showSearch) {
                showSearch = false
                viewModel.clearSearch()
            }
            BackHandler(enabled = showSettings) { showSettings = false }
            BackHandler(enabled = showMap) { showMap = false }

            val palette = Palettes.byId(themeId)
            TideTheme(palette = palette) {
                CompositionLocalProvider(LocalTidePrefs provides TidePrefs(useMetric, use24h)) {
                    AnimatedContent(
                        targetState = when {
                            showSettings -> "settings"
                            showSearch -> "search"
                            showMap -> "map"
                            else -> "main"
                        },
                        transitionSpec = {
                            if (targetState != "main") {
                                (slideInVertically { it / 8 } + fadeIn()) togetherWith fadeOut()
                            } else {
                                fadeIn() togetherWith (slideOutVertically { it / 8 } + fadeOut())
                            }
                        },
                        label = "rootScreen",
                    ) { screen ->
                        when (screen) {
                            "settings" -> SettingsScreen(
                                currentTheme = themeId,
                                currentLanguageTag = languageTag,
                                useMetric = useMetric,
                                use24h = use24h,
                                onSelectTheme = viewModel::onSelectTheme,
                                onSelectLanguage = { tag ->
                                    viewModel.onSelectLanguage(tag)
                                    // Store immediately so attachBaseContext on the recreated
                                    // activity finds the new locale before Room emits.
                                    LocaleManager.store(applicationContext, tag)
                                    recreate()
                                },
                                onSetMetric = { v ->
                                    UserPrefs.setMetric(applicationContext, v)
                                    useMetric = v
                                },
                                onSet24h = { v ->
                                    UserPrefs.set24h(applicationContext, v)
                                    use24h = v
                                },
                                onClearCache = viewModel::onClearCache,
                                onBack = { showSettings = false },
                            )
                            "search" -> SearchScreen(
                                state = searchState,
                                onQueryChange = viewModel::onSearchQueryChange,
                                onPick = {
                                    viewModel.onAddStation(it)
                                    showSearch = false
                                },
                                onBack = {
                                    showSearch = false
                                    viewModel.clearSearch()
                                },
                                localizeRegion = viewModel::localizedRegion,
                            )
                            "map" -> MapScreen(
                                state = mapState,
                                estran = estranState,
                                onLoadEstran = mapViewModel::loadEstran,
                                lidar = lidarState,
                                onLoadLidar = mapViewModel::loadLidar,
                                onBack = { showMap = false },
                            )
                            else -> TideScreen(
                                state = state,
                                onRefresh = { viewModel.refresh(force = true) },
                                onRequestPermission = {
                                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                },
                                onOpenLocationSettings = { context.openAppSettings() },
                                onOpenSettings = { showSettings = true },
                                onOpenSearch = {
                                    viewModel.clearSearch()
                                    showSearch = true
                                },
                                onOpenMap = { showMap = true },
                                onSelectStation = viewModel::onSelectStation,
                                onRemoveStation = viewModel::onRemoveStation,
                                localizedRegions = localizedRegions,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
