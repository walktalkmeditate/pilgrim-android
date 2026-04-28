// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [SoundsPreferencesRepository]. Used by
 * audio observer + ViewModel tests to vary the master toggle and
 * sibling prefs without a real DataStore. Default values mirror the
 * iOS production defaults so a fresh `FakeSoundsPreferencesRepository()`
 * matches a fresh-install user.
 */
class FakeSoundsPreferencesRepository(
    initialSoundsEnabled: Boolean = true,
    initialBellHapticEnabled: Boolean = true,
    initialBellVolume: Float = 0.7f,
    initialSoundscapeVolume: Float = 0.4f,
    initialWalkStartBellId: String? = null,
    initialWalkEndBellId: String? = null,
    initialMeditationStartBellId: String? = null,
    initialMeditationEndBellId: String? = null,
    initialBreathRhythm: Int = 0,
) : SoundsPreferencesRepository {
    private val _soundsEnabled = MutableStateFlow(initialSoundsEnabled)
    override val soundsEnabled: StateFlow<Boolean> = _soundsEnabled.asStateFlow()
    override suspend fun setSoundsEnabled(value: Boolean) {
        _soundsEnabled.value = value
    }

    private val _bellHapticEnabled = MutableStateFlow(initialBellHapticEnabled)
    override val bellHapticEnabled: StateFlow<Boolean> = _bellHapticEnabled.asStateFlow()
    override suspend fun setBellHapticEnabled(value: Boolean) {
        _bellHapticEnabled.value = value
    }

    private val _bellVolume = MutableStateFlow(initialBellVolume)
    override val bellVolume: StateFlow<Float> = _bellVolume.asStateFlow()
    override suspend fun setBellVolume(value: Float) {
        _bellVolume.value = value
    }

    private val _soundscapeVolume = MutableStateFlow(initialSoundscapeVolume)
    override val soundscapeVolume: StateFlow<Float> = _soundscapeVolume.asStateFlow()
    override suspend fun setSoundscapeVolume(value: Float) {
        _soundscapeVolume.value = value
    }

    private val _walkStartBellId = MutableStateFlow(initialWalkStartBellId)
    override val walkStartBellId: StateFlow<String?> = _walkStartBellId.asStateFlow()
    override suspend fun setWalkStartBellId(value: String?) {
        _walkStartBellId.value = value
    }

    private val _walkEndBellId = MutableStateFlow(initialWalkEndBellId)
    override val walkEndBellId: StateFlow<String?> = _walkEndBellId.asStateFlow()
    override suspend fun setWalkEndBellId(value: String?) {
        _walkEndBellId.value = value
    }

    private val _meditationStartBellId = MutableStateFlow(initialMeditationStartBellId)
    override val meditationStartBellId: StateFlow<String?> = _meditationStartBellId.asStateFlow()
    override suspend fun setMeditationStartBellId(value: String?) {
        _meditationStartBellId.value = value
    }

    private val _meditationEndBellId = MutableStateFlow(initialMeditationEndBellId)
    override val meditationEndBellId: StateFlow<String?> = _meditationEndBellId.asStateFlow()
    override suspend fun setMeditationEndBellId(value: String?) {
        _meditationEndBellId.value = value
    }

    private val _breathRhythm = MutableStateFlow(initialBreathRhythm)
    override val breathRhythm: StateFlow<Int> = _breathRhythm.asStateFlow()
    override suspend fun setBreathRhythm(value: Int) {
        _breathRhythm.value = value
    }
}
