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
        .map { prefs ->
            // One-shot migration from the legacy snake_case key to the
            // iOS-faithful camelCase key. Read the new key first; if
            // absent and the legacy key has a value, fall back to it.
            // Writes go to the new key only (legacy key is cleared on
            // first write — see select / deselect). This keeps existing
            // users' selections live across the migration without a
            // separate migration job.
            prefs[KEY_SELECTED] ?: prefs[KEY_SELECTED_LEGACY]
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * **Migration contract — DO NOT remove the legacy-key cleanup.**
     *
     * Stage 10-B switched the storage key from snake_case
     * `"selected_soundscape_id"` to iOS-faithful camelCase
     * `"selectedSoundscapeId"`. The first `select()` after upgrade
     * writes the new key AND clears the legacy key so the user has
     * exactly one source of truth going forward. Removing the
     * `prefs.remove(KEY_SELECTED_LEGACY)` line would leave both keys
     * populated indefinitely; the `selectedSoundscapeId` flow's
     * fallback (`prefs[KEY_SELECTED] ?: prefs[KEY_SELECTED_LEGACY]`)
     * would still read correctly, but a future code change that
     * inverts the precedence (or another stage that introduces a
     * different read order) could silently surface stale data.
     *
     * One-way semantics are intentional: a downgrade past Stage 10-B
     * after the user has saved a new selection will lose that
     * selection. This is acceptable because (a) downgrades are
     * vanishingly rare on Android, (b) the .pilgrim ZIP round-trip
     * with iOS is more important than backward compatibility with
     * pre-10-B Android builds, and (c) the user can always re-pick
     * their soundscape post-downgrade.
     */
    suspend fun select(assetId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SELECTED] = assetId
            prefs.remove(KEY_SELECTED_LEGACY)
        }
    }

    suspend fun deselect() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SELECTED)
            prefs.remove(KEY_SELECTED_LEGACY)
        }
    }

    private companion object {
        const val TAG = "SoundscapeSelection"
        // iOS UserDefaults key — matches verbatim for cross-platform
        // .pilgrim ZIP round-trip. See iOS UserPreferences.swift line 51.
        val KEY_SELECTED = stringPreferencesKey("selectedSoundscapeId")
        // Legacy snake_case key from before Stage 10-B. Read on
        // migration so existing users don't lose their selection;
        // cleared on the next select / deselect.
        val KEY_SELECTED_LEGACY = stringPreferencesKey("selected_soundscape_id")
    }
}
