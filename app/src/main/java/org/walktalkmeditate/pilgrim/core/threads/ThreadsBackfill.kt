// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.walktalkmeditate.pilgrim.data.dao.TranscribedRecordingSnapshot

/**
 * One-time historical sweep over already-transcribed recordings — origin
 * claims ("first time", "where it began") are only true once history is
 * fully analyzed (parity spec: ThreadStore). Android port of iOS
 * `ThreadsBackfill.swift`; the pieces that don't transliterate are called
 * out on each type below.
 *
 * Everything in this file is used ONLY by the runner + the Worker/
 * Scheduler that wrap it — split across the same physical file (worker +
 * scheduler + setEnabled + state), matching iOS's single-`enum` shape.
 */
sealed interface ThreadsBackfillOutcome {
    /** Every snapshot item accounted for; the completed key was written. */
    data object Completed : ThreadsBackfillOutcome

    /** [ThreadsPreferencesRepository.threadsAfterWalks] was off — nothing to do, nothing to retry. */
    data object ToggleOff : ThreadsBackfillOutcome

    /** [BatteryGate] (or the toggle, rechecked per batch) closed before every item was seen. */
    data object GateClosed : ThreadsBackfillOutcome

    /** Every item was attempted but at least one genuinely failed to save. */
    data object Incomplete : ThreadsBackfillOutcome

    /** [ThreadsPreferencesRepository.importGeneration] changed mid-sweep — a fresh pass is required. */
    data object Stale : ThreadsBackfillOutcome
}

/**
 * The sweep engine — a plain, test-seamed suspend function (iOS parity
 * `runIfNeeded`'s `snapshotProvider`/`gate` defaults) rather than a bare
 * static namespace: Android's collaborators ([TranscriptContextStore],
 * [TranscriptContextAnalyzer], [ThreadsPreferencesRepository]) are Hilt
 * singletons, not iOS-style globally reachable statics, so there is no
 * static `ThreadsBackfill.runIfNeeded()` to port literally — this class
 * is what a `ThreadsBackfillWorker` calls, and what a unit test calls
 * directly with fake snapshot/gate lambdas.
 *
 * Single-flight is WorkManager's job (KEEP policy on the unique work
 * name), not this class's — unlike iOS's in-memory `isRunning` flag,
 * which exists only because iOS has no queue to dedupe concurrent
 * `runIfNeeded()` calls itself.
 */
