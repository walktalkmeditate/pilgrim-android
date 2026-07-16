// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [SeekPreferencesRepository]. Default
 * values mirror iOS production defaults
 * (`UserPreferences.swift:72-75@c1745e8`) so a fresh
 * `FakeSeekPreferencesRepository()` matches a fresh-install user.
 */
class FakeSeekPreferencesRepository(
    initialSonarEnabled: Boolean = true,
    initialSonarVolume: Float = 0.5f,
    initialLastDurationMinutes: Int = 60,
    initialSafetyShown: Boolean = false,
) : SeekPreferencesRepository {

    private val _sonarEnabled = MutableStateFlow(initialSonarEnabled)
    override val sonarEnabled: StateFlow<Boolean> = _sonarEnabled.asStateFlow()
    override suspend fun setSonarEnabled(value: Boolean) {
        _sonarEnabled.value = value
    }

    private val _sonarVolume = MutableStateFlow(initialSonarVolume)
    override val sonarVolume: StateFlow<Float> = _sonarVolume.asStateFlow()
    override suspend fun setSonarVolume(value: Float) {
        _sonarVolume.value = value
    }

    private val _lastDurationMinutes = MutableStateFlow(initialLastDurationMinutes)
    override val lastDurationMinutes: StateFlow<Int> = _lastDurationMinutes.asStateFlow()
    override suspend fun setLastDurationMinutes(value: Int) {
        _lastDurationMinutes.value = value
    }

    private val _safetyShown = MutableStateFlow(initialSafetyShown)
    override val safetyShown: StateFlow<Boolean> = _safetyShown.asStateFlow()
    override suspend fun setSafetyShown(value: Boolean) {
        _safetyShown.value = value
    }

    /** Non-suspend setter for tests that flip the toggle mid-scenario. */
    fun setSonarEnabledNow(value: Boolean) {
        _sonarEnabled.value = value
    }
}
