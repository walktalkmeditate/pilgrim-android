// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Registry of walks the user has marked as "released" via the web
 * editor. iOS parity v1.6.0 — `UserPreferences.archivedWalkRegistry`
 * (UUID → archivedAt epoch).
 *
 * Backed by the shared `pilgrim_prefs` DataStore. Stored as a single
 * JSON-encoded string so the registry round-trips through the same
 * Preferences mechanism the rest of the app uses, without introducing
 * Proto DataStore (which would force a schema-versioning policy for
 * one map). All reads/writes route through the registry's serial
 * Flow + edit lambda — no direct map mutation, no race window between
 * concurrent markers.
 *
 * Behavior:
 * - [snapshot] reads the current set of archived UUIDs synchronously
 *   from the eagerly-started StateFlow (`.value`).
 * - [isArchived] is the single-UUID convenience.
 * - [markArchived] / [unmarkArchived] write through `dataStore.edit`
 *   so concurrent callers serialize on DataStore's internal mutex.
 * - [clear] wipes the registry (post-`deleteAll` orphan cleanup).
 *
 * Errors during DataStore reads emit `emptyPreferences()` so a
 * transient I/O glitch doesn't pin the registry to a stale state. The
 * eager StateFlow recovers on the next successful read.
 */
/**
 * Test-friendly seam over [DataStoreArchivedWalkRegistry]. Production
 * binds this to the real impl via [ArchivedWalkRegistryModule].
 */
interface ArchivedWalkRegistry {
    val archivedRegistry: StateFlow<Map<String, Double>>
    fun isArchived(uuid: String): Boolean
    fun snapshot(): Map<String, Double>
    suspend fun markArchived(uuid: String, archivedAtEpoch: Double)
    suspend fun unmarkArchived(uuid: String)
    suspend fun clear()
}

@Singleton
class DataStoreArchivedWalkRegistry @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ArchivedWalkRegistryScope private val scope: CoroutineScope,
) : ArchivedWalkRegistry {
    private val json: Json = Json { ignoreUnknownKeys = true }

    override val archivedRegistry: StateFlow<Map<String, Double>> = dataStore.data
        .catch { t ->
            Log.w(TAG, "archived-registry read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { prefs ->
            val raw = prefs[KEY_REGISTRY] ?: return@map emptyMap()
            try {
                json.decodeFromString(StoredRegistry.serializer(), raw).entries
            } catch (t: Throwable) {
                Log.w(TAG, "archived-registry decode failed; treating as empty", t)
                emptyMap()
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun isArchived(uuid: String): Boolean = archivedRegistry.value.containsKey(uuid)

    override fun snapshot(): Map<String, Double> = archivedRegistry.value

    override suspend fun markArchived(uuid: String, archivedAtEpoch: Double) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_REGISTRY])
            current[uuid] = archivedAtEpoch
            prefs[KEY_REGISTRY] = encode(current)
        }
    }

    override suspend fun unmarkArchived(uuid: String) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_REGISTRY])
            if (current.remove(uuid) != null) {
                prefs[KEY_REGISTRY] = encode(current)
            }
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_REGISTRY) }
    }

    private fun decode(raw: String?): MutableMap<String, Double> {
        if (raw.isNullOrEmpty()) return mutableMapOf()
        return try {
            json.decodeFromString(StoredRegistry.serializer(), raw)
                .entries
                .toMutableMap()
        } catch (t: Throwable) {
            Log.w(TAG, "archived-registry decode failed in edit; resetting", t)
            mutableMapOf()
        }
    }

    private fun encode(entries: Map<String, Double>): String =
        json.encodeToString(StoredRegistry.serializer(), StoredRegistry(entries))

    @Serializable
    private data class StoredRegistry(val entries: Map<String, Double>)

    private companion object {
        const val TAG = "ArchivedWalkRegistry"
        val KEY_REGISTRY = stringPreferencesKey("archivedWalkRegistry")
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArchivedWalkRegistryScope
