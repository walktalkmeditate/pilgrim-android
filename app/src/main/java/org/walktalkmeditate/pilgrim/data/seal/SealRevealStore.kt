// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seal

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the set of walk UUIDs whose seal-reveal animation has
 * already played. Once a walk's seal has been revealed, returning to
 * its summary (back-nav, re-entry from Journal tap) skips the reveal
 * — user requested per-walk-once semantics, divergence from iOS which
 * replays on every entry.
 */
@Singleton
class SealRevealStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val source: Flow<Set<String>> = dataStore.data
        .catch { t ->
            if (t is CancellationException) throw t
            Log.w(TAG, "seal-reveal datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_REVEALED] ?: emptySet() }
        .distinctUntilChanged()

    suspend fun isRevealed(uuid: String): Boolean = source.first().contains(uuid)

    suspend fun markRevealed(uuid: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_REVEALED] ?: emptySet()
            prefs[KEY_REVEALED] = current + uuid
        }
    }

    private companion object {
        const val TAG = "SealRevealStore"
        val KEY_REVEALED = stringSetPreferencesKey("seal_revealed_walk_uuids")
    }
}
