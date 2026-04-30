// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Singleton
class CollectiveCacheStore @Inject constructor(
    @CollectiveDataStore private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val statsFlow: Flow<CollectiveStats?> =
        dataStore.data
            .map { prefs -> prefs[KEY_STATS_JSON]?.let(::decodeStats) }
            .distinctUntilChanged()

    val lastFetchedAtFlow: Flow<Long?> =
        dataStore.data
            .map { it[KEY_LAST_FETCHED_AT] }
            .distinctUntilChanged()

    val optInFlow: Flow<Boolean> =
        dataStore.data
            .map { it[KEY_OPT_IN] ?: false }
            .distinctUntilChanged()

    val pendingFlow: Flow<CollectiveCounterDelta> =
        dataStore.data
            .map { prefs ->
                prefs[KEY_PENDING_JSON]?.let(::decodeDelta) ?: CollectiveCounterDelta()
            }
            .distinctUntilChanged()

    val lastSeenCollectiveWalksFlow: Flow<Int> =
        dataStore.data
            .map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }
            .distinctUntilChanged()

    suspend fun writeStats(stats: CollectiveStats, fetchedAtMs: Long) {
        val blob = json.encodeToString(CollectiveStats.serializer(), stats)
        dataStore.edit { prefs ->
            prefs[KEY_STATS_JSON] = blob
            prefs[KEY_LAST_FETCHED_AT] = fetchedAtMs
        }
    }

    suspend fun invalidateLastFetched() {
        dataStore.edit { prefs -> prefs.remove(KEY_LAST_FETCHED_AT) }
    }

    suspend fun setOptIn(value: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_OPT_IN] = value }
    }

    suspend fun firstReadyLastSeenCollectiveWalks(): Int =
        dataStore.data.map { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] ?: 0 }.first()

    suspend fun setLastSeenCollectiveWalks(value: Int) {
        dataStore.edit { it[KEY_LAST_SEEN_COLLECTIVE_WALKS] = value }
    }

    suspend fun mutatePending(
        mutate: (CollectiveCounterDelta) -> CollectiveCounterDelta,
    ): CollectiveCounterDelta {
        var result = CollectiveCounterDelta()
        dataStore.edit { prefs ->
            val current = prefs[KEY_PENDING_JSON]?.let(::decodeDelta) ?: CollectiveCounterDelta()
            val next = mutate(current)
            result = next
            if (next.isEmpty()) {
                prefs.remove(KEY_PENDING_JSON)
            } else {
                prefs[KEY_PENDING_JSON] = json.encodeToString(
                    CollectiveCounterDelta.serializer(),
                    next,
                )
            }
        }
        return result
    }

    private fun decodeStats(blob: String): CollectiveStats? = try {
        json.decodeFromString(CollectiveStats.serializer(), blob)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    private fun decodeDelta(blob: String): CollectiveCounterDelta? = try {
        json.decodeFromString(CollectiveCounterDelta.serializer(), blob)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        const val DATASTORE_NAME = "collective_counter"
        val KEY_STATS_JSON = stringPreferencesKey("cached_stats_json")
        val KEY_LAST_FETCHED_AT = longPreferencesKey("last_fetched_at_ms")
        val KEY_OPT_IN = booleanPreferencesKey("opt_in")
        val KEY_PENDING_JSON = stringPreferencesKey("pending_delta_json")
        val KEY_LAST_SEEN_COLLECTIVE_WALKS = intPreferencesKey("lastSeenCollectiveWalks")
    }
}
