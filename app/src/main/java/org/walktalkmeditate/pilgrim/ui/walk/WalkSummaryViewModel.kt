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
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.haversineMeters

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

    val summary: StateFlow<WalkSummary?> = flow {
        emit(buildSummary())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    private suspend fun buildSummary(): WalkSummary? {
        val walk = repository.getWalk(walkId) ?: return null
        val samples = repository.locationSamplesFor(walkId)
        val events = repository.eventsFor(walkId)
        val waypoints = repository.waypointsFor(walkId)

        val distance = walkDistanceFromSamples(samples)
        val (pausedMs, meditatedMs) = pauseAndMeditateTotalsFromEvents(events)
        val totalElapsed = (walk.endTimestamp ?: walk.startTimestamp) - walk.startTimestamp
        val activeWalking = (totalElapsed - pausedMs - meditatedMs).coerceAtLeast(0)

        val distanceKm = distance / 1_000.0
        val pace = if (distanceKm >= 0.01 && activeWalking >= 1_000L) {
            (activeWalking / 1_000.0) / distanceKm
        } else {
            null
        }

        return WalkSummary(
            walk = walk,
            totalElapsedMillis = totalElapsed,
            activeWalkingMillis = activeWalking,
            totalPausedMillis = pausedMs,
            totalMeditatedMillis = meditatedMs,
            distanceMeters = distance,
            paceSecondsPerKm = pace,
            waypointCount = waypoints.size,
        )
    }

    private fun walkDistanceFromSamples(
        samples: List<org.walktalkmeditate.pilgrim.data.entity.RouteDataSample>,
    ): Double {
        var distance = 0.0
        var last: LocationPoint? = null
        for (s in samples) {
            val point = LocationPoint(
                timestamp = s.timestamp,
                latitude = s.latitude,
                longitude = s.longitude,
            )
            if (last != null) distance += haversineMeters(last, point)
            last = point
        }
        return distance
    }

    private fun pauseAndMeditateTotalsFromEvents(events: List<WalkEvent>): Pair<Long, Long> {
        var paused = 0L
        var meditated = 0L
        var pendingPauseAt: Long? = null
        var pendingMeditationAt: Long? = null
        for (e in events) {
            when (e.eventType) {
                WalkEventType.PAUSED -> pendingPauseAt = e.timestamp
                WalkEventType.RESUMED -> pendingPauseAt?.let {
                    paused += (e.timestamp - it).coerceAtLeast(0)
                    pendingPauseAt = null
                }
                WalkEventType.MEDITATION_START -> pendingMeditationAt = e.timestamp
                WalkEventType.MEDITATION_END -> pendingMeditationAt?.let {
                    meditated += (e.timestamp - it).coerceAtLeast(0)
                    pendingMeditationAt = null
                }
                WalkEventType.WAYPOINT_MARKED -> Unit
            }
        }
        return paused to meditated
    }

    companion object {
        const val ARG_WALK_ID = "walkId"
    }
}
