// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface TranscriptionScheduler {
    fun scheduleForWalk(walkId: Long)
}

@Singleton
class WorkManagerTranscriptionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TranscriptionScheduler {

    override fun scheduleForWalk(walkId: Long) {
        // WorkRequest.Builder.build() throws IllegalArgumentException if
        // an expedited work request carries any constraint other than
        // network or storage. Pairing `setExpedited` with
        // `setRequiresBatteryNotLow(true)` crashes at finishWalk time.
        // Keep expedited for post-walk UX (transcript should appear
        // within seconds on the summary screen); keep the
        // storage-not-low constraint (transcription writes to Room);
        // drop the battery constraint.
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_WALK_ID to walkId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "transcribe-walk-$walkId",
            // KEEP, not REPLACE: TranscriptionRunner re-reads pending
            // rows from Room on each run, so a second schedule call for
            // the same walk (e.g., user double-tapping Finish) should
            // be a no-op rather than cancelling and restarting an
            // already-running batch.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
