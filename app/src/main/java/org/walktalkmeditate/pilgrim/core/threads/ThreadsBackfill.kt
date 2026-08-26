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
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

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

        // The no-speech placeholder is display text the transcription
        // runner commits for silent recordings and deliberately never
        // analyzes — those rows are accounted for by definition. Dropped
        // here (not per-item) so they can neither burn analysis on
        // placeholder text nor make a placeholder-only history read as
        // "recordings exist but zero contexts" to the distrust check.
        val spokenSnapshot: suspend () -> List<TranscribedRecordingSnapshot> = {
            snapshotProvider().filterNot { it.transcription == VoiceRecording.NO_SPEECH_PLACEHOLDER }
        }

        val startGeneration = preferences.importGeneration.value
        val alreadyComplete = preferences.backfillCompletedAtVersion() == TranscriptContext.ANALYSIS_VERSION &&
            preferences.backfillCompletedAtImportGeneration() == startGeneration
        if (alreadyComplete) {
            if (isCompletionTrustworthy(spokenSnapshot)) return ThreadsBackfillOutcome.Completed
            // D2D device-transfer carries this completion flag (a
            // DataStore file) but the transfer rules exclude
            // transcript_contexts/ (data_extraction_rules.xml —
            // recomputable derived data, never worth carrying) — a
            // migrated device can read "complete" with zero contexts ever
            // saved. Clear the completion flag AND the checkpoint (never
            // the toggle): a clean completion leaves no checkpoint behind,
            // but a kill inside an earlier completion write could — and a
            // surviving full-length checkpoint would make the "full sweep"
            // below resume past everything and re-stamp with zero
            // contexts. Then fall through into a real, full sweep.
            preferences.clearBackfillCompleted()
            preferences.clearBackfillCheckpoint()
        }

        if (!gate()) return ThreadsBackfillOutcome.GateClosed

        val items = spokenSnapshot().sortedBy { it.uuid }
        pruneStaleOrphans(items.map { it.uuid }.toSet())

        val checkpoint = preferences.backfillCheckpoint()
        val checkpointValid = checkpoint.forImportGeneration == startGeneration &&
            checkpoint.atAnalysisVersion == TranscriptContext.ANALYSIS_VERSION
        // Resume at the first uuid strictly past the watermark — the same
        // string order the sort above uses. Items deleted since the
        // checkpoint was written simply vanish from the list without
        // shifting anything still ahead of the watermark into the
        // "already done" prefix, which an integer index would.
        var watermark = if (checkpointValid) checkpoint.watermark else null
        val startIndex = watermark?.let { mark ->
            items.indexOfFirst { it.uuid > mark }.takeIf { it >= 0 } ?: items.size
        } ?: 0

        var allAccounted = true
        var gateClosed = false
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
            // allAccounted is never reset back to true once a batch fails
            // it, so requiring it here (not just this batch's OWN
            // batchClean) pins the watermark at the last batch that was
            // clean AND every batch before it was too — a later clean
            // batch must never advance the checkpoint past an EARLIER
            // batch's still-unaccounted-for item, or a retry would resume
            // past the failure and this sweep's own eventual completion
            // stamp would be recorded without it.
            if (batchClean && allAccounted) watermark = items[index - 1].uuid
            preferences.setBackfillCheckpoint(BackfillCheckpoint(watermark, startGeneration, TranscriptContext.ANALYSIS_VERSION))
            yield()
        }

        val staleGeneration = preferences.importGeneration.value != startGeneration
        val outcome = when {
            gateClosed -> ThreadsBackfillOutcome.GateClosed
            staleGeneration -> ThreadsBackfillOutcome.Stale
            allAccounted -> {
                // Checkpoint first: a kill between these two writes must
                // leave "incomplete + resumable", never "complete + full
                // checkpoint" — that shape survives a D2D transfer and
                // makes the distrust re-sweep above resume past everything
                // and re-stamp with zero contexts.
                preferences.clearBackfillCheckpoint()
                preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, startGeneration)
                ThreadsBackfillOutcome.Completed
            }
            else -> ThreadsBackfillOutcome.Incomplete
        }
        // First-activation QA signal — outcome + counts only, never uuids
        // or transcript data. Failures already log in the worker.
        Log.i(TAG, "sweep finished: $outcome (${items.size} snapshot items, resumed at $startIndex, stopped at $index)")
        return outcome
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
     *
     * A collaborator throw is caught here and reported as that same
     * per-item `false` — letting it propagate would turn one poison item
     * into a whole-sweep WorkManager retry that re-hits the same throw
     * forever, stalling every item behind it.
     */
    private suspend fun accountFor(item: TranscribedRecordingSnapshot): Boolean {
        return try {
            val language = analyzer.detectLanguage(item.transcription)
            if (language != ENGLISH) return true
            analyzer.analyzeAndStore(item.uuid, item.transcription) != null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // uuid only — never the transcript or detected language.
            Log.w(TAG, "analysis failed for recording ${item.uuid}", t)
            false
        }
    }

    /**
     * Whether a recorded completion should still be trusted. `false` only
     * when [store] confidently reports zero saved contexts (an empty list,
     * not the `null` "couldn't read the directory" signal) WHILE the
     * snapshot has recordings that ought to have produced some — the D2D
     * transfer shape (see the [sweep] call site). A `null` from [store]
     * is a genuine read failure, never proof of anything: mass-distrusting
     * a real completion on a transient read error would re-run a full
     * sweep for every user who happens to hit it, matching
     * [pruneStaleOrphans]'s own "an empty/failed read is not proof of
     * universal orphanhood" principle.
     *
     * Android-original hardening: iOS at the pin shares this hole (its
     * completion key migrates with system backup while its context files
     * are backup-excluded) and ships no equivalent distrust check.
     */
    private suspend fun isCompletionTrustworthy(
        snapshotProvider: suspend () -> List<TranscribedRecordingSnapshot>,
    ): Boolean {
        val storedUuids = store.allUuids()
        if (storedUuids == null || storedUuids.isNotEmpty()) return true
        return snapshotProvider().isEmpty()
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
        const val TAG = "ThreadsBackfill"
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
