// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

data class AboutStats(
    val walkCount: Int,
    val totalDistanceMeters: Double,
    val firstWalkInstant: Instant?,
    val hasWalks: Boolean,
) {
    companion object {
        val Empty = AboutStats(0, 0.0, null, false)
    }
}

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val walkSource: AboutWalkSource,
    unitsPreferences: UnitsPreferencesRepository,
) : ViewModel() {

    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    /**
     * Stage 11-A: reads `Walk.distanceMeters` cache col directly instead
     * of running `locationSamplesFor` per walk. Cache cols are populated
     * at finalize-time (Task 5) and a backfill coordinator (Task 6)
     * drains stale rows in the background, so a `null` is the empty-state
     * (no walks) or a transient pre-backfill state — both safely handled
     * by `?: 0.0`. Drops the per-walk N+1 to zero queries.
     */
    val stats: StateFlow<AboutStats> = walkSource.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) return@map AboutStats.Empty
            AboutStats(
                walkCount = finished.size,
                totalDistanceMeters = finished.sumOf { it.distanceMeters ?: 0.0 },
                firstWalkInstant = Instant.ofEpochMilli(finished.minOf { it.startTimestamp }),
                hasWalks = true,
            )
        }
        .catch { emit(AboutStats.Empty) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AboutStats.Empty,
        )
}
