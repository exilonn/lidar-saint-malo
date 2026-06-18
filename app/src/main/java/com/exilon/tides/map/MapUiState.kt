package com.exilon.tides.map

import com.exilon.tides.data.model.TideExtreme
import java.time.ZoneId

/** State for the interactive tide map. The map reads the active station straight from Room (via
 *  [MapViewModel]) — it never fetches tide data itself. */
sealed interface MapUiState {
    data object Loading : MapUiState

    /** No station selected, or the selected one has no usable coordinates yet. */
    data object Unavailable : MapUiState

    /** The active station: its coordinates, cached extremes (for the slider) and timezone. */
    data class Ready(
        val stationId: String,
        val placeName: String,
        val lat: Double,
        val lng: Double,
        val extremes: List<TideExtreme>,
        val zone: ZoneId,
    ) : MapUiState
}
