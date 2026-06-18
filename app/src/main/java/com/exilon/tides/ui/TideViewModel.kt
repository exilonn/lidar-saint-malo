package com.exilon.tides.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.exilon.tides.data.RateLimitExceededException
import com.exilon.tides.data.RefreshResult
import com.exilon.tides.data.StationResult
import com.exilon.tides.data.TideRepository
import com.exilon.tides.ui.theme.ThemeId
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the UI state. Reads the cached forecasts for every saved station from the repository
 * (Room-backed [StateFlow]) and layers refresh status on top. [onRefreshed] lets the host nudge
 * the widget after a successful refresh without this class depending on Glance.
 */
class TideViewModel(
    private val repository: TideRepository,
    private val onRefreshed: suspend () -> Unit = {},
) : ViewModel() {

    private data class RefreshStatus(
        val isRefreshing: Boolean = false,
        val permissionRequired: Boolean = false,
        val error: String? = null,
        val noStationNearby: Boolean = false,
        val noStationDistanceKm: Double? = null,
    )

    private val refreshStatus = MutableStateFlow(RefreshStatus())

    val uiState: StateFlow<TideUiState> =
        combine(
            repository.allTideData,
            repository.selectedStationId,
            refreshStatus,
        ) { places, selectedId, status ->
            when {
                places.isNotEmpty() -> {
                    val index = places.indexOfFirst { it.stationId == selectedId }
                        .takeIf { it >= 0 } ?: 0
                    TideUiState.Ready(
                        places = places,
                        selectedIndex = index,
                        isRefreshing = status.isRefreshing,
                        transientError = status.error,
                    )
                }
                status.permissionRequired -> TideUiState.PermissionRequired
                status.isRefreshing -> TideUiState.Loading
                status.noStationNearby -> TideUiState.NoStation(status.noStationDistanceKm)
                status.error != null -> TideUiState.Error(status.error)
                else -> TideUiState.Loading
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TideUiState.Loading)

    private val _search = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = _search.asStateFlow()
    private var searchJob: Job? = null

    /** Selected theme palette (drives app + widget). */
    val themeId: StateFlow<ThemeId> = repository.themeId
        .map { ThemeId.from(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeId.OCEAN)

    /** Manual language override (BCP-47 tag); null = follow the system locale. */
    val languageTag: StateFlow<String?> = repository.languageTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Live, on-device-geocoded region name per station id, in the app's *current* language —
     * recomputed whenever the saved-station list or the language changes, so a saved station's
     * subtitle (e.g. "Bretagne, France") never gets stuck showing the raw English string TideCheck
     * returned at add-time. Keyed by stationId; absent entries fall back to the stored raw region.
     */
    private val _localizedRegions = MutableStateFlow<Map<String, String?>>(emptyMap())
    val localizedRegions: StateFlow<Map<String, String?>> = _localizedRegions.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.allTideData, languageTag) { places, tag -> places to tag }
                .collectLatest { (places, tag) ->
                    _localizedRegions.value = coroutineScope {
                        places
                            .map { place -> async { place.stationId to repository.localizedRegion(place.lat, place.lng, tag) } }
                            .awaitAll()
                            .toMap()
                    }
                }
        }
    }

    /** Used by the search screen to live-localize an unsaved result's region as it's geocoded. */
    suspend fun localizedRegion(lat: Double, lng: Double): String? =
        repository.localizedRegion(lat, lng, languageTag.value)

    fun onSelectTheme(id: ThemeId) {
        viewModelScope.launch {
            repository.setTheme(id.key)
            onRefreshed() // re-skin the widget to match
        }
    }

    fun onSelectLanguage(tag: String?) {
        viewModelScope.launch { repository.setLanguage(tag) }
    }

    init {
        refresh(force = false)
    }

    /**
     * [force] = false (app open, regained permission, lifecycle resume): cache-only unless the
     * selected station's data is genuinely stale — never hits TideCheck just because the app
     * reopened. [force] = true: explicit pull-to-refresh.
     */
    fun refresh(force: Boolean = false) {
        if (refreshStatus.value.isRefreshing) return
        viewModelScope.launch {
            refreshStatus.update { it.copy(isRefreshing = true, error = null) }
            applyResult(repository.refresh(force))
        }
    }

    /** Switch the active station. Usually instant (served from cache); fetches only if stale. */
    fun onSelectStation(stationId: String) {
        viewModelScope.launch { applyResult(repository.selectStation(stationId)) }
    }

    /** Save a searched station as a favourite, select it, and fetch its forecast. */
    fun onAddStation(result: StationResult) {
        viewModelScope.launch {
            refreshStatus.update { it.copy(isRefreshing = true, error = null) }
            applyResult(repository.addFavourite(result))
            clearSearch()
        }
    }

    fun onRemoveStation(stationId: String) {
        viewModelScope.launch {
            repository.removeStation(stationId)
            onRefreshed()
        }
    }

    /** Settings > Clear cache: wipes cached tide/weather data, then immediately re-fetches. */
    fun onClearCache() {
        viewModelScope.launch {
            repository.clearCache()
            refresh(force = true)
        }
    }

    /** Debounced station search backing the search screen. */
    fun onSearchQueryChange(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            _search.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            _search.value = SearchUiState.Loading
            delay(SEARCH_DEBOUNCE_MS)
            repository.searchStations(q)
                .onSuccess { _search.value = SearchUiState.Results(q, it) }
                .onFailure { e ->
                    _search.value = if (e is RateLimitExceededException) {
                        SearchUiState.RateLimited
                    } else {
                        SearchUiState.Error(e.message ?: "Search failed")
                    }
                }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchUiState.Idle
    }

    /** Called after the user grants the location permission. */
    fun onPermissionGranted() {
        refreshStatus.update { it.copy(permissionRequired = false) }
        refresh(force = false)
    }

    fun onPermissionDenied() {
        refreshStatus.update { it.copy(isRefreshing = false, permissionRequired = true) }
    }

    private suspend fun applyResult(result: RefreshResult) {
        refreshStatus.update {
            when (result) {
                RefreshResult.Success ->
                    it.copy(
                        isRefreshing = false,
                        permissionRequired = false,
                        error = null,
                        noStationNearby = false,
                        noStationDistanceKm = null,
                    )
                RefreshResult.PermissionDenied ->
                    it.copy(isRefreshing = false, permissionRequired = true)
                RefreshResult.LocationUnavailable ->
                    it.copy(isRefreshing = false, error = "Location unavailable — is location turned on?")
                RefreshResult.RateLimited ->
                    it.copy(isRefreshing = false, error = "Rate limit reached — showing saved tides")
                is RefreshResult.NoStationNearby ->
                    it.copy(isRefreshing = false, noStationNearby = true, noStationDistanceKm = result.distanceKm)
                is RefreshResult.Error ->
                    it.copy(isRefreshing = false, error = result.message)
            }
        }
        if (result is RefreshResult.Success) onRefreshed()
    }

    class Factory(
        private val repository: TideRepository,
        private val onRefreshed: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TideViewModel(repository, onRefreshed) as T
    }

    private companion object {
        const val MIN_QUERY = 2
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** State of the station search screen. */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val query: String, val stations: List<StationResult>) : SearchUiState
    data object RateLimited : SearchUiState
    data class Error(val message: String) : SearchUiState
}
