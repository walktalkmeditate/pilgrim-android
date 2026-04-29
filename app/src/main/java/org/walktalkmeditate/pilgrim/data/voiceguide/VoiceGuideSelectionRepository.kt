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
import kotlinx.coroutines.launch

/**
 * Persists the single currently-selected voice-guide pack id in
 * DataStore Preferences. Exposed as a [StateFlow] so UI surfaces
 * can observe selection changes reactively.
 *
 * Mirrors iOS's `UserPreferences.selectedVoiceGuidePackId` key — a
 * nullable string; null means "no pack selected." Matches iOS's
 * "first successful download auto-selects" flow via [selectIfUnset],
 * called by `VoiceGuideDownloadObserver` on terminal-success.
 *
 * **SharingStarted.Eagerly** is load-bearing.
 * [VoiceGuideOrchestrator.eligiblePackOrNullSync] reads
 * `selectedPackId.value` synchronously inside a non-suspend
 * eligibility check. `.value` reads do NOT count as StateFlow
 * subscribers, so a `WhileSubscribed` window during a brief
 * unsubscription would surface the initial `null` and the
 * orchestrator would silently skip a spawn. The Stage 5-G QA pass
 * called this out as "a stale-cache trap for NAVIGATION observers
 * after a long composition pause." `Eagerly` is safe with the
 * upstream `.catch { emit(emptyPreferences()) }` in place — without
 * `.catch`, DataStore errors would terminate the flow and `Eagerly`
 * would never recover (Stage 3-D lesson).
 *
 * **Key migration (Stage 10-D).** Storage key was renamed from
 * snake_case `selected_voice_guide_pack_id` to iOS-faithful camelCase
 * `selectedVoiceGuidePackId` for `.pilgrim` ZIP cross-platform
 * round-trip parity (iOS UserDefaults uses camelCase). Existing users
 * keep their selection via:
 *   - read-side fallback in the flow's `.map` (`new ?: legacy`)
 *   - one-time `migrateLegacyKeyIfNeeded()` triggered from `init`
 *     that copies legacy → new when only the legacy key is present
 *
 * The legacy key is intentionally NOT removed — leaving it in place
 * keeps a downgrade past Stage 10-D functional. A future cleanup
 * stage can prune it once Stage 10-D adoption is universal.
 */
@Singleton
class VoiceGuideSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @VoiceGuideSelectionScope private val scope: CoroutineScope,
) {
    init {
        scope.launch { migrateLegacyKeyIfNeeded() }
    }

    val selectedPackId: StateFlow<String?> = dataStore.data
        .catch { t ->
            Log.w(TAG, "selection datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { prefs -> prefs[KEY_SELECTED] ?: prefs[KEY_SELECTED_LEGACY] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun select(packId: String) {
        dataStore.edit { it[KEY_SELECTED] = packId }
    }

    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }

    /**
     * Atomic read-check-write inside a single `edit` block. TOCTOU-safe
     * for the "first download wins" flow — two concurrent completions
     * won't both claim the selection slot. Treats the legacy key as
     * occupied so an upgrading user with a legacy selection isn't
     * overwritten by an auto-select that races migration in `init`.
     */
    suspend fun selectIfUnset(packId: String) {
        dataStore.edit { prefs ->
            if (prefs[KEY_SELECTED] == null && prefs[KEY_SELECTED_LEGACY] == null) {
                prefs[KEY_SELECTED] = packId
            }
        }
    }

    private suspend fun migrateLegacyKeyIfNeeded() {
        dataStore.edit { prefs ->
            val legacy = prefs[KEY_SELECTED_LEGACY]
            val current = prefs[KEY_SELECTED]
            if (legacy != null && current == null) {
                prefs[KEY_SELECTED] = legacy
            }
        }
    }

    private companion object {
        const val TAG = "VoiceGuideSelection"
        val KEY_SELECTED = stringPreferencesKey("selectedVoiceGuidePackId")
        val KEY_SELECTED_LEGACY = stringPreferencesKey("selected_voice_guide_pack_id")
    }
}
