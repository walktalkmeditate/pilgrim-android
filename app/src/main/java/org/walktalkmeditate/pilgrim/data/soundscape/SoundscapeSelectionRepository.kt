// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

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

/**
 * Persists the currently-selected soundscape asset id in DataStore
 * Preferences. Exposed as a [StateFlow] so UI surfaces can observe
 * selection changes reactively.
 *
 * Independent of voice-guide selection: user can have voice-guide
 * A + soundscape B, or just one, or neither. Mirrors iOS's
 * `UserPreferences.selectedSoundscapeId` key.
 *
 * **SharingStarted.Eagerly** (vs voice-guide's `WhileSubscribed(5s)`)
 * because [SoundscapeOrchestrator] reads `.value` synchronously
 * inside a non-suspend eligibility check — `.value` reads do NOT
 * count as StateFlow subscribers, so a `WhileSubscribed` flow would
 * return the initial `null` until someone explicitly `collect`s.
 * Voice-guide avoids this via the always-on `VoiceGuideDownloadObserver`
 * which keeps the catalog flow (and therefore the selection flow
 * underneath) warm for the app's lifetime. Soundscape has no
 * equivalent observer — we opted out of auto-select-on-first-
 * download (iOS doesn't either). `Eagerly` is safe with the
 * upstream `.catch { emit(emptyPreferences()) }` in place (Stage
 * 3-D lesson: without `.catch`, DataStore errors would terminate
 * the flow and `Eagerly` would never recover).
 *
 * Without this, a cold process restore into a Meditating state with
 * a persisted soundscape selection would silent-play — the
 * orchestrator's 800ms-delayed `.value` read would land before any
 * subscriber warmed DataStore.
 */
@Singleton
class SoundscapeSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @SoundscapeSelectionScope private val scope: CoroutineScope,
) {
    val selectedSoundscapeId: StateFlow<String?> = dataStore.data
        .catch { t ->
            Log.w(TAG, "selection datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SELECTED] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun select(assetId: String) {
        dataStore.edit { it[KEY_SELECTED] = assetId }
    }

    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }

    private companion object {
        const val TAG = "SoundscapeSelection"
        val KEY_SELECTED = stringPreferencesKey("selected_soundscape_id")
    }
}
