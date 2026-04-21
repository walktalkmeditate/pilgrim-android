// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

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
 * Persists the currently-selected soundscape asset id in DataStore
 * Preferences. Exposed as a [StateFlow] so UI surfaces can observe
 * selection changes reactively.
 *
 * Independent of voice-guide selection: user can have voice-guide
 * A + soundscape B, or just one, or neither. Mirrors iOS's
 * `UserPreferences.selectedSoundscapeId` key.
 */
@Singleton
class SoundscapeSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @SoundscapeSelectionScope private val scope: CoroutineScope,
) {
    val selectedSoundscapeId: StateFlow<String?> = dataStore.data
        .catch { t ->
            Log.w(TAG, "selection datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SELECTED] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    suspend fun select(assetId: String) {
        dataStore.edit { it[KEY_SELECTED] = assetId }
    }

    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }

    private companion object {
        const val TAG = "SoundscapeSelection"
        val KEY_SELECTED = stringPreferencesKey("selected_soundscape_id")
    }
}
