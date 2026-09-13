package com.github.stevermeister.vertrek.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.stevermeister.vertrek.data.CacheState
import com.github.stevermeister.vertrek.data.Direction
import com.github.stevermeister.vertrek.data.NoDataReason
import com.github.stevermeister.vertrek.data.TripDto
import com.github.stevermeister.vertrek.data.TripsRepository
import com.github.stevermeister.vertrek.data.ageMinutes
import com.github.stevermeister.vertrek.data.cacheStateOf
import com.github.stevermeister.vertrek.data.opposite
import com.github.stevermeister.vertrek.data.resolveDirection
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TripsUiState {
    data object Loading : TripsUiState

    data class Content(
        val direction: Direction,
        val isRefreshing: Boolean,
        val body: TripsBody,
    ) : TripsUiState
}

/** Mirrors CacheState, but with "fetched successfully, zero trips" broken out as its own case. */
sealed interface TripsBody {
    data class Fresh(val trips: List<TripDto>) : TripsBody
    data class Stale(val trips: List<TripDto>, val ageMinutes: Long) : TripsBody
    data class NoData(val reason: NoDataReason) : TripsBody
    data object Empty : TripsBody
}

private const val REFRESH_INDICATOR_MILLIS = 3_000L

/**
 * [onRefreshRequested] enqueues RefreshWorker and [onDirectionChanged] calls
 * TileService.getUpdater(...).requestUpdate() — both injected so this class
 * has no direct Android Context/WorkManager/TileService dependency and can
 * be unit-tested with a repository alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModel(
    private val repository: TripsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val onRefreshRequested: () -> Unit = {},
    private val onDirectionChanged: () -> Unit = {},
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<TripsUiState> =
        repository.observeDirectionOverride()
            .map { override -> resolveDirection(override, clock) }
            .distinctUntilChanged()
            .flatMapLatest { direction ->
                combine(
                    repository.observeCached(direction),
                    repository.observeLastFailureReason(direction),
                    isRefreshing,
                ) { cached, lastFailure, refreshing ->
                    val cacheState = cacheStateOf(cached, lastFailure, clock)
                    TripsUiState.Content(direction, refreshing, toBody(cacheState)) as TripsUiState
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TripsUiState.Loading)

    /** Enqueues a background refresh; does not itself touch the network. */
    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        onRefreshRequested()
        viewModelScope.launch {
            // No WorkInfo observation here — this is a simple, honest "something
            // is happening" indicator, not a precise completion signal. The
            // DataStore-backed content above updates on its own once the
            // worker actually writes a new cache value.
            delay(REFRESH_INDICATOR_MILLIS)
            isRefreshing.value = false
        }
    }

    /** Writes the same DataStore override the tile reads, then nudges the tile to redraw. */
    fun swapDirection() {
        viewModelScope.launch {
            val current = resolveDirection(repository.getDirectionOverride(), clock)
            repository.setDirectionOverride(current.opposite())
            onDirectionChanged()
        }
    }

    private fun toBody(cacheState: CacheState): TripsBody =
        when (cacheState) {
            is CacheState.Fresh ->
                if (cacheState.data.trips.isEmpty()) TripsBody.Empty else TripsBody.Fresh(cacheState.data.trips)
            is CacheState.Stale ->
                if (cacheState.data.trips.isEmpty()) {
                    TripsBody.Empty
                } else {
                    TripsBody.Stale(cacheState.data.trips, cacheState.data.ageMinutes(clock))
                }
            is CacheState.NoData -> TripsBody.NoData(cacheState.reason)
        }
}
