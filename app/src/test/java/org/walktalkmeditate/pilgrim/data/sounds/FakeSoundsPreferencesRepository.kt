// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [SoundsPreferencesRepository]. Used by
 * audio observer + ViewModel tests to vary the master toggle without
 * a real DataStore. Default `initial = true` preserves the
 * "sounds always play" behavior callers expect from prior stages.
 */
class FakeSoundsPreferencesRepository(
    initial: Boolean = true,
) : SoundsPreferencesRepository {
    private val _soundsEnabled = MutableStateFlow(initial)
    override val soundsEnabled: StateFlow<Boolean> = _soundsEnabled.asStateFlow()
    override suspend fun setSoundsEnabled(value: Boolean) {
        _soundsEnabled.value = value
    }
}
