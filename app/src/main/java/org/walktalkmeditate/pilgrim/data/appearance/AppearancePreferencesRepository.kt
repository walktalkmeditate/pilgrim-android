// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's chosen [AppearanceMode]. Exposed as a
 * [StateFlow] so [org.walktalkmeditate.pilgrim.MainActivity]
 * can read the current value synchronously at `setContent` time
 * (`Eagerly` start strategy + `.value`) and the [SettingsViewModel]
 * can observe changes for picker rendering.
 *
 * Mirrors iOS's `UserPreferences.appearanceMode` key.
 */
interface AppearancePreferencesRepository {
    val appearanceMode: StateFlow<AppearanceMode>
    suspend fun setAppearanceMode(mode: AppearanceMode)
}
