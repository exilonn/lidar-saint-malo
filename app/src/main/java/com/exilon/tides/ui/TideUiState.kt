package com.exilon.tides.ui

import com.exilon.tides.data.model.TideData

/** Unidirectional UI state. Cached data always wins: if Room has a forecast we show it, and
 *  surface refresh problems as a non-blocking banner rather than hiding the data. */
sealed interface TideUiState {
    data object Loading : TideUiState
    data object PermissionRequired : TideUiState

    /** No cached data and the fetch failed (first run, offline, etc.). */
    data class Error(val message: String) : TideUiState

    /** No tide station within range and nothing saved — prompt to search a coastal place. */
    data class NoStation(val distanceKm: Double?) : TideUiState

    /**
     * One or more saved stations are cached. [places] holds them in display order (current
     * location first), [selectedIndex] points at the active one — the swipeable pager and the
     * picker move this index.
     */
    data class Ready(
        val places: List<TideData>,
        val selectedIndex: Int,
        val isRefreshing: Boolean,
        val transientError: String?,
    ) : TideUiState {
        val selected: TideData? get() = places.getOrNull(selectedIndex)
    }
}
