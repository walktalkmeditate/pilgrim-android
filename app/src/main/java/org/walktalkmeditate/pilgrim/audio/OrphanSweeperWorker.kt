// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily background worker that runs [OrphanRecordingSweeper.sweepAll].
 * Catches the global case of walks the user never opens — Stage 2-E's
 * on-init sweep covers walks the user views.
 */
@HiltWorker
class OrphanSweeperWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sweeper: OrphanRecordingSweeper,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val result = sweeper.sweepAll()
        Log.i(TAG, "sweepAll: $result")
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG, "sweepAll failed", t)
        Result.retry()
    }

    private companion object { const val TAG = "OrphanSweeperWorker" }
}
