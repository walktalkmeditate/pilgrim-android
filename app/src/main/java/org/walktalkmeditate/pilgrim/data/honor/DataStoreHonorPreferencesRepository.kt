// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * DataStore-backed [HonorPreferencesRepository], eagerly started so the
 * overview's toggle shows the stored value as it opens. The keys are iOS's
 * UserDefaults keys verbatim.
 */
@Singleton
class DataStoreHonorPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @HonorPreferencesScope scope: CoroutineScope,
) : HonorPreferencesRepository {

    private val stored: Flow<Preferences> = dataStore.data
        .catch { t ->
            Log.w(TAG, "honor preferences read failed; using defaults", t)
            emit(emptyPreferences())
        }

    private val storedVoicesEnabled: Flow<Boolean> = stored.map { it[KEY_HONOR_VOICES_ENABLED] ?: DEFAULT_VOICES_ENABLED }

    override val voicesEnabled: StateFlow<Boolean> = storedVoicesEnabled
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_VOICES_ENABLED)

    override suspend fun setVoicesEnabled(value: Boolean) {
        dataStore.edit { it[KEY_HONOR_VOICES_ENABLED] = value }
    }

    override suspend fun awaitVoicesEnabled(): Boolean = storedVoicesEnabled.first()

    override suspend fun awaitPilgrimageOfflineNoteShown(): Boolean =
        stored.map { it[KEY_PILGRIMAGE_OFFLINE_NOTE_SHOWN] ?: false }.first()

    override suspend fun setPilgrimageOfflineNoteShown() {
        dataStore.edit { it[KEY_PILGRIMAGE_OFFLINE_NOTE_SHOWN] = true }
    }

    private companion object {
        const val TAG = "HonorPrefs"
        val KEY_HONOR_VOICES_ENABLED = booleanPreferencesKey("honorVoicesEnabled")
        val KEY_PILGRIMAGE_OFFLINE_NOTE_SHOWN = booleanPreferencesKey("pilgrimageOfflineNoteShown")
        const val DEFAULT_VOICES_ENABLED = true
    }
}
