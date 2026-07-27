// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore

/**
 * Robolectric coverage for [WorkManagerWhisperModelDownloadScheduler]
 * against a real WorkManager test harness — the mandatory `.build()`
 * exercise per the house platform-object rule (precedent:
 * `WorkManagerTranscriptionSchedulerTest`, which caught the
 * Expedited + BatteryNotLow crash that six review cycles missed).
 * Also pins the U9 C1 gate (KEEP dedupe, terminal-FAILED no-op,
 * SUCCEEDED no-op only while its delivery survives on disk, retry
 * REPLACE), the C2 constraint policy
 * (UNMETERED default, sticky cellular override → CONNECTED, REPLACE
 * on flip), and the U8 WorkInfo → [ModelDownloadWork] mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerWhisperModelDownloadSchedulerTest {

    private lateinit var context: Context
    private lateinit var stubFactory: StubWorkerFactory
    private lateinit var dataStore: FakePreferencesDataStore
    private lateinit var scheduler: WorkManagerWhisperModelDownloadScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "whisper-model").deleteRecursively()
        stubFactory = StubWorkerFactory()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(stubFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        dataStore = FakePreferencesDataStore()
        scheduler = WorkManagerWhisperModelDownloadScheduler(context, dataStore)
    }

    private fun workInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WhisperModelDownloadWorker.UNIQUE_WORK_NAME)
            .get()

    private fun runStubToTerminal(result: ListenableWorker.Result) {
        stubFactory.nextResult = result
        val id = workInfos().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
    }

    private fun installVerifiedBase() {
        val filesDir = context.filesDir.toPath()
        val model = WhisperModelConfig.baseModelPath(filesDir).toFile()
        model.parentFile?.mkdirs()
        RandomAccessFile(model, "rw").use { it.setLength(WhisperModelConfig.EXPECTED_BYTES) }
        WhisperModelConfig.baseShaMarkerPath(filesDir).toFile()
            .writeText(WhisperModelConfig.EXPECTED_SHA256)
    }

    @Test fun `ensureEnqueued builds and registers unique work with the UNMETERED default`() {
        runBlocking { scheduler.ensureEnqueued() }

        val info = workInfos().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
    }

    @Test fun `ensureEnqueued twice KEEPs the first request`() {
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id

        runBlocking { scheduler.ensureEnqueued() }

        assertEquals(firstId, workInfos().single().id)
    }

    @Test fun `retry REPLACEs the existing request`() {
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id

        runBlocking { scheduler.retry() }

        val infos = workInfos()
        assertTrue(infos.any { it.id != firstId && it.state == WorkInfo.State.ENQUEUED })
    }

    // C1 gate: a terminal failure awaits the explicit user retry —
    // app restarts must not burn another attempt cycle.
    @Test fun `ensureEnqueued after a terminal failure does not re-enqueue, retry does`() {
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id
        runStubToTerminal(
            ListenableWorker.Result.failure(
                workDataOf(
                    WhisperModelDownloadWorker.KEY_FAILURE_REASON
                        to WhisperModelDownloadWorker.REASON_CHECKSUM,
                ),
            ),
        )
        assertEquals(WorkInfo.State.FAILED, workInfos().single().state)

        runBlocking { scheduler.ensureEnqueued() }
        assertEquals(firstId, workInfos().single().id)
        assertEquals(WorkInfo.State.FAILED, workInfos().single().state)

        runBlocking { scheduler.retry() }
        val latest = workInfos()
        assertTrue(latest.any { it.id != firstId && it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun `ensureEnqueued after success with the delivered model on disk does not re-enqueue`() {
        installVerifiedBase()
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id
        runStubToTerminal(ListenableWorker.Result.success())
        assertEquals(WorkInfo.State.SUCCEEDED, workInfos().single().state)

        runBlocking { scheduler.ensureEnqueued() }

        assertEquals(firstId, workInfos().single().id)
        assertEquals(WorkInfo.State.SUCCEEDED, workInfos().single().state)
    }

    // Stale-SUCCEEDED heal: the record outlived its bytes ("clear app
    // storage", manual deletion, partial restore) — with no verified
    // base and no partial in flight, ensureEnqueued re-enqueues.
    @Test fun `ensureEnqueued after success with an empty filesystem re-enqueues`() {
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id
        runStubToTerminal(ListenableWorker.Result.success())
        assertEquals(WorkInfo.State.SUCCEEDED, workInfos().single().state)

        runBlocking { scheduler.ensureEnqueued() }

        assertTrue(workInfos().any { it.id != firstId && it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun `ensureEnqueued after success with a partial in flight does not re-enqueue`() {
        val partial = WhisperModelConfig.basePartialPath(context.filesDir.toPath()).toFile()
        partial.parentFile?.mkdirs()
        partial.writeBytes(ByteArray(100))
        runBlocking { scheduler.ensureEnqueued() }
        runStubToTerminal(ListenableWorker.Result.success())

        runBlocking { scheduler.ensureEnqueued() }

        assertEquals(WorkInfo.State.SUCCEEDED, workInfos().single().state)
    }

    // C2: the override is sticky — it applies to enqueues made long
    // after the flip, not just the immediate REPLACE.
    @Test fun `cellular override flips the constraint for subsequent enqueues`() {
        runBlocking { scheduler.setCellularOverride(true) }
        assertTrue(workInfos().isEmpty())

        runBlocking { scheduler.ensureEnqueued() }

        val info = workInfos().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(runBlocking { scheduler.observeCellularOverride().first() })
    }

    @Test fun `cellular override flip re-enqueues pending work with REPLACE under the new constraint`() {
        runBlocking { scheduler.ensureEnqueued() }
        val firstId = workInfos().single().id
        assertEquals(NetworkType.UNMETERED, workInfos().single().constraints.requiredNetworkType)

        runBlocking { scheduler.setCellularOverride(true) }

        val replacement = workInfos().single { it.state == WorkInfo.State.ENQUEUED }
        assertTrue(replacement.id != firstId)
        assertEquals(NetworkType.CONNECTED, replacement.constraints.requiredNetworkType)
    }

    @Test fun `observe emits null before any work exists`() = runBlocking {
        assertNull(withTimeout(10_000L) { scheduler.observe().first() })
    }

    // outputData reason round-trip through real WorkManager, not just
    // the mapping function.
    @Test fun `observe maps a FAILED record's outputData reason`() {
        runBlocking { scheduler.ensureEnqueued() }
        runStubToTerminal(
            ListenableWorker.Result.failure(
                workDataOf(
                    WhisperModelDownloadWorker.KEY_FAILURE_REASON
                        to WhisperModelDownloadWorker.REASON_STORAGE,
                ),
            ),
        )

        val work = runBlocking {
            withTimeout(10_000L) { scheduler.observe().first { it != null } }
        }

        assertEquals(ModelDownloadWork.Failed(ModelDownloadWork.Failed.Reason.Storage), work)
    }

    @Test fun `mapping - enqueued and blocked read Enqueued`() {
        assertEquals(
            ModelDownloadWork.Enqueued,
            mapModelDownloadWork(WorkInfo.State.ENQUEUED, Data.EMPTY, Data.EMPTY),
        )
        assertEquals(
            ModelDownloadWork.Enqueued,
            mapModelDownloadWork(WorkInfo.State.BLOCKED, Data.EMPTY, Data.EMPTY),
        )
    }

    @Test fun `mapping - running reads byte progress, defaulting the total to the pinned size`() {
        val progressed = mapModelDownloadWork(
            WorkInfo.State.RUNNING,
            workDataOf(
                WhisperModelDownloadWorker.KEY_BYTES_DOWNLOADED to 1_234L,
                WhisperModelDownloadWorker.KEY_TOTAL_BYTES to 10_000L,
                WhisperModelDownloadWorker.KEY_VERIFYING to false,
            ),
            Data.EMPTY,
        )
        assertEquals(ModelDownloadWork.Downloading(1_234L, 10_000L), progressed)

        val fresh = mapModelDownloadWork(WorkInfo.State.RUNNING, Data.EMPTY, Data.EMPTY)
        assertEquals(
            ModelDownloadWork.Downloading(0L, WhisperModelConfig.EXPECTED_BYTES),
            fresh,
        )
    }

    @Test fun `mapping - running with the verify flag reads Verifying`() {
        val work = mapModelDownloadWork(
            WorkInfo.State.RUNNING,
            workDataOf(WhisperModelDownloadWorker.KEY_VERIFYING to true),
            Data.EMPTY,
        )
        assertEquals(ModelDownloadWork.Verifying, work)
    }

    @Test fun `mapping - terminals read Succeeded, Cancelled, and typed failures`() {
        assertEquals(
            ModelDownloadWork.Succeeded,
            mapModelDownloadWork(WorkInfo.State.SUCCEEDED, Data.EMPTY, Data.EMPTY),
        )
        assertEquals(
            ModelDownloadWork.Cancelled,
            mapModelDownloadWork(WorkInfo.State.CANCELLED, Data.EMPTY, Data.EMPTY),
        )
        assertEquals(
            ModelDownloadWork.Failed(ModelDownloadWork.Failed.Reason.Storage),
            mapModelDownloadWork(
                WorkInfo.State.FAILED,
                Data.EMPTY,
                workDataOf(
                    WhisperModelDownloadWorker.KEY_FAILURE_REASON
                        to WhisperModelDownloadWorker.REASON_STORAGE,
                ),
            ),
        )
        // An unattributed failure degrades to Checksum — the retryable direction.
        assertEquals(
            ModelDownloadWork.Failed(ModelDownloadWork.Failed.Reason.Checksum),
            mapModelDownloadWork(WorkInfo.State.FAILED, Data.EMPTY, Data.EMPTY),
        )
    }

    @Test fun `cellular override defaults to off`() {
        assertFalse(runBlocking { scheduler.observeCellularOverride().first() })
    }

    /**
     * Stands in for the Hilt-built production worker so the harness
     * can drive unique work to real terminal states; the production
     * worker's own behavior is covered by [WhisperModelDownloadWorkerTest].
     */
    private class StubWorkerFactory : WorkerFactory() {
        var nextResult: ListenableWorker.Result = ListenableWorker.Result.failure()

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = object : Worker(appContext, workerParameters) {
            override fun doWork(): Result = nextResult
        }
    }
}
