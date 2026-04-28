// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.di.VoicePreferencesDataStore

/**
 * DataStore-backed [VoicePreferencesRepository].
 *
 * Storage keys match iOS UserDefaults verbatim (`voiceGuideEnabled`,
 * `autoTranscribe`) so a `.pilgrim` ZIP round-trips between platforms.
 *
 * Eagerly-started StateFlows so consumers (orchestrator, walk-finalize observer)
 * can read `.value` synchronously from background contexts.
 */
@Singleton
class DataStoreVoicePreferencesRepository @Inject constructor(
    @VoicePreferencesDataStore private val dataStore: DataStore<Preferences>,
    @VoicePreferencesScope private val scope: CoroutineScope,
) : VoicePreferencesRepository {

    override val voiceGuideEnabled: StateFlow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[VOICE_GUIDE_ENABLED] ?: DEFAULT_VOICE_GUIDE_ENABLED }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_VOICE_GUIDE_ENABLED)

    override val autoTranscribe: StateFlow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[AUTO_TRANSCRIBE] ?: DEFAULT_AUTO_TRANSCRIBE }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_AUTO_TRANSCRIBE)

    init {
        // Stage 10-D upgrade migration: see runAutoTranscribeMigrationIfNeeded kdoc.
        // Fire-and-forget on the repo scope. Idempotent — re-checks the key
        // inside `dataStore.edit` so concurrent setters don't get clobbered.
        scope.launch { runAutoTranscribeMigrationIfNeeded() }
    }

    override suspend fun setVoiceGuideEnabled(enabled: Boolean) {
        dataStore.edit { it[VOICE_GUIDE_ENABLED] = enabled }
    }

    override suspend fun setAutoTranscribe(enabled: Boolean) {
        dataStore.edit { it[AUTO_TRANSCRIBE] = enabled }
    }

    /**
     * Stage 10-D autoTranscribe migration. Android currently auto-transcribes every
     * recording (via WalkFinalizationObserver). iOS default is false. To preserve
     * existing-user behavior on upgrade while matching iOS for fresh installs:
     *
     * - If `autoTranscribe` key is already present → no-op (user has expressed a
     *   preference, or migration already ran).
     * - Else if any pre-existing user-pref key is detected → seed `autoTranscribe = true`.
     * - Else → seed `autoTranscribe = false` (fresh install, iOS parity).
     *
     * The migration is TOCTOU-safe by running inside `dataStore.edit { }` and re-checking
     * the key inside the transaction.
     */
    private suspend fun runAutoTranscribeMigrationIfNeeded() {
        val current = dataStore.data.first()
        if (current.contains(AUTO_TRANSCRIBE)) return
        val isUpgrade = UPGRADE_PROBE_BOOL_KEYS.any { current.contains(it) } ||
            UPGRADE_PROBE_STRING_KEYS.any { current.contains(it) }
        dataStore.edit { prefs ->
            // Re-check inside the transaction in case a concurrent setter wrote first.
            if (!prefs.contains(AUTO_TRANSCRIBE)) {
                prefs[AUTO_TRANSCRIBE] = isUpgrade
            }
        }
    }

    private companion object {
        // Verbatim iOS UserDefaults keys for `.pilgrim` ZIP cross-platform parity.
        val VOICE_GUIDE_ENABLED = booleanPreferencesKey("voiceGuideEnabled")
        val AUTO_TRANSCRIBE = booleanPreferencesKey("autoTranscribe")
        const val DEFAULT_VOICE_GUIDE_ENABLED = false
        const val DEFAULT_AUTO_TRANSCRIBE = false

        // Upgrade probe keys — written by previous stages.
        val UPGRADE_PROBE_BOOL_KEYS = listOf(
            booleanPreferencesKey("soundsEnabled"),                 // Stage 10-B
            booleanPreferencesKey("beginWithIntention"),            // Stage 10-C
            booleanPreferencesKey("celestialAwarenessEnabled"),     // Stage 10-C
            booleanPreferencesKey("walkReliquaryEnabled"),          // Stage 10-C
        )
        val UPGRADE_PROBE_STRING_KEYS = listOf(
            stringPreferencesKey("appearance_mode"),                 // Stage 9.5-E (snake_case in DataStore)
            stringPreferencesKey("selected_voice_guide_pack_id"),    // Stage 5-D pre-rename
            stringPreferencesKey("selectedVoiceGuidePackId"),        // post-rename
            stringPreferencesKey("zodiacSystem"),                    // Stage 10-C
            stringPreferencesKey("distanceUnits"),                   // Stage 10-C
        )
    }
}
