// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.walktalkmeditate.pilgrim.di.VoiceGuideProgressDataStore

/**
 * Per-walk persistence for [VoiceGuideScheduler]'s `played` set.
 *
 * **Why this exists.** The orchestrator's scheduler tracks which
 * prompts it has played during the current walk session in an
 * in-memory `Set<String>`. iOS-parity behavior: every walk starts
 * fresh at the first settling-phase prompt. Under the `:tracker`
 * process split, the UI process can be o-killed mid-walk while
 * tracker survives. UI restart re-spawns the orchestrator with an
 * empty `played` set → the next selected prompt is the opening one
 * the user just heard pre-kill. Multi-hour walks under RAM
 * pressure compound this into the same opening prompt repeating
 * every 25-30 min.
 *
 * Persisting the `played` set per active walk lets the restarted
 * orchestrator pick up where the prior one left off.
 */
interface VoiceGuideProgressRepository {
    /**
     * Load the persisted played-prompt set for [walkId]. Returns the
     * stored set if a prior session for the same walk recorded
     * progress; returns an empty set when no prior progress (or the
     * stored progress is for a different walk).
     */
    suspend fun load(walkId: Long): Set<String>

    /**
     * Record [promptId] as played for [walkId]. Atomic — appends to
     * the existing set if walk matches, or starts a fresh set scoped
     * to [walkId] if storage was empty / from a prior walk.
     */
    suspend fun markPlayed(walkId: Long, promptId: String)

    /**
     * Clear persisted progress. Called on walk finish / discard so
     * the next walk starts with no carryover.
     */
    suspend fun clear()

    /**
     * No-op implementation for unit tests that don't care about
     * persistence behavior. Always returns an empty set; never
     * persists anything.
     */
    object NoOp : VoiceGuideProgressRepository {
        override suspend fun load(walkId: Long): Set<String> = emptySet()
        override suspend fun markPlayed(walkId: Long, promptId: String) = Unit
        override suspend fun clear() = Unit
    }
}

@Singleton
class DataStoreVoiceGuideProgressRepository @Inject constructor(
    @VoiceGuideProgressDataStore private val dataStore: DataStore<Preferences>,
) : VoiceGuideProgressRepository {

    override suspend fun load(walkId: Long): Set<String> {
        val prefs = dataStore.data.first()
        val storedWalkId = prefs[KEY_WALK_ID]
        return if (storedWalkId == walkId) {
            prefs[KEY_PLAYED_IDS] ?: emptySet()
        } else {
            // Stale state from a previous walk. Purge so future
            // markPlayed calls land on a clean slate.
            dataStore.edit { it.clear() }
            emptySet()
        }
    }

    override suspend fun markPlayed(walkId: Long, promptId: String) {
        dataStore.edit { prefs ->
            val prior = if (prefs[KEY_WALK_ID] == walkId) {
                prefs[KEY_PLAYED_IDS] ?: emptySet()
            } else {
                emptySet()
            }
            prefs[KEY_WALK_ID] = walkId
            prefs[KEY_PLAYED_IDS] = prior + promptId
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_WALK_ID = longPreferencesKey("walk_id")
        val KEY_PLAYED_IDS = stringSetPreferencesKey("played_ids")
    }
}
