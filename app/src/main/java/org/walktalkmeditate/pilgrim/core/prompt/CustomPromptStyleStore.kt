// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * DataStore-Preferences-backed store for user-defined [CustomPromptStyle]s.
 *
 * Mirrors iOS `CustomPromptStyleStore` (UserDefaults JSON-array under
 * key `"CustomPromptStyles"`):
 *  - `save(style)` replaces by id when present, otherwise appends if
 *    under the [MAX_STYLES] cap. The cap silently drops the 4th add —
 *    matches iOS `guard canAddMore else { return }`.
 *  - `delete(style)` removes any entries matching the id (no-op for
 *    unknown ids).
 *  - Decode failures (corrupt persisted JSON) log a warning and emit
 *    an empty list rather than crashing.
 *
 * `Eagerly` sharing matches the appearance + voice-guide repos so
 * `.value` reads from non-suspend code paths see persisted data
 * without waiting on a subscriber.
 */
@Singleton
class CustomPromptStyleStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    @CustomPromptStyleScope private val scope: CoroutineScope,
) {
    val styles: StateFlow<List<CustomPromptStyle>> = dataStore.data
        .catch { t ->
            Log.w(TAG, "custom prompt styles datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { prefs -> decode(prefs[KEY_STYLES]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun save(style: CustomPromptStyle) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_STYLES])
            val existingIndex = current.indexOfFirst { it.id == style.id }
            val updated = when {
                existingIndex >= 0 -> current.toMutableList().apply { this[existingIndex] = style }
                current.size < MAX_STYLES -> current + style
                else -> current
            }
            prefs[KEY_STYLES] = json.encodeToString(LIST_SERIALIZER, updated)
        }
    }

    suspend fun delete(style: CustomPromptStyle) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_STYLES])
            val updated = current.filterNot { it.id == style.id }
            prefs[KEY_STYLES] = json.encodeToString(LIST_SERIALIZER, updated)
        }
    }

    private fun decode(raw: String?): List<CustomPromptStyle> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(LIST_SERIALIZER, raw)
        } catch (t: SerializationException) {
            Log.w(TAG, "custom prompt styles decode failed; defaulting to empty", t)
            emptyList()
        }
    }

    private companion object {
        const val MAX_STYLES = 3
        const val TAG = "CustomPromptStore"
        val KEY_STYLES = stringPreferencesKey("custom_prompt_styles")
        val LIST_SERIALIZER = ListSerializer(CustomPromptStyle.serializer())
    }
}
