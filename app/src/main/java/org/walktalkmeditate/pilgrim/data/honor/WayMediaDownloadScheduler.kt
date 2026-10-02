// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** One gather's work as the downloader reads it. */
data class WayMediaWork(
    val state: State,
    /** The worker's latest report; null before its first, and for a Way that was gone. */
    val report: WayMediaReport?,
) {
    enum class State { WAITING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    val isFinished: Boolean get() = state != State.WAITING && state != State.RUNNING
}

/**
 * The media download's WorkManager end: one unique work per Way, so a
 * gather from a reopened overview never runs twice ([gather] keeps the
 * work already pending, as iOS's `download` bounces off `active`), and
 * "try again" replaces it (iOS `retry`). UI process only: WorkManager is
 * never initialized in `:tracker`.
 */
interface WayMediaDownloadScheduler {

    /**
     * Enqueues the Way's gather, `KEEP` (or `REPLACE` when [replace]), and
     * returns the work it now follows: the one it enqueued, or the one
     * already pending that `KEEP` kept, even if that one has finished since.
     * A null is a work WorkManager no longer has.
     *
     * @throws Exception when WorkManager can't take or read the work: its
     *   database full or broken.
     */
    suspend fun gather(wayId: String, replace: Boolean): Flow<WayMediaWork?>

    fun cancel(wayId: String)
}

@Singleton
class WorkManagerWayMediaDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : WayMediaDownloadScheduler {

    override suspend fun gather(wayId: String, replace: Boolean): Flow<WayMediaWork?> {
        val workManager = WorkManager.getInstance(context)
        val name = WayMediaDownloadWorker.uniqueWorkName(wayId)
        val request = request(wayId)
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(name, policy, request).await()
        val infos = workManager.getWorkInfosForUniqueWorkFlow(name).first()
        return workManager.getWorkInfoByIdFlow(followedId(infos, request.id)).map { it?.toWork() }
    }

    override fun cancel(wayId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(WayMediaDownloadWorker.uniqueWorkName(wayId))
    }

    companion object {
        /**
         * Any network, cellular included, with no storage, battery, or
         * expedited settings (S3 §5): iOS's session is `isDiscretionary =
         * false` and has no low-storage gate, a full disk surfacing as its
         * own copy instead; `UNMETERED` would leave "gathering their
         * voices" stuck on a phone with only mobile data.
         */
        internal fun request(wayId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WayMediaDownloadWorker>()
                .setInputData(workDataOf(WayMediaDownloadWorker.KEY_WAY_ID to wayId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

        /**
         * The work a gather follows among the unique name's: the one it
         * enqueued, else the one `KEEP` kept. That one may have finished
         * between the enqueue and this read, and is still the one to follow,
         * finished: WorkManager clears a name's finished works before it
         * enqueues anew, so a finished one here is the kept one.
         */
        internal fun followedId(infos: List<WorkInfo>, enqueued: UUID): UUID {
            val followed = infos.firstOrNull { it.id == enqueued }
                ?: infos.firstOrNull { !it.state.isFinished }
                ?: infos.firstOrNull()
            return followed?.id ?: enqueued
        }

        private fun WorkInfo.toWork(): WayMediaWork {
            val mapped = when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WayMediaWork.State.WAITING
                WorkInfo.State.RUNNING -> WayMediaWork.State.RUNNING
                WorkInfo.State.SUCCEEDED -> WayMediaWork.State.SUCCEEDED
                WorkInfo.State.FAILED -> WayMediaWork.State.FAILED
                WorkInfo.State.CANCELLED -> WayMediaWork.State.CANCELLED
            }
            val data = if (state.isFinished) outputData else progress
            return WayMediaWork(mapped, WayMediaReport.from(data))
        }
    }
}
