// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sharing

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
 * Persists the set of walk UUIDs whose user has shared (via Goshuin
 * image, Etegami image, OR Walk Share Journey). Used to gate the
 * Light Reading card on Walk Summary — iOS parity per
 * `WalkSummaryView.swift:86,132-134@db4196e` and
 * `WalkSharingTracker.swift`.
 *
 * Key string `"sharedWalkUUIDs"` matches iOS UserDefaults key for
 * cross-platform forensic clarity (storage layer differs — iOS uses
 * UserDefaults, Android uses DataStore Preferences — but the
 * contract is identical).
 */
@Singleton
class WalkSharingTracker @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val source: Flow<Set<String>> = dataStore.data
        .catch { t ->
            if (t is CancellationException) throw t
            Log.w(TAG, "walk-sharing datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SHARED] ?: emptySet() }
        .distinctUntilChanged()

    suspend fun hasShared(walkUuid: String): Boolean = source.first().contains(walkUuid)

    suspend fun markShared(walkUuid: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SHARED] ?: emptySet()
            prefs[KEY_SHARED] = current + walkUuid
        }
    }

    fun hasSharedFlow(walkUuid: String): Flow<Boolean> =
        source.map { it.contains(walkUuid) }.distinctUntilChanged()

    private companion object {
        const val TAG = "WalkSharingTracker"
        val KEY_SHARED = stringSetPreferencesKey("sharedWalkUUIDs")
    }
}
