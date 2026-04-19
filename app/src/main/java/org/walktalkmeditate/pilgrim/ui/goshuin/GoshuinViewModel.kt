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
                val seals = finished.map { mapToSeal(it) }
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

    private suspend fun mapToSeal(walk: Walk): GoshuinSeal {
        val samples = repository.locationSamplesFor(walk.id).map {
            LocationPoint(
                timestamp = it.timestamp,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
        val distance = walkDistanceMeters(samples)
        val distanceLabel = WalkFormat.distanceLabel(distance)
        val sealSpec = walk.toSealSpec(
            distanceMeters = distance,
            ink = Color.Transparent,
            displayDistance = distanceLabel.value,
            unitLabel = distanceLabel.unit,
        )
        val walkDate = Instant.ofEpochMilli(walk.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return GoshuinSeal(
            walkId = walk.id,
            sealSpec = sealSpec,
            walkDate = walkDate,
            shortDateLabel = SHORT_DATE_FORMATTER.format(walkDate),
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
        // Locale-sensitive: `"Apr 19"` on en-US, `"19 avr."` on fr-FR.
        private val SHORT_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    }
}
