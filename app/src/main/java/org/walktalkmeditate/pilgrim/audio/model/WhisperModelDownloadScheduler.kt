// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.di.VoicePreferencesDataStore

/**
 * Single-flight scheduling surface for the whisper base-model download
 * (U9 spec C1/C2). Also the production [ModelDownloadWorkSource]: the
 * WorkInfo → [ModelDownloadWork] mapping documented on that seam lives
 * in [mapModelDownloadWork].
 */
interface WhisperModelDownloadScheduler : ModelDownloadWorkSource {

    /**
     * KEEP enqueue, gated: a FAILED record awaits an explicit [retry]
     * (auto-re-enqueue would burn the checksum cap's bandwidth budget
     * on every app open), and a SUCCEEDED record no-ops only while its
     * delivery survives on disk (verified base, or a partial resuming
     * toward one) — a stale record whose bytes were cleared re-enqueues.
     * Everything else — no record, pending work (KEEP dedupes),
     * CANCELLED (resume from the partial) — enqueues.
     */
    suspend fun ensureEnqueued()

    /** REPLACE enqueue — the explicit user path past a terminal failure. */
    suspend fun retry()

    /**
     * Persist the sticky cellular override, then REPLACE-re-enqueue any
     * unfinished work so the new constraint applies immediately —
     * lossless because the replacement resumes from the partial.
     */
    suspend fun setCellularOverride(enabled: Boolean)

    /** Sheet-facing read of the sticky override (U11). */
    override fun observeCellularOverride(): Flow<Boolean>
}

@Singleton
class WorkManagerWhisperModelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @VoicePreferencesDataStore private val dataStore: DataStore<Preferences>,
) : WhisperModelDownloadScheduler {

    override suspend fun ensureEnqueued() {
        when (latestWorkInfo()?.state) {
            WorkInfo.State.FAILED -> return
            WorkInfo.State.SUCCEEDED -> if (deliveredOrInFlight()) return
            else -> Unit
        }
        enqueue(ExistingWorkPolicy.KEEP)
    }

    /**
     * A SUCCEEDED record is only as good as the bytes it delivered:
     * "clear app storage", manual deletion, or a partial restore
     * leaves the record pointing at nothing. No verified base and no
     * partial resuming toward one → the record is stale and
     * [ensureEnqueued] re-enqueues.
     */
    private suspend fun deliveredOrInFlight(): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir.toPath()
        WhisperModelConfig.verifiedModelPresent(
            filesDir = filesDir,
            expectedBytes = WhisperModelConfig.EXPECTED_BYTES,
            expectedSha256 = WhisperModelConfig.EXPECTED_SHA256,
        ) || Files.exists(WhisperModelConfig.basePartialPath(filesDir))
    }

    override suspend fun retry() {
        enqueue(ExistingWorkPolicy.REPLACE)
    }

    override suspend fun setCellularOverride(enabled: Boolean) {
        dataStore.edit { it[CELLULAR_OVERRIDE] = enabled }
        val latest = latestWorkInfo() ?: return
        if (!latest.state.isFinished) enqueue(ExistingWorkPolicy.REPLACE)
    }

    override fun observeCellularOverride(): Flow<Boolean> =
        dataStore.data
            .map { prefs -> prefs[CELLULAR_OVERRIDE] ?: false }
            .distinctUntilChanged()

    override fun observe(): Flow<ModelDownloadWork?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WhisperModelDownloadWorker.UNIQUE_WORK_NAME)
            .map { infos ->
                infos.lastOrNull()?.let { mapModelDownloadWork(it.state, it.progress, it.outputData) }
            }
            .distinctUntilChanged()

    private suspend fun latestWorkInfo(): WorkInfo? = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WhisperModelDownloadWorker.UNIQUE_WORK_NAME)
            .get()
            .lastOrNull()
    }

    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        val override = dataStore.data.first()[CELLULAR_OVERRIDE] ?: false
        val networkType = if (override) NetworkType.CONNECTED else NetworkType.UNMETERED
        // No STORAGE_NOT_LOW constraint (spec C2): it would hold the job
        // ENQUEUED with no user-visible reason; the worker's StatFs
        // precheck produces the actionable FailedStorage terminal instead.
        val request = OneTimeWorkRequestBuilder<WhisperModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WhisperModelDownloadWorker.UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    companion object {
        /**
         * Lives in the `voice_preferences` DataStore beside
         * `autoTranscribe` — the transcription pref namespace. Sticky:
         * survives the download's completion and applies to any future
         * re-delivery.
         */
        val CELLULAR_OVERRIDE = booleanPreferencesKey("modelDownloadCellularOverride")
    }
}

/**
 * The seam mapping pinned in [ModelDownloadWork]'s contract:
 * ENQUEUED/BLOCKED → Enqueued; RUNNING → Downloading (byte progress) or
 * Verifying (worker-flagged phase); SUCCEEDED → Succeeded; FAILED →
 * Failed with the reason from the worker's outputData (unknown reasons
 * degrade to Checksum — the retryable direction); CANCELLED →
 * Cancelled. Top-level and internal so tests drive it without
 * constructing WorkInfo.
 */
internal fun mapModelDownloadWork(
    state: WorkInfo.State,
    progress: Data,
    outputData: Data,
): ModelDownloadWork = when (state) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelDownloadWork.Enqueued
    WorkInfo.State.RUNNING ->
        if (progress.getBoolean(WhisperModelDownloadWorker.KEY_VERIFYING, false)) {
            ModelDownloadWork.Verifying
        } else {
            ModelDownloadWork.Downloading(
                bytesDownloaded = progress.getLong(WhisperModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
                totalBytes = progress.getLong(
                    WhisperModelDownloadWorker.KEY_TOTAL_BYTES,
                    WhisperModelConfig.EXPECTED_BYTES,
                ),
            )
        }
    WorkInfo.State.SUCCEEDED -> ModelDownloadWork.Succeeded
    WorkInfo.State.FAILED -> ModelDownloadWork.Failed(
        when (outputData.getString(WhisperModelDownloadWorker.KEY_FAILURE_REASON)) {
            WhisperModelDownloadWorker.REASON_STORAGE -> ModelDownloadWork.Failed.Reason.Storage
            else -> ModelDownloadWork.Failed.Reason.Checksum
        },
    )
    WorkInfo.State.CANCELLED -> ModelDownloadWork.Cancelled
}
