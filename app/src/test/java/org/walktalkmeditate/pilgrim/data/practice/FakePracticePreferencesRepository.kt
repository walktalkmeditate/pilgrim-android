// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [PracticePreferencesRepository]. Used by
 * Settings card + downstream consumer (intention sheet, light-reading,
 * celestial calculator, photo picker) tests to vary prefs without a
 * real DataStore. Default values mirror iOS production defaults so a
 * fresh `FakePracticePreferencesRepository()` matches a fresh-install
 * user.
 */
class FakePracticePreferencesRepository(
    initialBeginWithIntention: Boolean = false,
    initialCelestialAwarenessEnabled: Boolean = false,
    initialZodiacSystem: ZodiacSystem = ZodiacSystem.Tropical,
    initialWalkReliquaryEnabled: Boolean = false,
) : PracticePreferencesRepository {

    private val _beginWithIntention = MutableStateFlow(initialBeginWithIntention)
    override val beginWithIntention: StateFlow<Boolean> = _beginWithIntention.asStateFlow()
    override suspend fun setBeginWithIntention(value: Boolean) {
        _beginWithIntention.value = value
    }

    private val _celestialAwarenessEnabled = MutableStateFlow(initialCelestialAwarenessEnabled)
    override val celestialAwarenessEnabled: StateFlow<Boolean> = _celestialAwarenessEnabled.asStateFlow()
    override suspend fun setCelestialAwarenessEnabled(value: Boolean) {
        _celestialAwarenessEnabled.value = value
    }

    private val _zodiacSystem = MutableStateFlow(initialZodiacSystem)
    override val zodiacSystem: StateFlow<ZodiacSystem> = _zodiacSystem.asStateFlow()
    override suspend fun setZodiacSystem(value: ZodiacSystem) {
        _zodiacSystem.value = value
    }

    private val _walkReliquaryEnabled = MutableStateFlow(initialWalkReliquaryEnabled)
    override val walkReliquaryEnabled: StateFlow<Boolean> = _walkReliquaryEnabled.asStateFlow()
    override suspend fun setWalkReliquaryEnabled(value: Boolean) {
        _walkReliquaryEnabled.value = value
    }
}
