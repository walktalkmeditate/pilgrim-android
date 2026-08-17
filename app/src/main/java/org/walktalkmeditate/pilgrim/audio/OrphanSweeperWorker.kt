// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore

/**
 * Daily background worker that runs [OrphanRecordingSweeper.sweepAll]
 * and, since Phase 19, [SharePrepStore.sweepOrphans]. Catches the global
 * case of walks the user never opens — Stage 2-E's on-init sweep covers
 * walks the user views.
 *
 * The share-prep sweep rides here rather than on its own schedule
 * because it answers the same question against the same kind of keep
 * set: the transcode artifacts worth keeping are exactly those a walk
 * with a live repair record may still need to re-upload (port plan
 * Decision 3). Everything else — a walk whose Interactive toggle was
 * never turned off before the screen went away, an attempt abandoned
 * before it ever POSTed — is cache the OS could have evicted anyway.
 *
 * A record for a walk the walker has DELETED is the one case where
 * that keep set lies: nothing else ever clears such a record (the
 * clearing paths all live inside that walk's own share screen), so it
 * would protect the deleted walk's transcoded voice from every future
 * sweep. [ShareRepairStore.sweepStale] drops those against the live
 * walk list before the keep set is handed over.
 */
@HiltWorker
class OrphanSweeperWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sweeper: OrphanRecordingSweeper,
    private val sharePrepStore: SharePrepStore,
    private val shareRepairStore: ShareRepairStore,
    private val repository: WalkRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val result = sweeper.sweepAll()
        val liveWalkUuids = repository.allWalks().map { it.uuid }.toSet()
        val prepDirsRemoved = sharePrepStore.sweepOrphans(shareRepairStore.sweepStale(liveWalkUuids))
        Log.i(TAG, "sweepAll: $result, share-prep dirs removed: $prepDirsRemoved")
        Result.success()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        // Cooperate with WorkManager cancellation (constraints changed
        // mid-run, e.g., battery dropped). Without this re-throw the
        // exception would be swallowed and we'd request retry on a
        // worker the system was actively cancelling.
        throw cancel
    } catch (t: Throwable) {
        Log.w(TAG, "daily sweep failed", t)
        Result.retry()
    }

    private companion object { const val TAG = "OrphanSweeperWorker" }
}
