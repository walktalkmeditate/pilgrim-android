// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
 * DataStore-backed [SeekPreferencesRepository]. Eagerly starts each
 * StateFlow so [org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlayer]
 * can read `.value` synchronously at play time. Same architecture as
 * [org.walktalkmeditate.pilgrim.data.practice.DataStorePracticePreferencesRepository]
 * (Stage 10-C).
 *
 * Storage keys match iOS UserDefaults verbatim
 * (`UserPreferences.swift:72-75@c1745e8`) so seek settings round-trip
 * between platforms.
 */
@Singleton
class DataStoreSeekPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @SeekPreferencesScope private val scope: CoroutineScope,
) : SeekPreferencesRepository {

    override val sonarEnabled: StateFlow<Boolean> =
        boolFlow(KEY_SEEK_SONAR_ENABLED, DEFAULT_SONAR_ENABLED)
    override val sonarVolume: StateFlow<Float> = dataStore.data
        .catch { t ->
            Log.w(TAG, "seek datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { sanitizeVolume(it[KEY_SEEK_SONAR_VOLUME]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_SONAR_VOLUME)
    override val lastDurationMinutes: StateFlow<Int> = dataStore.data
        .catch { t ->
            Log.w(TAG, "seek datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SEEK_LAST_DURATION_MINUTES] ?: DEFAULT_LAST_DURATION_MINUTES }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_LAST_DURATION_MINUTES)
    override val safetyShown: StateFlow<Boolean> =
        boolFlow(KEY_SEEK_SAFETY_SHOWN, DEFAULT_SAFETY_SHOWN)

    override suspend fun setSonarEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SEEK_SONAR_ENABLED] = value }
    }

    override suspend fun setSonarVolume(value: Float) {
        dataStore.edit { it[KEY_SEEK_SONAR_VOLUME] = sanitizeVolume(value) }
    }

    override suspend fun setLastDurationMinutes(value: Int) {
        dataStore.edit { it[KEY_SEEK_LAST_DURATION_MINUTES] = value }
    }

    override suspend fun setSafetyShown(value: Boolean) {
        dataStore.edit { it[KEY_SEEK_SAFETY_SHOWN] = value }
    }

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): StateFlow<Boolean> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "seek datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] ?: default }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, default)

    private companion object {
        const val TAG = "SeekPrefs"

        /**
         * `coerceIn` does NOT sanitize NaN (`Float.NaN.coerceIn(0f, 1f)
         * == NaN` — BellPlayer precedent), so NaN maps to the default
         * explicitly. Applied on BOTH read and write: writes from
         * future callers clamp at the seam, and a value persisted by
         * an old binary outside [0, 1] never reaches a player.
         */
        fun sanitizeVolume(value: Float?): Float = when {
            value == null || value.isNaN() -> DEFAULT_SONAR_VOLUME
            else -> value.coerceIn(0f, 1f)
        }

        // iOS UserDefaults keys — match verbatim for cross-platform
        // settings round-trip. Keep alphabetical for grep-ability.
        val KEY_SEEK_LAST_DURATION_MINUTES = intPreferencesKey("seekLastDurationMinutes")
        val KEY_SEEK_SAFETY_SHOWN = booleanPreferencesKey("seekSafetyShown")
        val KEY_SEEK_SONAR_ENABLED = booleanPreferencesKey("seekSonarEnabled")
        val KEY_SEEK_SONAR_VOLUME = floatPreferencesKey("seekSonarVolume")

        const val DEFAULT_LAST_DURATION_MINUTES = 60
        const val DEFAULT_SAFETY_SHOWN = false
        const val DEFAULT_SONAR_ENABLED = true
        const val DEFAULT_SONAR_VOLUME = 0.5f
    }
}
