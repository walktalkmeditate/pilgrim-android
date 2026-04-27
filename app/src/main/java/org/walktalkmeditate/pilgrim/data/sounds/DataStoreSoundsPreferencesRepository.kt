// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
 * DataStore-backed [SoundsPreferencesRepository]. Eagerly starts the
 * StateFlow so audio observers (bells, soundscapes) read `.value`
 * synchronously at gate-check time. Same architecture as
 * [org.walktalkmeditate.pilgrim.data.appearance.DataStoreAppearancePreferencesRepository]
 * (Stage 9.5-E).
 */
@Singleton
class DataStoreSoundsPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @SoundsPreferencesScope private val scope: CoroutineScope,
) : SoundsPreferencesRepository {

    override val soundsEnabled: StateFlow<Boolean> = dataStore.data
        .catch { t ->
            Log.w(TAG, "sounds datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SOUNDS_ENABLED] ?: DEFAULT_SOUNDS_ENABLED }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_SOUNDS_ENABLED)

    override suspend fun setSoundsEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SOUNDS_ENABLED] = value }
    }

    private companion object {
        const val TAG = "SoundsPrefs"
        // iOS UserDefaults key — matches verbatim for cross-platform
        // .pilgrim ZIP round-trip.
        val KEY_SOUNDS_ENABLED = booleanPreferencesKey("soundsEnabled")
        const val DEFAULT_SOUNDS_ENABLED = true
    }
}
