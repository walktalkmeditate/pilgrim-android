// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Three-state load for the summary screen: the VM's [summary] flow
 * starts at [Loading], resolves to [Loaded] when the walk row + samples
 * + events land, or [NotFound] if the walk row is missing (deleted or
 * never existed for this id). Replaces the previous nullable pattern
 * where "loading" and "gone" were indistinguishable.
 */
sealed class WalkSummaryUiState {
    data object Loading : WalkSummaryUiState()
    data class Loaded(val summary: WalkSummary) : WalkSummaryUiState()
    data object NotFound : WalkSummaryUiState()
}

data class WalkSummary(
    val walk: Walk,
    val totalElapsedMillis: Long,
    val activeWalkingMillis: Long,
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val distanceMeters: Double,
    val paceSecondsPerKm: Double?,
    val waypointCount: Int,
)

@HiltViewModel
class WalkSummaryViewModel @Inject constructor(
    private val repository: WalkRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walkId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_WALK_ID)) {
        "walkId argument missing from nav savedStateHandle"
    }

    val state: StateFlow<WalkSummaryUiState> = flow {
        emit(buildState())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WalkSummaryUiState.Loading,
    )

    private suspend fun buildState(): WalkSummaryUiState {
        val walk = repository.getWalk(walkId) ?: return WalkSummaryUiState.NotFound
        val samples = repository.locationSamplesFor(walkId)
        val events = repository.eventsFor(walkId)
        val waypoints = repository.waypointsFor(walkId)

        val distance = walkDistanceMeters(
            samples.map {
                LocationPoint(
                    timestamp = it.timestamp,
                    latitude = it.latitude,
                    longitude = it.longitude,
                )
            },
        )
        // Close dangling PAUSED/MEDITATION_START intervals at the walk's
        // end timestamp — the reducer folds them into the in-memory
        // accumulator on Finish but does not persist synthetic close
        // events, so the replay would otherwise undercount pause and
        // meditation time (and overcount active walking).
        val totals = replayWalkEventTotals(events = events, closeAt = walk.endTimestamp)
        val totalElapsed = (walk.endTimestamp ?: walk.startTimestamp) - walk.startTimestamp
        val activeWalking = (totalElapsed - totals.totalPausedMillis - totals.totalMeditatedMillis)
            .coerceAtLeast(0)

        val distanceKm = distance / 1_000.0
        val pace = if (distanceKm >= 0.01 && activeWalking >= 1_000L) {
            (activeWalking / 1_000.0) / distanceKm
        } else {
            null
        }

        return WalkSummaryUiState.Loaded(
            WalkSummary(
                walk = walk,
                totalElapsedMillis = totalElapsed,
                activeWalkingMillis = activeWalking,
                totalPausedMillis = totals.totalPausedMillis,
                totalMeditatedMillis = totals.totalMeditatedMillis,
                distanceMeters = distance,
                paceSecondsPerKm = pace,
                waypointCount = waypoints.size,
            ),
        )
    }

    companion object {
        const val ARG_WALK_ID = "walkId"
    }
}
