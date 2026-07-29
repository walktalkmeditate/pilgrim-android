// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val repository: PermissionsRepository,
    // Same PermissionsRepository singleton, narrowed to its ritual facet so
    // tests can fake the once-per-grant persistence (see PermissionsViewModelTest).
    private val ritualStore: PermissionRitualStore,
    private val bell: BellPlaying,
    private val soundsPreferences: SoundsPreferencesRepository,
) : ViewModel() {

    val onboardingComplete: StateFlow<Boolean> = repository.onboardingComplete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val batteryExemptionAsked: StateFlow<Boolean> = repository.batteryExemptionAsked.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    private val _locationPulse = MutableStateFlow(false)
    val locationPulse: StateFlow<Boolean> = _locationPulse.asStateFlow()
    private val _microphonePulse = MutableStateFlow(false)
    val microphonePulse: StateFlow<Boolean> = _microphonePulse.asStateFlow()
    private val _activityPulse = MutableStateFlow(false)
    val activityPulse: StateFlow<Boolean> = _activityPulse.asStateFlow()

    fun markOnboardingComplete() {
        viewModelScope.launch {
            repository.markOnboardingComplete()
        }
    }

    fun markBatteryExemptionAsked() {
        viewModelScope.launch {
            repository.markBatteryExemptionAsked()
        }
    }

    /**
     * The #43 grant ritual, fired when [permission] is newly granted (iOS
     * `PermissionsViewModel.celebrateGrant`): a one-shot bell (once per
     * permission, persisted) at half volume, a soft success haptic alongside
     * it, and a checkmark pulse on the granted card.
     *
     * The bell + haptic honor [soundsEnabled] and the once-per-grant flag;
     * the pulse plays whenever motion is allowed (even with sounds off) and
     * is skipped under reduce-motion. [onGrantHaptic] is injected by the
     * caller so the haptic fires through Compose's `LocalHapticFeedback`
     * while the bell decision stays unit-testable.
     */
    fun celebrateGrant(
        permission: PermissionRitual.Permission,
        soundsEnabled: Boolean,
        reduceMotion: Boolean,
        onGrantHaptic: () -> Unit = {},
    ) {
        viewModelScope.launch {
            val shouldBell = ritualStore.consumeBellGrant(permission, soundsEnabled)
            if (shouldBell) {
                // iOS plays the user's meditation-END bell here
                // (PermissionsViewModel.swift playGrantBell — yoga-chime by
                // default), not a dedicated grant sound. Passing the id keeps
                // parity once the pack downloads; Android's bundled fallback
                // still rings before that (deliberate divergence — iOS's
                // isAvailable guard goes silent instead).
                bell.play(
                    bellId = soundsPreferences.meditationEndBellId.value,
                    scale = GRANT_BELL_SCALE,
                    withHaptic = false,
                )
                onGrantHaptic()
            }
            if (!reduceMotion) pulse(permission)
        }
    }

    /**
     * One-shot pulse: flip the flag on (the card springs its checkmark to
     * 1.15×), then off after the grow settles so it springs back to 1.0×.
     */
    private suspend fun pulse(permission: PermissionRitual.Permission) {
        try {
            setPulse(permission, true)
            delay(PULSE_HOLD_MS)
        } finally {
            // Reset even if the scope is cancelled mid-hold, so the pulse flag
            // never strands `true` (a permanently enlarged checkmark).
            setPulse(permission, false)
        }
    }

    private fun setPulse(permission: PermissionRitual.Permission, value: Boolean) {
        when (permission) {
            PermissionRitual.Permission.Location -> _locationPulse.value = value
            PermissionRitual.Permission.Microphone -> _microphonePulse.value = value
            PermissionRitual.Permission.Activity -> _activityPulse.value = value
        }
    }

    companion object {
        /** iOS plays the grant bell at volume 0.5 (PermissionsViewModel.swift). */
        const val GRANT_BELL_SCALE = 0.5f
        /** iOS holds the pulse 0.2s before springing the checkmark back. */
        const val PULSE_HOLD_MS = 200L
    }
}
