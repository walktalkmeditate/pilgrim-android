// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import android.util.Log
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
import org.walktalkmeditate.pilgrim.data.WalkRepository
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

/**
 * The activation surface: [ensureScheduled] is the "schedule/run entry
 * point" the app-launch seam calls unconditionally on every process
 * start (iOS parity `MainCoordinator.init()`'s `Task { @MainActor in
 * ThreadsBackfill.runIfNeeded() }`) — this class's OWN internal guards
 * (via [ThreadsBackfillRunner.sweep]'s toggle/completion/gate checks
 * inside [ThreadsBackfillWorker]) decide whether real work happens, not
 * the caller. [setEnabled] is the Settings-toggle entry point (VoiceCard
 * routes through it — U10): unlike every sibling toggle, it owns the
 * reset-and-resweep-on-enable side effect so a toggle off→on doesn't
 * strand analysis gaps from the off period.
 */
interface ThreadsBackfillScheduler {
    fun ensureScheduled()
    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class WorkManagerThreadsBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ThreadsPreferencesRepository,
) : ThreadsBackfillScheduler {

    /**
     * Plain (not expedited) `OneTimeWorkRequest` + `BatteryNotLow` +
     * KEEP policy — Stage 2-F's crash class is Expedited+BatteryNotLow;
     * this request is deliberately the other shape. KEEP is
     * [ThreadsBackfillRunner]'s single-flight mechanism: a redundant
     * launch-time call while a sweep is already enqueued/running is a
     * no-op rather than restarting it.
     */
    override fun ensureScheduled() {
        val request = OneTimeWorkRequestBuilder<ThreadsBackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * iOS parity `ThreadsBackfill.setEnabled` — the resweep-on-enable
     * side effect belongs with the preference flip, not left for a
     * caller to remember. Disabling only writes the preference: the
     * completed flag survives (matching iOS's `reset()` never being
     * called on the disable path), and there is no periodic re-schedule
     * to suppress — [ensureScheduled] is only ever called from app
     * launch or from this same re-enable branch, so "toggle-off stops
     * future scheduling" holds without an explicit cancel.
     */
    override suspend fun setEnabled(enabled: Boolean) {
        preferences.setThreadsAfterWalks(enabled)
        if (enabled) {
            preferences.clearBackfillCompleted()
            preferences.clearBackfillCheckpoint()
            ensureScheduled()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "threads-backfill"
    }
}

/**
 * Maps [ThreadsBackfillRunner.sweep]'s outcome to a WorkManager
 * [androidx.work.ListenableWorker.Result]. [ThreadsBackfillOutcome.GateClosed]
 * covers BOTH the worker run-start battery re-check (below 20% returns
 * `Result.retry()` immediately, before any batch runs) and a mid-sweep
 * closure — `BatteryNotLow`'s system floor (~15%) admits runs the 20%
 * gate refuses, so the 15-20% band is a real path this mapping exercises
 * in production, not just in tests.
 */
@HiltWorker
class ThreadsBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: ThreadsBackfillRunner,
    private val repository: WalkRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = try {
            runner.sweep(
                snapshotProvider = { repository.transcribedRecordingsSnapshot() },
                gate = { BatteryGate.allowsBackgroundWork(applicationContext) },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "sweep failed", t)
            return Result.retry()
        }
        return when (outcome) {
            ThreadsBackfillOutcome.Completed, ThreadsBackfillOutcome.ToggleOff -> Result.success()
            ThreadsBackfillOutcome.GateClosed,
            ThreadsBackfillOutcome.Incomplete,
            ThreadsBackfillOutcome.Stale,
            -> Result.retry()
        }
    }

    private companion object {
        const val TAG = "ThreadsBackfillWorker"
    }
}
