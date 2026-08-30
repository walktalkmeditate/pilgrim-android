// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.TranscribedRecordingSnapshot

/**
 * [ThreadsBackfillWorker.doWork] through the REAL worker built via
 * `TestListenableWorkerBuilder` (platform-object builder rule; precedent
 * [org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadWorkerTest]'s
 * direct-construction `WorkerFactory`, since the `@HiltWorker` constructor
 * is directly callable): every [ThreadsBackfillOutcome] maps to the
 * WorkManager result the worker's KDoc promises, and a collaborator throw
 * inside the sweep retries instead of crashing the worker. Runner wiring
 * mirrors [ThreadsBackfillTest] (real store/analyzer over the Robolectric
 * filesDir, fake always-English language client); the snapshot seam is the
 * [WalkRepository] override the worker actually calls in production, and
 * the battery gate is driven through the real sticky-broadcast read
 * ([BatteryGateTest]'s mechanism), not a faked gate lambda.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsBackfillWorkerTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var store: TranscriptContextStore
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var runner: ThreadsBackfillRunner

    private var snapshotProvider: suspend () -> List<TranscribedRecordingSnapshot> = { emptyList() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        preferences = FakeThreadsPreferencesRepository()
        val analyzer = TranscriptContextAnalyzer(
            store,
            ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)),
            MlKitLanguageIdClient(
                object : LanguageIdentifierGateway {
                    override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                        listOf(LanguageGuess("en", 0.99f))
                },
            ),
            preferences,
        )
        runner = ThreadsBackfillRunner(store, analyzer, preferences)
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
    }

    private fun buildWorker(): ThreadsBackfillWorker {
        val repository = object : WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        ) {
            override suspend fun transcribedRecordingsSnapshot(): List<TranscribedRecordingSnapshot> =
                snapshotProvider()
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = ThreadsBackfillWorker(
                appContext = appContext,
                params = workerParameters,
                runner = runner,
                repository = repository,
            )
        }
        return TestListenableWorkerBuilder<ThreadsBackfillWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stickBattery(percent: Int) {
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, percent)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING),
        )
    }

    private fun snapshotOf(count: Int): List<TranscribedRecordingSnapshot> =
        (0 until count).map { i ->
            TranscribedRecordingSnapshot(
                uuid = "worker-u-%03d".format(i),
                transcription = "walking today I thought about the road ahead and where this path leads $i",
            )
        }

    @Test
    fun `Completed maps to success`() {
        snapshotProvider = { snapshotOf(1) }

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            TranscriptContext.ANALYSIS_VERSION,
            runBlocking { preferences.backfillCompletedAtVersion() },
        )
    }

    @Test
    fun `ToggleOff maps to success`() {
        runBlocking { preferences.setThreadsAfterWalks(false) }
        snapshotProvider = { error("snapshot must not be consulted while the toggle is off") }

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `GateClosed maps to retry`() {
        stickBattery(percent = 10)
        snapshotProvider = { error("snapshot must not be consulted while the gate is closed") }

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `Incomplete maps to retry`() {
        val items = snapshotOf(1)
        snapshotProvider = { items }
        // ThreadsBackfillTest's item-scoped write-failure shape: a
        // directory squatting on the exact temp path writeAtomically
        // opens makes that one save genuinely fail.
        File(context.filesDir, "transcript_contexts/${items.single().uuid}.json.gz.tmp").mkdirs()

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `Stale maps to retry`() {
        // An import landing mid-sweep bumps the generation after the
        // sweep captured its start value — the snapshot read is the
        // sweep's last collaborator call before the batch loop, so it is
        // the natural place to land the bump.
        snapshotProvider = {
            preferences.bumpImportGeneration()
            emptyList()
        }

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `a collaborator throwing from the sweep maps to retry`() {
        snapshotProvider = { throw IllegalStateException("snapshot read blew up") }

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
