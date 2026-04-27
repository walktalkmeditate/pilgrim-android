// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * DataStore-backed [AppearancePreferencesRepository]. Eagerly starts
 * the StateFlow so [org.walktalkmeditate.pilgrim.MainActivity]'s
 * `setContent` can read `.value` at first composition without
 * missing the persisted preference (a `WhileSubscribed` start
 * strategy would return the default until a subscriber attaches,
 * causing a one-frame light/dark flash on cold launch).
 *
 * Reuses the shared `pilgrim_prefs` DataStore — one more string key
 * (`appearance_mode`) alongside the existing voice-guide / soundscape /
 * recovery keys.
 */
@Singleton
class DataStoreAppearancePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @AppearancePreferencesScope private val scope: CoroutineScope,
) : AppearancePreferencesRepository {

    override val appearanceMode: StateFlow<AppearanceMode> = dataStore.data
        .catch { t ->
            // Disk read failures (corrupt prefs, transient I/O)
            // shouldn't crash the theme — emit empty so we fall back
            // to the default. Same resilience pattern as
            // `VoiceGuideSelectionRepository` (Stage 5-D).
            Log.w(TAG, "appearance datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { AppearanceMode.fromStorageValue(it[KEY_MODE]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AppearanceMode.DEFAULT)

    override suspend fun setAppearanceMode(mode: AppearanceMode) {
        dataStore.edit { it[KEY_MODE] = mode.storageValue() }
    }

    private companion object {
        const val TAG = "AppearancePrefs"
        val KEY_MODE = stringPreferencesKey("appearance_mode")
    }
}
