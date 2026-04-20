// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

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
 * Persists the single currently-selected voice-guide pack id in
 * DataStore Preferences. Exposed as a [StateFlow] so UI surfaces
 * can observe selection changes reactively.
 *
 * Mirrors iOS's `UserPreferences.selectedVoiceGuidePackId` key — a
 * nullable string; null means "no pack selected." Matches iOS's
 * "first successful download auto-selects" flow via [selectIfUnset],
 * called by `VoiceGuideDownloadObserver` on terminal-success.
 */
@Singleton
class VoiceGuideSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @VoiceGuideSelectionScope private val scope: CoroutineScope,
) {
    val selectedPackId: StateFlow<String?> = dataStore.data
        .catch { t ->
            // Disk read failures (corrupt prefs file, temporary I/O)
            // shouldn't crash observers; emit empty so the repository
            // surfaces null and the next read retries. Matches the
            // resilience pattern in `HemisphereRepository` (Stage 3-D).
            Log.w(TAG, "selection datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SELECTED] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    suspend fun select(packId: String) {
        dataStore.edit { it[KEY_SELECTED] = packId }
    }

    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }

    /**
     * Atomic read-check-write inside a single `edit` block. TOCTOU-safe
     * for the "first download wins" flow — two concurrent completions
     * won't both claim the selection slot.
     */
    suspend fun selectIfUnset(packId: String) {
        dataStore.edit { prefs ->
            if (prefs[KEY_SELECTED] == null) {
                prefs[KEY_SELECTED] = packId
            }
        }
    }

    private companion object {
        const val TAG = "VoiceGuideSelection"
        val KEY_SELECTED = stringPreferencesKey("selected_voice_guide_pack_id")
    }
}
