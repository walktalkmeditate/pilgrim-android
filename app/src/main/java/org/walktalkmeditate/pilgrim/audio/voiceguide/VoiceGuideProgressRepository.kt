// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.walktalkmeditate.pilgrim.di.VoiceGuideProgressDataStore

/**
 * Per-pack persistence for [VoiceGuideScheduler]'s `played` set.
 *
 * **iOS parity (`VoiceGuideManagement.loadHistory` /
 * `persistHistory`, `Audio/voiceguide/history.json`).** iOS persists
 * the played-prompt set keyed by **pack id**, NOT by walk. The
 * scheduler for walk N + 1 with the same pack loads walk N's played
 * set and skips those prompts, so every walk opens with a different
 * prompt until the pack is exhausted — then the scheduler resets in
 * memory and persistence shrinks back to the freshly-played set on
 * the next [save]. An earlier Android implementation keyed by walkId
 * and **cleared on Finished**, which made every walk re-open at the
 * same `seq=1` prompt — the parity bug this rewrite closes.
 *
 * **Mid-walk-restart survival.** A UI process o-kill mid-walk still
 * lands in the same pack's persisted set on restart, so the new
 * orchestrator skips whatever was already played this walk and does
 * not double-play the opening prompt.
 *
 * **Snapshot semantics.** [save] OVERWRITES the stored set with the
 * scheduler's current in-memory snapshot. This matters because the
 * scheduler clears `played` in memory when it cycles through every
 * prompt; an incremental append would leave the stored set stuck at
 * full-exhausted forever after one cycle.
 */
interface VoiceGuideProgressRepository {
    /**
     * Load the persisted played-prompt set for [packId]. Returns an
     * empty set when no progress has been stored for that pack yet.
     */
    suspend fun load(packId: String): Set<String>

    /**
     * Overwrite the stored played-set for [packId] with [played]. iOS
     * parity: snapshot semantics — the stored set always equals the
     * scheduler's current in-memory state, including post-cycle shrinkage.
     */
    suspend fun save(packId: String, played: Set<String>)

    /**
     * No-op implementation for unit tests that don't care about
     * persistence behavior. Always returns an empty set; never saves.
     */
    object NoOp : VoiceGuideProgressRepository {
        override suspend fun load(packId: String): Set<String> = emptySet()
        override suspend fun save(packId: String, played: Set<String>) = Unit
    }
}

@Singleton
class DataStoreVoiceGuideProgressRepository @Inject constructor(
    @VoiceGuideProgressDataStore private val dataStore: DataStore<Preferences>,
) : VoiceGuideProgressRepository {

    override suspend fun load(packId: String): Set<String> {
        return dataStore.data.first()[keyFor(packId)] ?: emptySet()
    }

    override suspend fun save(packId: String, played: Set<String>) {
        dataStore.edit { it[keyFor(packId)] = played }
    }

    private fun keyFor(packId: String) =
        stringSetPreferencesKey("played_ids_$packId")
}
