// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.launcher.IconSwitcher
import org.walktalkmeditate.pilgrim.data.launcher.IconVariant
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
    private val iconSwitcher: IconSwitcher,
) : ViewModel() {

    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    /**
     * Reviewer-flagged: `flowOn(Dispatchers.IO)` for parity with
     * `SettingsViewModel.practiceSummary` — Room hot Flow defaults to
     * Room's executor but explicit IO insulates against test configs
     * + future migrations that change the emit dispatcher.
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
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AboutStats.Empty,
        )

    /**
     * iOS parity `AboutView.swift:73-82@db4196e` — observable current
     * app-icon variant. Refreshed via [refreshIconVariant] every time
     * the confirmation dialog opens so the "is current?" predicate
     * is fresh after a back-to-back switch.
     */
    private val _iconVariant = MutableStateFlow(iconSwitcher.currentVariant())
    val iconVariant: StateFlow<IconVariant> = _iconVariant.asStateFlow()

    fun refreshIconVariant() {
        _iconVariant.value = iconSwitcher.currentVariant()
    }

    fun setIconVariant(target: IconVariant) {
        // Reviewer-flagged: `setComponentEnabledSetting` can throw
        // `SecurityException` on some hardened ROMs. A throw inside
        // a Compose click handler would propagate to the event
        // dispatcher and crash. Catch + re-sync from
        // `currentVariant()` so the UI never lies about which alias
        // is enabled.
        try {
            iconSwitcher.switchTo(target)
            _iconVariant.value = target
        } catch (_: Exception) {
            _iconVariant.value = iconSwitcher.currentVariant()
        }
    }
}
