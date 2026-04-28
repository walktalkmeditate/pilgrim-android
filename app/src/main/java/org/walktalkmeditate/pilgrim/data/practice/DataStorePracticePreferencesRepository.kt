// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
 * DataStore-backed [PracticePreferencesRepository]. Eagerly starts each
 * StateFlow so consumers (intention sheet gate, light-reading gate,
 * celestial calculator zodiac selector, photo-picker gate) can read
 * `.value` synchronously at decision time. Same architecture as
 * [org.walktalkmeditate.pilgrim.data.sounds.DataStoreSoundsPreferencesRepository]
 * (Stage 10-B).
 *
 * Storage keys match iOS UserDefaults verbatim so a `.pilgrim` ZIP
 * round-trips between platforms.
 */
@Singleton
class DataStorePracticePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @PracticePreferencesScope private val scope: CoroutineScope,
) : PracticePreferencesRepository {

    override val beginWithIntention: StateFlow<Boolean> =
        boolFlow(KEY_BEGIN_WITH_INTENTION, DEFAULT_BEGIN_WITH_INTENTION)
    override val celestialAwarenessEnabled: StateFlow<Boolean> =
        boolFlow(KEY_CELESTIAL_AWARENESS_ENABLED, DEFAULT_CELESTIAL_AWARENESS_ENABLED)
    override val zodiacSystem: StateFlow<ZodiacSystem> = dataStore.data
        .catch { t ->
            Log.w(TAG, "practice datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { ZodiacSystem.fromStorageValue(it[KEY_ZODIAC_SYSTEM]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ZodiacSystem.DEFAULT)
    override val walkReliquaryEnabled: StateFlow<Boolean> =
        boolFlow(KEY_WALK_RELIQUARY_ENABLED, DEFAULT_WALK_RELIQUARY_ENABLED)

    override suspend fun setBeginWithIntention(value: Boolean) {
        dataStore.edit { it[KEY_BEGIN_WITH_INTENTION] = value }
    }

    override suspend fun setCelestialAwarenessEnabled(value: Boolean) {
        dataStore.edit { it[KEY_CELESTIAL_AWARENESS_ENABLED] = value }
    }

    override suspend fun setZodiacSystem(value: ZodiacSystem) {
        dataStore.edit { it[KEY_ZODIAC_SYSTEM] = value.storageValue() }
    }

    override suspend fun setWalkReliquaryEnabled(value: Boolean) {
        dataStore.edit { it[KEY_WALK_RELIQUARY_ENABLED] = value }
    }

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): StateFlow<Boolean> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "practice datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] ?: default }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, default)

    private companion object {
        const val TAG = "PracticePrefs"
        // iOS UserDefaults keys — match verbatim for cross-platform
        // .pilgrim ZIP round-trip. Keep alphabetical for grep-ability.
        val KEY_BEGIN_WITH_INTENTION = booleanPreferencesKey("beginWithIntention")
        val KEY_CELESTIAL_AWARENESS_ENABLED = booleanPreferencesKey("celestialAwarenessEnabled")
        val KEY_WALK_RELIQUARY_ENABLED = booleanPreferencesKey("walkReliquaryEnabled")
        val KEY_ZODIAC_SYSTEM = stringPreferencesKey("zodiacSystem")

        const val DEFAULT_BEGIN_WITH_INTENTION = false
        const val DEFAULT_CELESTIAL_AWARENESS_ENABLED = false
        const val DEFAULT_WALK_RELIQUARY_ENABLED = false
    }
}
