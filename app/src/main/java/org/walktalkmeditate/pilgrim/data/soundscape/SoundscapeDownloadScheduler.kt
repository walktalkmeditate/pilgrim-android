// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

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
 * Fan-out of [SoundscapeDownloadWorker] enqueues, keyed by asset
 * id. Unique-work semantics ensure one worker per asset at a time
 * — second enqueue while in flight is KEEP (no-op); explicit retry
 * uses REPLACE to supersede a FAILED run.
 *
 * Parallels `VoiceGuideDownloadScheduler` (Stage 5-D) — shared
 * [DownloadProgress] type lives in `data.audio.download`.
 */
interface SoundscapeDownloadScheduler {
    /** Enqueue with KEEP — second concurrent call no-ops. */
    fun enqueue(assetId: String)

    /** Enqueue with REPLACE — used by UI when retrying a FAILED run. */
    fun retry(assetId: String)

    /** Cancel the unique-named work. Partial files remain for resume. */
    fun cancel(assetId: String)

    /** Progress + terminal state for the asset. Null until any work exists. */
    fun observe(assetId: String): Flow<DownloadProgress?>
}

@Singleton
class WorkManagerSoundscapeDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SoundscapeDownloadScheduler {

    override fun enqueue(assetId: String) = enqueueInternal(assetId, ExistingWorkPolicy.KEEP)
    override fun retry(assetId: String) = enqueueInternal(assetId, ExistingWorkPolicy.REPLACE)

    override fun cancel(assetId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(SoundscapeDownloadWorker.uniqueWorkName(assetId))
    }

    override fun observe(assetId: String): Flow<DownloadProgress?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(
                SoundscapeDownloadWorker.uniqueWorkName(assetId),
            )
            .asFlow()
            .map { infos -> infos.lastOrNull()?.toDownloadProgress() }
            .distinctUntilChanged()

    private fun enqueueInternal(assetId: String, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SoundscapeDownloadWorker>()
            .setInputData(workDataOf(SoundscapeDownloadWorker.KEY_ASSET_ID to assetId))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SoundscapeDownloadWorker.uniqueWorkName(assetId),
            policy,
            request,
        )
    }

    private fun WorkInfo.toDownloadProgress(): DownloadProgress {
        // Soundscape is a single file — no per-prompt progress counts.
        // Keep the shared DownloadProgress shape for interoperability
        // with voice-guide's catalog join; completed/total are 0 for
        // a single-file download.
        val mapped = when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadProgress.State.Enqueued
            WorkInfo.State.RUNNING -> DownloadProgress.State.Running
            WorkInfo.State.SUCCEEDED -> DownloadProgress.State.Succeeded
            WorkInfo.State.FAILED -> DownloadProgress.State.Failed
            WorkInfo.State.CANCELLED -> DownloadProgress.State.Cancelled
        }
        return DownloadProgress(state = mapped, completed = 0, total = 0)
    }
}
