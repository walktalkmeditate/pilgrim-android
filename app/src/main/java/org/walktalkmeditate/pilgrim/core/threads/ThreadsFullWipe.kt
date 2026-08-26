// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Internal, unit-tested full-wipe API (parity spec BEH-65/DAT-18/DAT-34).
 * Android has no user-facing Delete-All-Data surface yet — [WalkRepository]
 * exposes only per-walk/per-recording deletes — so this exists ready for
 * whichever future surface needs it, ships the exact ordering guarantee
 * iOS's `DataManager.deleteAll` uses, and is exercised directly by tests.
 *
 * Three-step order, matching iOS exactly: tombstone every known recording
 * uuid SYNCHRONOUSLY first, then sweep the store, then clear the moon-line
 * key. Tombstoning first closes the race where an analysis already queued
 * before the wipe would otherwise write a context file back after it —
 * [TranscriptContextStore.deleteAll] itself also tombstones every uuid it
 * recovers from FILENAMES on disk (DAT-25), so the two sources compose:
 * [recordingUuids] covers anything the caller's own snapshot knows about
 * (including a row about to be deleted whose context write hasn't landed
 * on disk yet), and the store's own filename-derived sweep covers
 * anything already-written that the caller's snapshot might have missed.
 *
 * What this does NOT clear, matching DAT-56 exactly:
 * [ThreadsPreferencesRepository.threadsAfterWalks] and
 * [ThreadsPreferencesRepository.importGeneration] both survive — a wipe is
 * not a preference reset.
 */
@Singleton
class ThreadsFullWipe @Inject constructor(
    private val store: TranscriptContextStore,
    private val preferences: ThreadsPreferencesRepository,
) {
    suspend fun wipe(recordingUuids: List<String>) {
        store.insertTombstones(recordingUuids)
        store.deleteAll()
        preferences.clearMoonLineIndex()
    }
}
