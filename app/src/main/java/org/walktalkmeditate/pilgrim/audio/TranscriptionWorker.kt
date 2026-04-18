// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: TranscriptionRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L).takeIf { it > 0 }
            ?: return Result.failure()
        val outcome = runner.transcribePending(walkId)
        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                // Disk-full at install time, asset-stream errors, or
                // any other model-load failure is plausibly transient.
                // Ask WorkManager to back off and retry rather than
                // marking the walk's transcription permanently failed.
                if (error is WhisperError.ModelLoadFailed) Result.retry()
                else Result.failure()
            },
        )
    }

    companion object { const val KEY_WALK_ID = "walk_id" }
}