@Singleton
class ThreadsBackfillRunner @Inject constructor(
    private val store: TranscriptContextStore,
    private val analyzer: TranscriptContextAnalyzer,
    private val preferences: ThreadsPreferencesRepository,
) {

    /**
     * @param snapshotProvider every already-transcribed recording across
     *   all walks in production ([org.walktalkmeditate.pilgrim.data.WalkRepository.transcribedRecordingsSnapshot],
     *   supplied by [ThreadsBackfillWorker] — this class deliberately
     *   holds no repository/Context of its own, matching iOS's
     *   `runIfNeeded(snapshotProvider:gate:)` test-seam shape); tests
     *   substitute a fixed list.
     * @param gate [BatteryGate.allowsBackgroundWork] in production
     *   (supplied by the caller, which holds the [Context] this class
     *   deliberately does not) — re-checked before every batch, matching
     *   iOS's per-25-item recheck (DAT-37/EDG-60), plus once at entry so
     *   a worker that wakes up already below the threshold does no work
     *   at all.
     */
    suspend fun sweep(
        snapshotProvider: suspend () -> List<TranscribedRecordingSnapshot>,
        gate: suspend () -> Boolean,
    ): ThreadsBackfillOutcome {
        if (!preferences.threadsAfterWalks.value) return ThreadsBackfillOutcome.ToggleOff

        val startGeneration = preferences.importGeneration.value
        val alreadyComplete = preferences.backfillCompletedAtVersion() == TranscriptContext.ANALYSIS_VERSION &&
            preferences.backfillCompletedAtImportGeneration() == startGeneration
        if (alreadyComplete) return ThreadsBackfillOutcome.Completed

        if (!gate()) return ThreadsBackfillOutcome.GateClosed

        val items = snapshotProvider().sortedBy { it.uuid }
        pruneStaleOrphans(items.map { it.uuid }.toSet())

        val checkpoint = preferences.backfillCheckpoint()
        val startIndex = if (checkpoint.forImportGeneration == startGeneration) checkpoint.processedCount else 0

        var allAccounted = true
        var gateClosed = false
        var lastCleanBoundary = startIndex
        var index = startIndex

        while (index < items.size) {
            if (!gate() || !preferences.threadsAfterWalks.value) {
                gateClosed = true
                break
            }
            val batchEnd = minOf(index + BATCH_SIZE, items.size)
            var batchClean = true
            for (i in index until batchEnd) {
                val item = items[i]
                if (!store.hasCurrentContext(item.uuid) && !accountFor(item)) {
                    allAccounted = false
                    batchClean = false
                }
            }
            index = batchEnd
            if (batchClean) lastCleanBoundary = index
            preferences.setBackfillCheckpoint(BackfillCheckpoint(lastCleanBoundary, startGeneration))
            yield()
        }

        val staleGeneration = preferences.importGeneration.value != startGeneration
        return when {
            gateClosed -> ThreadsBackfillOutcome.GateClosed
            staleGeneration -> ThreadsBackfillOutcome.Stale
            allAccounted -> {
                preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, startGeneration)
                preferences.clearBackfillCheckpoint()
                ThreadsBackfillOutcome.Completed
            }
            else -> ThreadsBackfillOutcome.Incomplete
        }
    }

    /**
     * `true` = accounted for (saved, tombstone-blocked, or correctly
     * skipped as non-English — see below); `false` = a genuine failure
     * this sweep must retry later.
     *
     * The extra [TranscriptContextAnalyzer.detectLanguage] call exists
     * because Android's English-only-v1 gate (unlike iOS, which stores
     * themes for every language and only nulls the markers field) writes
     * NOTHING for a non-English recording — that recording's
     * `hasCurrentContext` will never become true no matter how many times
     * the sweep retries it. Without this independent check, one
     * non-English recording anywhere in a user's history would keep
     * `allAccounted` false forever and the backfill would never
     * complete. Same duplicate-detection trade [TranscriptionRunner]
     * already accepts (a fast, local, no-network ML Kit call) — see
     * [TranscriptContextAnalyzer]'s own KDoc.
     */
    private suspend fun accountFor(item: TranscribedRecordingSnapshot): Boolean {
        val language = analyzer.detectLanguage(item.transcription)
        if (language != ENGLISH) return true
        return analyzer.analyzeAndStore(item.uuid, item.transcription) != null
    }

    /**
     * Stale-schema files whose recording isn't in this sweep's snapshot
     * are deleted outright (iOS parity: current-schema orphans are left
     * for `ThreadsDossierBuilder.build`'s own cleanup — U7). An empty
     * [liveUuids] is skipped outright: the snapshot query returning
     * empty is indistinguishable from a genuine read failure, and
     * treating it as proof of orphanhood would mass-delete every
     * stale-schema context still on disk for recordings that are, in
     * fact, live.
     */
    private suspend fun pruneStaleOrphans(liveUuids: Set<String>) {
        if (liveUuids.isEmpty()) return
        val staleOrphans = store.loadAllIncludingStaleVersions()
            .filter { it.analysisVersion != TranscriptContext.ANALYSIS_VERSION && it.uuid !in liveUuids }
            .map { it.uuid }
        if (staleOrphans.isEmpty()) return
        store.delete(staleOrphans)
    }

    private companion object {
        const val BATCH_SIZE = 25
        const val ENGLISH = "en"
    }
}
