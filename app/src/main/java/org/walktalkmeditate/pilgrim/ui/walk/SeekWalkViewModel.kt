// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.walk.seek.SeekOrchestrator
import org.walktalkmeditate.pilgrim.walk.seek.SeekPendingSession

/**
 * Thin Compose bridge over the app-scoped [SeekOrchestrator] + the seek
 * preferences: `ActiveWalkScreen` collects the fog/pulse feeds for
 * `PilgrimMap` and drives the options sheet's seek section through it.
 * Every flow is a DIRECT hot-singleton passthrough — no `stateIn`, no
 * `WhileSubscribed` — so a long composition pause can never serve stale
 * seek state (Stage 5-G rule). iOS has no analogue: the same
 * `ActiveWalkViewModel` that owns the engine feeds the views
 * (`ActiveWalkView.swift:272-277@c1745e8`).
 */
@HiltViewModel
class SeekWalkViewModel @Inject constructor(
    private val orchestrator: SeekOrchestrator,
    private val seekPreferences: SeekPreferencesRepository,
) : ViewModel() {

    /** `PilgrimMap(seekFog =)` input; null keeps wander maps untouched. */
    val fogState: StateFlow<SeekFogState?> = orchestrator.fogState

    /** `PilgrimMap(seekPulse =)` input; token advances once per heartbeat. */
    val pulse: StateFlow<SeekPulseVisual> = orchestrator.pulse

    /** Null = no live engine; COMPLETE disables the "Seek Anew" row. */
    val enginePhase: StateFlow<SeekEnginePhase?> = orchestrator.enginePhase

    /**
     * Non-null while setup output awaits the walk start — the seek
     * section renders pre-departure on this (85373c1: "letting the
     * walker check the sonar or reroll before stepping off").
     */
    val pendingSession: StateFlow<SeekPendingSession?> = orchestrator.pendingSession

    /** Live sonar-pref mirrors (iOS reads `UserPreferences.seekSonar*`). */
    val sonarEnabled: StateFlow<Boolean> = seekPreferences.sonarEnabled
    val sonarVolume: StateFlow<Float> = seekPreferences.sonarVolume

    fun setSonarEnabled(enabled: Boolean) {
        viewModelScope.launch { seekPreferences.setSonarEnabled(enabled) }
    }

    fun setSonarVolume(volume: Float) {
        viewModelScope.launch { seekPreferences.setSonarVolume(volume) }
    }

    /** R17 reroll — iOS `ActiveWalkView.swift:274-277@c1745e8`. */
    fun seekAnewRequested() {
        orchestrator.seekAnewRequested()
    }
}
