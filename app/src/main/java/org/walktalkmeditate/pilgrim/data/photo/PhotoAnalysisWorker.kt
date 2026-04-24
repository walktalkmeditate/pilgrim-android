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
            // ML Kit / DB failures are plausibly transient (low memory,
            // SD card remount, WorkManager constraint change). Ask for
            // a retry rather than marking analysis permanently failed;
            // per-photo failures inside the runner are already
            // tombstoned without bubbling.
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_WALK_ID = "walk_id"
    }
}
