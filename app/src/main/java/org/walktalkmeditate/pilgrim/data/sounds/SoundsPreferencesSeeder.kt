// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-launch defaults for bell + soundscape selections. Mirrors iOS
 * `AppDelegate.swift:61-73 @ db4196e` — without this seed, a fresh
 * install reads `null` from every selection flow, and the bell-id
 * gate added in `MeditationBellObserver` would silence the meditation
 * bell entirely. The user explicitly picks "None" by writing `null`
 * (which removes the DataStore key); the seed runs once, guarded by
 * [KEY_DEFAULTS_MIGRATED] so a subsequent app launch never resurrects
 * a value the user cleared.
 *
 * iOS uses UserDefaults `register(defaults:)` to seed unseen keys; we
 * use a one-shot write gated by a migration flag for the same effect.
 */
@Singleton
class SoundsPreferencesSeeder @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun seedDefaultsIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[KEY_DEFAULTS_MIGRATED] == true) return@edit
            seedIfAbsent(prefs, KEY_WALK_START, "echo-chime")
            seedIfAbsent(prefs, KEY_WALK_END, "gentle-harp")
            seedIfAbsent(prefs, KEY_MEDITATION_START, "temple-bell")
            seedIfAbsent(prefs, KEY_MEDITATION_END, "yoga-chime")
            seedIfAbsent(prefs, KEY_SOUNDSCAPE, "gentle-stream")
            prefs[KEY_DEFAULTS_MIGRATED] = true
        }
    }

    private fun seedIfAbsent(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String,
    ) {
        if (prefs[key] == null) prefs[key] = value
    }

    private companion object {
        val KEY_DEFAULTS_MIGRATED = booleanPreferencesKey("soundscapeDefaultMigrated_v1")
        val KEY_WALK_START = stringPreferencesKey("walkStartBellId")
        val KEY_WALK_END = stringPreferencesKey("walkEndBellId")
        val KEY_MEDITATION_START = stringPreferencesKey("meditationStartBellId")
        val KEY_MEDITATION_END = stringPreferencesKey("meditationEndBellId")
        val KEY_SOUNDSCAPE = stringPreferencesKey("selectedSoundscapeId")
    }
}
