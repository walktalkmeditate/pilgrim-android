// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Backing VM for the goshuin collection grid. Observes all walks,
 * filters finished (`endTimestamp != null`), maps each to a
 * [GoshuinSeal] with a pre-built
 * [org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec]
 * (`ink = Transparent` placeholder). Ordered most-recent-first by
 * end timestamp; ties broken by id descending for stability.
 *
 * The per-walk seasonal ink shift happens in the composable layer
 * because `LocalPilgrimColors` is a theme read — matches Stage 4-B's
 * `WalkSummaryScreen.specForReveal` pattern.
 *
 * Distance per walk is computed from the GPS samples via
 * [walkDistanceMeters], mirroring
 * [org.walktalkmeditate.pilgrim.ui.home.HomeViewModel.mapToRow]. This
 * is an N+1 query per collection load; for current walk counts it is
 * a non-issue. If device QA flags jank at scale, a focused
 * `Walk.distanceMeters` cache is a separate schema-migration stage.
 */
@HiltViewModel
class GoshuinViewModel @Inject constructor(
    private val repository: WalkRepository,
    hemisphereRepository: HemisphereRepository,
    unitsPreferences: UnitsPreferencesRepository,
) : ViewModel() {

    /**
     * Proxy of [HemisphereRepository.hemisphere]. Separate from
     * [uiState] so a hemisphere flip doesn't re-emit the whole seal
     * list — the `@Composable` re-keys per-cell on hemisphere change
     * via its `remember(...)` block.
     */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    val uiState: StateFlow<GoshuinUiState> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks
                .filter { it.endTimestamp != null }
                .sortedWith(
                    compareByDescending<Walk> { it.endTimestamp }
                        .thenByDescending { it.id },
                )
            if (finished.isEmpty()) {
                GoshuinUiState.Empty
            } else {
                // Compute distance per finished walk once. mapToSeal
                // uses it for the SealSpec; the milestone detector uses
                // it to find LongestWalk across the full snapshot.
                val distances = finished.associate { walk ->
                    walk.id to walkDistanceMeters(samplesFor(walk.id))
                }
                val milestoneInputs = finished.map { walk ->
                    WalkMilestoneInput(
                        walkId = walk.id,
                        uuid = walk.uuid,
                        startTimestamp = walk.startTimestamp,
                        distanceMeters = distances.getValue(walk.id),
                        meditateDurationMillis = (walk.meditationSeconds ?: 0L) * 1000L,
                    )
                }
                // Snapshot the hemisphere at flow-emission time. A
                // subsequent hemisphere flip (rare — user crosses
                // equator) recomputes milestones on the next emission.
                // Acceptable: a "First of Spring" cell flipping to
                // "First of Autumn" matches the rest of the app's
                // seasonal-color hemisphere-change behavior.
                val currentHemisphere = hemisphere.value
                val seals = finished.mapIndexed { index, walk ->
                    mapToSeal(
                        walk = walk,
                        distance = distances.getValue(walk.id),
                        milestone = GoshuinMilestones.detect(
                            walkIndex = index,
                            walk = milestoneInputs[index],
                            allFinished = milestoneInputs,
                            hemisphere = currentHemisphere,
                        ),
                    )
                }
                GoshuinUiState.Loaded(seals = seals, totalCount = seals.size)
            }
        }
        .stateIn(
            scope = viewModelScope,
            // WhileSubscribed matches HomeViewModel — stops the Room
            // collector when the user navigates away for ≥5s; unit
            // tests without subscribers don't leave viewModelScope
            // hanging (Stage 2-E / 3-C lessons).
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = GoshuinUiState.Loading,
        )

    private suspend fun samplesFor(walkId: Long): List<LocationPoint> =
        repository.locationSamplesFor(walkId).map {
            LocationPoint(
                timestamp = it.timestamp,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }

    /**
     * Stage 10-C: passthrough of the units preference.
     *
     * NOTE: only the surrounding card text on the goshuin grid responds
     * to this — the seal artwork itself bakes [WalkFormat.distanceLabel]
     * at metric below, intentionally. The seal is treated as a permanent
     * artifact of the walk locked to the unit at generation time;
     * re-rendering the cached seal on toggle is a separate stage of
     * work (TODO stage 10-Z). Same call-site policy as
     * [org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel.buildState].
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    private fun mapToSeal(
        walk: Walk,
        distance: Double,
        milestone: GoshuinMilestone?,
    ): GoshuinSeal {
        // Seal artwork stays metric (TODO stage 10-Z; see [distanceUnits]).
        val distanceLabel = WalkFormat.distanceLabel(distance, UnitSystem.Metric)
        val sealSpec = walk.toSealSpec(
            distanceMeters = distance,
            ink = Color.Transparent,
            displayDistance = distanceLabel.value,
            unitLabel = distanceLabel.unit,
        )
        val walkDate = Instant.ofEpochMilli(walk.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        // Rebuild the formatter per call so a runtime locale change
        // (user toggles system language) takes effect on the next Room
        // emission without a process restart. Matches [HomeFormat]
        // precedent, which rebuilds `ofPattern(..., locale)` inside
        // `relativeDate` rather than caching at class load.
        val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        return GoshuinSeal(
            walkId = walk.id,
            sealSpec = sealSpec,
            walkDate = walkDate,
            shortDateLabel = shortDateFormatter.format(walkDate),
            milestone = milestone,
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
