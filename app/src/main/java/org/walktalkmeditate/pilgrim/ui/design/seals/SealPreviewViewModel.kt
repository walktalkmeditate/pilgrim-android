// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Debug-only VM for the Stage 4-A seal preview. Observes Room for
 * finished walks, maps each to a [PreviewSeal] with raw spec fields
 * (ink left as [Color.Transparent] — the preview screen resolves it
 * via [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * in a `@Composable` context).
 *
 * If there are no finished walks, falls back to eight synthetic seals
 * spread across the year for visual verification. Mirrors Stage 3-C's
 * `CalligraphyPathPreviewViewModel` pattern — will be deleted when
 * Stage 4-B's reveal animation lands in the real walk-finish flow.
 */
@HiltViewModel
class SealPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    init {
        viewModelScope.launch {
            hemisphereRepository.refreshFromLocationIfNeeded()
        }
    }

    val state: StateFlow<List<PreviewSeal>> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isNotEmpty()) {
                finished.map { walk ->
                    val samples = repository.locationSamplesFor(walk.id)
                    val distanceMeters = walkDistanceMeters(
                        samples.map {
                            LocationPoint(
                                timestamp = it.timestamp,
                                latitude = it.latitude,
                                longitude = it.longitude,
                            )
                        },
                    )
                    val formatted = WalkFormat.distance(distanceMeters)
                    // "5.20 km" → displayDistance "5.20", unit "km".
                    // "420 m"  → displayDistance "420",  unit "m".
                    val parts = formatted.split(' ', limit = 2)
                    val display = parts.getOrElse(0) { formatted }
                    val unit = parts.getOrElse(1) { "" }
                    val spec = walk.toSealSpec(
                        samples = samples,
                        ink = Color.Transparent,
                        displayDistance = display,
                        unitLabel = unit,
                    )
                    PreviewSeal(spec = spec, walkStartMillis = walk.startTimestamp)
                }
            } else {
                synthetic()
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    private fun synthetic(): List<PreviewSeal> {
        val baseMillis = clock.now() - 240L * 86_400_000L
        return (0 until 8).map { i ->
            val start = baseMillis + i * 30L * 86_400_000L
            val distanceKm = 2.5 + i * 0.4
            PreviewSeal(
                spec = SealSpec(
                    uuid = "seal-synthetic-$i",
                    startMillis = start,
                    distanceMeters = distanceKm * 1_000.0,
                    durationSeconds = 1_200.0 + i * 120.0,
                    displayDistance = "%.1f".format(distanceKm),
                    unitLabel = "km",
                    ink = Color.Transparent,
                ),
                walkStartMillis = start,
            )
        }
    }

    data class PreviewSeal(
        val spec: SealSpec,
        val walkStartMillis: Long,
    )

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
