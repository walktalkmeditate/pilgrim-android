// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.intention

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.data.intention.IntentionHistoryRepository.Companion.MAX_INTENTIONS

/**
 * DataStore-backed [IntentionHistoryRepository]. Eagerly starts the
 * StateFlow so the intention sheet can read `.value` synchronously.
 * Same architecture as [org.walktalkmeditate.pilgrim.data.practice.DataStorePracticePreferencesRepository].
 *
 * Storage key `IntentionHistory` + JSON `[String]` payload match iOS
 * `IntentionHistoryStore` verbatim for cross-platform `.pilgrim`
 * round-trip.
 */
@Singleton
class DataStoreIntentionHistoryRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @IntentionHistoryScope private val scope: CoroutineScope,
) : IntentionHistoryRepository {

    override val intentions: StateFlow<List<String>> = dataStore.data
        .catch { t ->
            Log.w(TAG, "intention-history datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { decode(it[KEY_INTENTION_HISTORY]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun add(intention: String) {
        val trimmed = intention.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_INTENTION_HISTORY])
            val next = (listOf(trimmed) + current.filterNot { it == trimmed })
                .take(MAX_INTENTIONS)
            prefs[KEY_INTENTION_HISTORY] = JSON.encodeToString(LIST_SERIALIZER, next)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY_INTENTION_HISTORY) }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching { JSON.decodeFromString(LIST_SERIALIZER, raw) }
            .getOrElse {
                Log.w(TAG, "intention-history decode failed; treating as empty", it)
                emptyList()
            }
    }

    private companion object {
        const val TAG = "IntentionHistory"
        // iOS UserDefaults key — verbatim for .pilgrim ZIP round-trip.
        val KEY_INTENTION_HISTORY = stringPreferencesKey("IntentionHistory")
        val JSON = Json { ignoreUnknownKeys = true }
        val LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
