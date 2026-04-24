// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PhotoAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: PhotoAnalysisRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L).takeIf { it > 0 }
            ?: return Result.failure()
        return runner.analyzePending(walkId).fold(
            onSuccess = { Result.success() },
            // ML Kit / DB failures bubble up here only for catastrophic
            // cases — the runner already tombstones per-photo failures
            // internally. Retry a bounded number of times to cover
            // transient issues (low memory, WorkManager constraint
            // flip, SD remount) without queueing unbounded retries
            // forever against a permanently broken state. After
            // [MAX_RETRY_ATTEMPTS], fall back to success so WorkManager
            // drops the request — runStartupSweep re-schedules on next
            // WalkSummary entry anyway, which is the right user-visible
            // recovery point.
            onFailure = {
                if (runAttemptCount >= MAX_RETRY_ATTEMPTS) Result.success()
                else Result.retry()
            },
        )
    }

    companion object {
        const val KEY_WALK_ID = "walk_id"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
