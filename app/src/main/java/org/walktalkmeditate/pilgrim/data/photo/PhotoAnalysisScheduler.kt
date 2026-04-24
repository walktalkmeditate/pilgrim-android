// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 7-B: schedules ML Kit analysis for the pinned photos of a
 * walk. Per-walk batch (not per-photo) so the shared
 * [MlKitPhotoLabeler] stays alive across all photos in one run and
 * WorkManager idempotency is trivial — `KEEP` + unique-per-walk name.
 */
interface PhotoAnalysisScheduler {
    fun scheduleForWalk(walkId: Long)
}

@Singleton
class WorkManagerPhotoAnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoAnalysisScheduler {

    override fun scheduleForWalk(walkId: Long) {
        // Stage 2-F lesson: Expedited + BatteryNotLow crashes at
        // WorkRequest.build(). Analysis is not time-critical (no user
        // is waiting on labels; they're a 7-C/AI-prompt input), so we
        // skip setExpedited entirely and let WorkManager schedule when
        // the device is idle and storage is low-OK.
        val request = OneTimeWorkRequestBuilder<PhotoAnalysisWorker>()
            .setInputData(workDataOf(PhotoAnalysisWorker.KEY_WALK_ID to walkId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(walkId),
            // KEEP: the runner re-reads pending rows each run, so a
            // second scheduleForWalk for the same walk (startup sweep
            // while a worker is still running) should be a no-op
            // rather than cancelling and restarting an in-flight
            // batch.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        fun uniqueWorkName(walkId: Long): String = "photo-analysis-walk-$walkId"
    }
}
