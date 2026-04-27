// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory test double for [AppearancePreferencesRepository]. Used by
 * `SettingsViewModelAppearanceTest` and any future surface that needs
 * to vary the mode without spinning up a real DataStore.
 */
class FakeAppearancePreferencesRepository(
    initial: AppearanceMode = AppearanceMode.System,
) : AppearancePreferencesRepository {

    private val _appearanceMode = MutableStateFlow(initial)
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    override suspend fun setAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
    }
}
