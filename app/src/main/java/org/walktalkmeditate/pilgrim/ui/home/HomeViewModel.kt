// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Backing VM for the Home walk list. Observes all walks, filters to
 * finished ones (`endTimestamp != null`), maps each to a
 * [HomeWalkRow] with the text fields precomputed. Row recomposition
 * is then a no-op pass-through of already-formatted strings.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) {
                HomeUiState.Empty
            } else {
                HomeUiState.Loaded(finished.map { mapToRow(it) })
            }
        }
        .stateIn(
            scope = viewModelScope,
            // WhileSubscribed matches Stage 2-E's pattern: unit tests
            // that don't subscribe don't leave a never-completing
            // collector running in viewModelScope (runTest would wait
            // on it forever otherwise).
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = HomeUiState.Loading,
        )

    private suspend fun mapToRow(walk: Walk): HomeWalkRow {
        val endMs = walk.endTimestamp ?: clock.now()
        val elapsed = endMs - walk.startTimestamp
        val samples = repository.locationSamplesFor(walk.id).map {
            LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
        }
        val distance = walkDistanceMeters(samples)
        val recordingCount = repository.countVoiceRecordingsFor(walk.id)
        return HomeWalkRow(
            walkId = walk.id,
            relativeDate = HomeFormat.relativeDate(
                context = context,
                timestampMs = walk.startTimestamp,
                nowMs = clock.now(),
            ),
            durationText = WalkFormat.duration(elapsed),
            distanceText = WalkFormat.distance(distance),
            recordingCountText = HomeFormat.recordingCountLabel(context, recordingCount),
            intention = walk.intention?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
