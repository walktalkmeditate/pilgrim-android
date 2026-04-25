// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

@Singleton
class CollectiveCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val statsFlow: Flow<CollectiveStats?> =
        context.collectiveDataStore.data
            .map { prefs -> prefs[KEY_STATS_JSON]?.let(::decodeStats) }
            .distinctUntilChanged()

    val lastFetchedAtFlow: Flow<Long?> =
        context.collectiveDataStore.data
            .map { it[KEY_LAST_FETCHED_AT] }
            .distinctUntilChanged()

    val optInFlow: Flow<Boolean> =
        context.collectiveDataStore.data
            .map { it[KEY_OPT_IN] ?: false }
            .distinctUntilChanged()

    val pendingFlow: Flow<CollectiveCounterDelta> =
        context.collectiveDataStore.data
            .map { prefs ->
                prefs[KEY_PENDING_JSON]?.let(::decodeDelta) ?: CollectiveCounterDelta()
            }
            .distinctUntilChanged()

    suspend fun writeStats(stats: CollectiveStats, fetchedAtMs: Long) {
        val blob = json.encodeToString(CollectiveStats.serializer(), stats)
        context.collectiveDataStore.edit { prefs ->
            prefs[KEY_STATS_JSON] = blob
            prefs[KEY_LAST_FETCHED_AT] = fetchedAtMs
        }
    }

    suspend fun invalidateLastFetched() {
        context.collectiveDataStore.edit { prefs -> prefs.remove(KEY_LAST_FETCHED_AT) }
    }

    suspend fun setOptIn(value: Boolean) {
        context.collectiveDataStore.edit { prefs -> prefs[KEY_OPT_IN] = value }
    }

    suspend fun mutatePending(
        mutate: (CollectiveCounterDelta) -> CollectiveCounterDelta,
    ): CollectiveCounterDelta {
        var result = CollectiveCounterDelta()
        context.collectiveDataStore.edit { prefs ->
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

    private companion object {
        val KEY_STATS_JSON = stringPreferencesKey("cached_stats_json")
        val KEY_LAST_FETCHED_AT = longPreferencesKey("last_fetched_at_ms")
        val KEY_OPT_IN = booleanPreferencesKey("opt_in")
        val KEY_PENDING_JSON = stringPreferencesKey("pending_delta_json")
    }
}

private val Context.collectiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "collective_counter",
)
