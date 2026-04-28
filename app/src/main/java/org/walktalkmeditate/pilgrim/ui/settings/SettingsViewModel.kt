// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

/**
 * Stage 8-B: ViewModel for the Settings screen surfaces — currently
 * just the collective-counter display + opt-in toggle. Passthrough
 * to [CollectiveRepository]; no UI-only state lives here.
 *
 * Stage 9.5-E adds the appearance-mode passthrough to
 * [AppearancePreferencesRepository] so the new Atmosphere card can
 * render the persisted preference and write user changes back.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val collectiveRepository: CollectiveRepository,
    private val appearancePreferences: AppearancePreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
    unitsPreferences: UnitsPreferencesRepository,
) : ViewModel() {

    val stats: StateFlow<CollectiveStats?> = collectiveRepository.stats
    val optIn: StateFlow<Boolean> = collectiveRepository.optIn
    val appearanceMode: StateFlow<AppearanceMode> = appearancePreferences.appearanceMode
    val soundsEnabled: StateFlow<Boolean> = soundsPreferences.soundsEnabled

    /**
     * Stage 10-C: passthrough so [CollectiveStatsCard] can format
     * the community totals in the user's preferred units.
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    fun setOptIn(value: Boolean) {
        viewModelScope.launch { collectiveRepository.setOptIn(value) }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        viewModelScope.launch {
            // DataStore writes can throw on disk-full / corrupt-prefs / IO
            // failure. viewModelScope uses a SupervisorJob, so an unhandled
            // throw from this child coroutine routes to the thread's
            // uncaught exception handler — on Main, that crashes the
            // process. Swallow + log: the optimistic UI selection (driven
            // by collectAsStateWithLifecycle on the StateFlow) will revert
            // to the persisted value on next emission, signaling the
            // failure to the user without crashing.
            runCatching { appearancePreferences.setAppearanceMode(mode) }
                .onFailure { Log.w(TAG, "failed to persist appearance mode", it) }
        }
    }

    fun setSoundsEnabled(value: Boolean) {
        viewModelScope.launch {
            // Same swallow-and-log pattern as setAppearanceMode: on a
            // DataStore write failure, the UI's optimistic checked state
            // reverts to the persisted value via the StateFlow re-emit.
            runCatching { soundsPreferences.setSoundsEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist sounds toggle", it) }
        }
    }

    fun fetchOnAppear() {
        viewModelScope.launch { collectiveRepository.fetchIfStale() }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
