// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator

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

    val stats: StateFlow<AboutStats> = walkSource.observeAllWalks()
        .map { walks ->
            withContext(Dispatchers.IO) {
                val finished = walks.filter { it.endTimestamp != null }
                if (finished.isEmpty()) return@withContext AboutStats.Empty
                val totalDistance = finished.sumOf { walk ->
                    WalkDistanceCalculator.computeDistanceMeters(
                        walkSource.locationSamplesFor(walk.id),
                    )
                }
                val firstStart = finished.minOf { it.startTimestamp }
                AboutStats(
                    walkCount = finished.size,
                    totalDistanceMeters = totalDistance,
                    firstWalkInstant = Instant.ofEpochMilli(firstStart),
                    hasWalks = true,
                )
            }
        }
        .catch { emit(AboutStats.Empty) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AboutStats.Empty,
        )
}
