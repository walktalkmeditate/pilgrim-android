// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.recovery

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/**
 * Persists the id of the most-recent walk that was auto-finalized
 * because the user swiped the app away from recents while a walk was in
 * progress. Cleared after the recovery banner is dismissed (auto-timer
 * or user-tap).
 *
 * Mirrors iOS `WalkSessionGuard.recoverIfNeeded` UX: when the OS kills
 * the app mid-walk (iOS swipe-from-recents, Android swipe-from-recents)
 * the walk is auto-finalized so it shows up in history, and a transient
 * banner on the Path tab acknowledges the recovery on next launch.
 *
 * The Android implementation is simpler than iOS's because Room writes
 * incrementally during a walk — we don't need iOS's JSON checkpoint
 * file. The walk row has the latest data already; we just flip its
 * `endTimestamp` from null to "now" and remember the id for the
 * banner.
 *
 * Interface so tests can supply an in-memory `MutableStateFlow`-backed
 * fake without standing up DataStore + a tmpfs.
 */
interface WalkRecoveryRepository {
    val recoveredWalkId: StateFlow<Long?>

    /**
     * Mark a walk as auto-finalized via the swipe-from-recents path.
     * Synchronous because the call site (`Service.onTaskRemoved`) runs
     * on the main thread and the OS may begin tearing down the process
     * within milliseconds.
     */
    fun markRecoveredBlocking(walkId: Long)

    /**
     * Clear the recovered-walk marker after the banner has been seen.
     */
    suspend fun clearRecovered()
}

@Singleton
class DataStoreWalkRecoveryRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @WalkRecoveryScope private val scope: CoroutineScope,
) : WalkRecoveryRepository {

    override val recoveredWalkId: StateFlow<Long?> = dataStore.data
        .catch { t ->
            Log.w(TAG, "datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_RECOVERED_WALK_ID] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    override fun markRecoveredBlocking(walkId: Long) {
        runBlocking {
            try {
                dataStore.edit { it[KEY_RECOVERED_WALK_ID] = walkId }
            } catch (t: Throwable) {
                Log.w(TAG, "markRecovered($walkId) failed", t)
            }
        }
    }

    override suspend fun clearRecovered() {
        try {
            dataStore.edit { it.remove(KEY_RECOVERED_WALK_ID) }
        } catch (t: Throwable) {
            Log.w(TAG, "clearRecovered failed", t)
        }
    }

    private companion object {
        const val TAG = "WalkRecovery"
        val KEY_RECOVERED_WALK_ID = longPreferencesKey("recovered_walk_id")
    }
}
