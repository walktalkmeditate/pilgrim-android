// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.data.collective.ContributionLedgerDataStore

/**
 * iOS parity `CollectiveContributionLog`
 * (`CollectiveContributionLog.swift@9a418e4`; spec
 * `docs/parity/2026-07-23-port-contribution-ledger-u4.md`).
 *
 * Remembers which walks actually moved the collective counter. The
 * summary's line is a claim about one walk's past, so it cannot read
 * the live contribution preference: toggling off would erase a true
 * line, on would fabricate one.
 *
 * Storage is the iOS-verbatim key holding a JSON-encoded,
 * insertion-ordered list of walk UUID strings — DataStore Preferences
 * has no ordered-array primitive, and a string set would break the
 * oldest-first eviction below. The store lives in its own DataStore
 * file: unlike the counter cache these entries are historical facts
 * that no fetch can reconstruct.
 */
@Singleton
class ContributionLedger @Inject constructor(
    @ContributionLedgerDataStore private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    suspend fun wasContributed(walkUuid: String): Boolean =
        walkUuid in decode(dataStore.data.first()[KEY_CONTRIBUTED_WALK_UUIDS])

    /**
     * Idempotent: a walk re-recorded after a retry keeps its original
     * position rather than evicting an unrelated walk. Read-decide-write
     * stays inside one `edit` block so concurrent recorders can't
     * interleave between the contains-check and the append.
     */
    suspend fun record(walkUuid: String) {
        dataStore.edit { prefs ->
            val uuids = decode(prefs[KEY_CONTRIBUTED_WALK_UUIDS])
            if (walkUuid in uuids) return@edit
            val appended = uuids + walkUuid
            prefs[KEY_CONTRIBUTED_WALK_UUIDS] =
                json.encodeToString(SERIALIZER, appended.takeLast(CAPACITY))
        }
    }

    private fun decode(blob: String?): List<String> {
        if (blob == null) return emptyList()
        return try {
            json.decodeFromString(SERIALIZER, blob)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    internal companion object {
        /**
         * iOS: "Roughly three years of daily walking. Past it the
         * oldest identifiers fall off and those summaries lose their
         * line — newest wins, as the journal reads recent-first."
         */
        const val CAPACITY = 1_000
        const val DATASTORE_NAME = "collective_contribution_log"
        val KEY_CONTRIBUTED_WALK_UUIDS = stringPreferencesKey("collectiveContributedWalkUUIDs")
        private val SERIALIZER = ListSerializer(String.serializer())
    }
}
