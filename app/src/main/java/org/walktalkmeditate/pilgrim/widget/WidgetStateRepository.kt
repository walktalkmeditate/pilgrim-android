// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * @Singleton DataStore wrapper for the home-screen widget's persisted
 * state. Single instance shared across all widget instances so a
 * lockscreen widget + home-screen widget render the same content.
 *
 * Decode failures (corrupted blob) emit Empty rather than throwing —
 * the widget renders the fallback mantra and the next finishWalk's
 * Worker write will overwrite the broken value cleanly.
 */
@Singleton
class WidgetStateRepository @Inject constructor(
    @WidgetDataStore private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val stateFlow: Flow<WidgetState> = dataStore.data
        .map { prefs -> prefs[KEY_STATE_JSON]?.let(::decode) ?: WidgetState.Empty }
        .distinctUntilChanged()

    suspend fun write(state: WidgetState) {
        val blob = json.encodeToString(WidgetState.serializer(), state)
        dataStore.edit { it[KEY_STATE_JSON] = blob }
    }

    private fun decode(blob: String): WidgetState? = try {
        json.decodeFromString(WidgetState.serializer(), blob)
    } catch (ce: CancellationException) {
        // Stage 5-C / 8-A audit rule: kotlin's runCatching swallows CE,
        // so we use explicit try/catch with re-throw.
        throw ce
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        const val DATASTORE_NAME = "widget_state"
        val KEY_STATE_JSON = stringPreferencesKey("state_json")
    }
}
