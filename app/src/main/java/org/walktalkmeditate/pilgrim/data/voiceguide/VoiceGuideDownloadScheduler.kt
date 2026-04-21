// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress

/**
 * Fan-out of [VoiceGuideDownloadWorker] enqueues, keyed by pack id.
 * Unique-work semantics ensure exactly one worker per pack — a
 * second enqueue while the first is running is KEEP (no-op); an
 * explicit retry uses REPLACE to supersede a previous FAILED run.
 */
interface VoiceGuideDownloadScheduler {
    /** Enqueue with KEEP — second concurrent call for the same pack no-ops. */
    fun enqueue(packId: String)

    /** Enqueue with REPLACE — used by the UI when re-running after a FAILED state. */
    fun retry(packId: String)

    /** Cancel the unique-named work. Partial files remain on disk for resume. */
    fun cancel(packId: String)

    /** Progress + terminal state for a pack. Emits null until any work exists. */
    fun observe(packId: String): Flow<DownloadProgress?>
}

@Singleton
class WorkManagerVoiceGuideDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceGuideDownloadScheduler {

    override fun enqueue(packId: String) = enqueueInternal(packId, ExistingWorkPolicy.KEEP)

    override fun retry(packId: String) = enqueueInternal(packId, ExistingWorkPolicy.REPLACE)

    override fun cancel(packId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName(packId))
    }

    override fun observe(packId: String): Flow<DownloadProgress?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(
                VoiceGuideDownloadWorker.uniqueWorkName(packId),
            )
            .asFlow()
            .map { infos -> infos.lastOrNull()?.toDownloadProgress() }
            .distinctUntilChanged()

    private fun enqueueInternal(packId: String, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<VoiceGuideDownloadWorker>()
            .setInputData(workDataOf(VoiceGuideDownloadWorker.KEY_PACK_ID to packId))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            VoiceGuideDownloadWorker.uniqueWorkName(packId),
            policy,
            request,
        )
    }

    private fun WorkInfo.toDownloadProgress(): DownloadProgress {
        val completed = progress.getInt(VoiceGuideDownloadWorker.KEY_COMPLETED, 0)
        val total = progress.getInt(VoiceGuideDownloadWorker.KEY_TOTAL, 0)
        val mapped = when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadProgress.State.Enqueued
            WorkInfo.State.RUNNING -> DownloadProgress.State.Running
            WorkInfo.State.SUCCEEDED -> DownloadProgress.State.Succeeded
            WorkInfo.State.FAILED -> DownloadProgress.State.Failed
            WorkInfo.State.CANCELLED -> DownloadProgress.State.Cancelled
        }
        return DownloadProgress(mapped, completed, total)
    }
}
