// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
 * DataStore-backed [SoundsPreferencesRepository]. Eagerly starts each
 * StateFlow so audio observers (bells, soundscapes) read `.value`
 * synchronously at gate-check time. Same architecture as
 * [org.walktalkmeditate.pilgrim.data.appearance.DataStoreAppearancePreferencesRepository]
 * (Stage 9.5-E).
 *
 * Storage keys match iOS UserDefaults verbatim so a `.pilgrim` ZIP
 * round-trips between platforms.
 */
@Singleton
class DataStoreSoundsPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @SoundsPreferencesScope private val scope: CoroutineScope,
) : SoundsPreferencesRepository {

    override val soundsEnabled: StateFlow<Boolean> = boolFlow(KEY_SOUNDS_ENABLED, DEFAULT_SOUNDS_ENABLED)
    override val bellHapticEnabled: StateFlow<Boolean> = boolFlow(KEY_BELL_HAPTIC_ENABLED, DEFAULT_BELL_HAPTIC_ENABLED)
    override val bellVolume: StateFlow<Float> = floatFlow(KEY_BELL_VOLUME, DEFAULT_BELL_VOLUME)
    override val soundscapeVolume: StateFlow<Float> = floatFlow(KEY_SOUNDSCAPE_VOLUME, DEFAULT_SOUNDSCAPE_VOLUME)
    override val walkStartBellId: StateFlow<String?> = nullableStringFlow(KEY_WALK_START_BELL_ID)
    override val walkEndBellId: StateFlow<String?> = nullableStringFlow(KEY_WALK_END_BELL_ID)
    override val meditationStartBellId: StateFlow<String?> = nullableStringFlow(KEY_MEDITATION_START_BELL_ID)
    override val meditationEndBellId: StateFlow<String?> = nullableStringFlow(KEY_MEDITATION_END_BELL_ID)
    override val breathRhythm: StateFlow<Int> = intFlow(KEY_BREATH_RHYTHM, DEFAULT_BREATH_RHYTHM)

    override suspend fun setSoundsEnabled(value: Boolean) {
        dataStore.edit { it[KEY_SOUNDS_ENABLED] = value }
    }

    override suspend fun setBellHapticEnabled(value: Boolean) {
        dataStore.edit { it[KEY_BELL_HAPTIC_ENABLED] = value }
    }

    override suspend fun setBellVolume(value: Float) {
        dataStore.edit { it[KEY_BELL_VOLUME] = value }
    }

    override suspend fun setSoundscapeVolume(value: Float) {
        dataStore.edit { it[KEY_SOUNDSCAPE_VOLUME] = value }
    }

    override suspend fun setWalkStartBellId(value: String?) {
        writeNullableString(KEY_WALK_START_BELL_ID, value)
    }

    override suspend fun setWalkEndBellId(value: String?) {
        writeNullableString(KEY_WALK_END_BELL_ID, value)
    }

    override suspend fun setMeditationStartBellId(value: String?) {
        writeNullableString(KEY_MEDITATION_START_BELL_ID, value)
    }

    override suspend fun setMeditationEndBellId(value: String?) {
        writeNullableString(KEY_MEDITATION_END_BELL_ID, value)
    }

    override suspend fun setBreathRhythm(value: Int) {
        dataStore.edit { it[KEY_BREATH_RHYTHM] = value }
    }

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): StateFlow<Boolean> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "sounds datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] ?: default }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, default)

    private fun floatFlow(key: Preferences.Key<Float>, default: Float): StateFlow<Float> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "sounds datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] ?: default }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, default)

    private fun intFlow(key: Preferences.Key<Int>, default: Int): StateFlow<Int> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "sounds datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] ?: default }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, default)

    private fun nullableStringFlow(key: Preferences.Key<String>): StateFlow<String?> =
        dataStore.data
            .catch { t ->
                Log.w(TAG, "sounds datastore read failed; emitting empty", t)
                emit(emptyPreferences())
            }
            .map { it[key] }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    private suspend fun writeNullableString(key: Preferences.Key<String>, value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    private companion object {
        const val TAG = "SoundsPrefs"
        // iOS UserDefaults keys — match verbatim for cross-platform
        // .pilgrim ZIP round-trip. Keep alphabetical for grep-ability.
        val KEY_BELL_HAPTIC_ENABLED = booleanPreferencesKey("bellHapticEnabled")
        val KEY_BELL_VOLUME = floatPreferencesKey("bellVolume")
        val KEY_BREATH_RHYTHM = intPreferencesKey("breathRhythm")
        val KEY_MEDITATION_END_BELL_ID = stringPreferencesKey("meditationEndBellId")
        val KEY_MEDITATION_START_BELL_ID = stringPreferencesKey("meditationStartBellId")
        val KEY_SOUNDS_ENABLED = booleanPreferencesKey("soundsEnabled")
        val KEY_SOUNDSCAPE_VOLUME = floatPreferencesKey("soundscapeVolume")
        val KEY_WALK_END_BELL_ID = stringPreferencesKey("walkEndBellId")
        val KEY_WALK_START_BELL_ID = stringPreferencesKey("walkStartBellId")

        const val DEFAULT_SOUNDS_ENABLED = true
        const val DEFAULT_BELL_HAPTIC_ENABLED = true
        const val DEFAULT_BELL_VOLUME = 0.7f
        const val DEFAULT_SOUNDSCAPE_VOLUME = 0.4f
        const val DEFAULT_BREATH_RHYTHM = 0 // BreathRhythm.DEFAULT_ID
    }
}
