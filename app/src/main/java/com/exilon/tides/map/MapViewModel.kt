package com.exilon.tides.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.exilon.tides.data.EstranRepository
import com.exilon.tides.data.EstranResult
import com.exilon.tides.data.LidarRepository
import com.exilon.tides.data.LidarResult
import com.exilon.tides.data.TideRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** LiDAR zone asset load state. Ready = GeoJSON in hand + chart-datum ref for IGN69 mapping. */
sealed interface LidarUiState {
    data object Idle : LidarUiState
    data object Loading : LidarUiState
    data class Ready(val geoJson: String, val zhRef: Double) : LidarUiState
    data object Unavailable : LidarUiState
}

/** The intertidal overlay's load state, separate from the (instant, Room-backed) station state. */
sealed interface EstranUiState {
    data object Idle : EstranUiState
    data object Loading : EstranUiState
    data class Ready(val geoJson: String, val hasIntertidal: Boolean) : EstranUiState
    data object Unavailable : EstranUiState
}

/**
 * Owns the tide map's state. The station (coordinates + cached extremes) comes instantly from the
 * Room-backed repository flows — no tide network. The estran overlay is fetched lazily via
 * [EstranRepository] (Overpass, cached) when the screen asks for it. MapLibre types never reach here.
 */
class MapViewModel(
    repository: TideRepository,
    private val estranRepository: EstranRepository,
    private val lidarRepository: LidarRepository,
) : ViewModel() {

    val mapState: StateFlow<MapUiState> =
        combine(repository.allTideData, repository.selectedStationId) { places, selectedId ->
            val place = places.firstOrNull { it.stationId == selectedId } ?: places.firstOrNull()
            if (place == null || (place.lat == 0.0 && place.lng == 0.0)) {
                MapUiState.Unavailable
            } else {
                MapUiState.Ready(
                    stationId = place.stationId,
                    placeName = place.placeName,
                    lat = place.lat,
                    lng = place.lng,
                    extremes = place.extremes,
                    zone = place.stationZone,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState.Loading)

    private val _estran = MutableStateFlow<EstranUiState>(EstranUiState.Idle)
    val estran: StateFlow<EstranUiState> = _estran.asStateFlow()
    private var estranJob: Job? = null
    private var loadedEstranStationId: String? = null

    /** Fetch (or serve cached) intertidal data for a station. Re-runs only when the station changes. */
    fun loadEstran(stationId: String, lat: Double, lng: Double) {
        if (stationId == loadedEstranStationId && _estran.value !is EstranUiState.Idle) return
        loadedEstranStationId = stationId
        estranJob?.cancel()
        estranJob = viewModelScope.launch {
            _estran.value = EstranUiState.Loading
            _estran.value = when (val result = estranRepository.estran(stationId, lat, lng)) {
                is EstranResult.Data -> EstranUiState.Ready(result.geoJson, result.hasIntertidal)
                EstranResult.Unavailable -> EstranUiState.Unavailable
            }
        }
    }

    private val _lidar = MutableStateFlow<LidarUiState>(LidarUiState.Idle)
    val lidar: StateFlow<LidarUiState> = _lidar.asStateFlow()
    private var lidarJob: Job? = null
    private var loadedLidarStationId: String? = null

    /** Fetch (or serve cached) LiDAR zone asset for a station. No-op for unmapped stations. */
    fun loadLidar(stationId: String) {
        if (stationId == loadedLidarStationId && _lidar.value !is LidarUiState.Idle) return
        loadedLidarStationId = stationId
        lidarJob?.cancel()
        lidarJob = viewModelScope.launch {
            _lidar.value = LidarUiState.Loading
            _lidar.value = when (val result = lidarRepository.ensureZone(stationId)) {
                is LidarResult.Ready -> LidarUiState.Ready(result.geoJson, result.zhRef)
                LidarResult.Unavailable -> LidarUiState.Unavailable
            }
        }
    }

    class Factory(
        private val repository: TideRepository,
        private val estranRepository: EstranRepository,
        private val lidarRepository: LidarRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MapViewModel(repository, estranRepository, lidarRepository) as T
    }
}
