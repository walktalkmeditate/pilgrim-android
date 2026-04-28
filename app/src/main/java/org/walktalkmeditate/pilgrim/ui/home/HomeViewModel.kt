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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Backing VM for the Home walk list. Observes all walks, filters to
 * finished ones (`endTimestamp != null`), maps each to a
 * [HomeWalkRow] with the text fields precomputed. Row recomposition
 * is then a no-op pass-through of already-formatted strings.
 *
 * Stage 3-E additions:
 * - [hemisphere] proxied from [HemisphereRepository] so the HomeScreen
 *   can observe hemisphere changes alongside the walk list without
 *   bundling them into [HomeUiState].
 * - [HomeWalkRow] now carries raw `uuid`/`startTimestamp`/
 *   `distanceMeters`/`durationSeconds` for the calligraphy thread
 *   backdrop's stroke synthesis.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
    unitsPreferences: UnitsPreferencesRepository,
) : ViewModel() {

    /**
     * Proxied from [HemisphereRepository]. Separate from [uiState] so
     * a rare hemisphere flip doesn't force a row-list recomposition;
     * the HomeScreen `remember(rows, hemisphere)` keys on both
     * explicitly and invalidates the strokes list on either change.
     *
     * Note on first-frame behavior: after a user's very first walk
     * (which wrote Southern to DataStore via
     * [org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel.finishWalk]),
     * the first HomeScreen render may briefly show Northern before
     * the DataStore read propagates (tens of ms). The
     * `distinctUntilChanged` inside [HemisphereRepository] prevents a
     * redundant second emit; the `@Composable`'s `remember` re-keys
     * on the hemisphere change and re-tints without a visible flicker.
     * Accepted as imperceptible for a one-time first-install edge case.
     */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    /**
     * Stage 10-C: passthrough of the units preference. Combined into
     * the row-mapping flow below so distance text re-formats as soon
     * as the user toggles units in Settings — no manual refresh.
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    val uiState: StateFlow<HomeUiState> = combine(
        repository.observeAllWalks(),
        unitsPreferences.distanceUnits,
    ) { walks, units ->
        val finished = walks.filter { it.endTimestamp != null }
        if (finished.isEmpty()) {
            HomeUiState.Empty
        } else {
            // Iterable.map's lambda is non-suspend, so we step through
            // the list manually — `mapToRow` is a `suspend fun` (it
            // reads route samples + recording counts off the IO
            // dispatcher).
            val rows = finished.map { mapToRow(it, units) }
            HomeUiState.Loaded(rows)
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

    private suspend fun mapToRow(walk: Walk, units: UnitSystem): HomeWalkRow {
        val endMs = walk.endTimestamp ?: clock.now()
        val elapsedMs = endMs - walk.startTimestamp
        val samples = repository.locationSamplesFor(walk.id).map {
            LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
        }
        val distance = walkDistanceMeters(samples)
        val recordingCount = repository.countVoiceRecordingsFor(walk.id)
        return HomeWalkRow(
            walkId = walk.id,
            uuid = walk.uuid,
            startTimestamp = walk.startTimestamp,
            distanceMeters = distance,
            durationSeconds = elapsedMs / 1000.0,
            relativeDate = HomeFormat.relativeDate(
                context = context,
                timestampMs = walk.startTimestamp,
                nowMs = clock.now(),
            ),
            durationText = WalkFormat.duration(elapsedMs),
            distanceText = WalkFormat.distance(distance, units),
            recordingCountText = HomeFormat.recordingCountLabel(context, recordingCount),
            intention = walk.intention?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
