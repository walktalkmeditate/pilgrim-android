// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.share.RepairSlot
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore
import org.walktalkmeditate.pilgrim.data.share.SlotIdentity
import org.walktalkmeditate.pilgrim.data.share.SlotKind
import org.walktalkmeditate.pilgrim.data.share.SlotStatus
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * The daily sweep's share-prep half, through the real worker.
 *
 * A repair record is the keep set [SharePrepStore.sweepOrphans] is
 * handed, so a record that outlives its walk protects that walk's
 * transcoded voice from every future sweep — this is where
 * [ShareRepairStore.sweepStale] is wired in to stop it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrphanSweeperWorkerTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var sweeper: OrphanRecordingSweeper
    private lateinit var prepStore: SharePrepStore
    private lateinit var repairStore: ShareRepairStore

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        sweeper = OrphanRecordingSweeper(context, repository, FakeTranscriptionScheduler())
        prepStore = SharePrepStore(context, FakeShareAudioTranscoder(), VoiceRecordingFileSystem(context))
        repairStore = ShareRepairStore(context, json)
    }

    @After
    fun tearDown() {
        db.close()
        File(context.cacheDir, "share-prep").deleteRecursively()
        // The `preferencesDataStore` delegate caches per classloader, so
        // isolation comes from a fresh uuid per test rather than from
        // deleting the file (ShareRepairStoreTest documents the trap).
    }

    private fun buildWorker(): OrphanSweeperWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = OrphanSweeperWorker(
                appContext = appContext,
                params = workerParameters,
                sweeper = sweeper,
                sharePrepStore = prepStore,
                shareRepairStore = repairStore,
                repository = repository,
            )
        }
        return TestListenableWorkerBuilder<OrphanSweeperWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private fun seedArtifact(walkUuid: String, recordingUuid: String): File =
        prepStore.artifactFile(walkUuid, recordingUuid).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }

    private fun pendingAudioSlot(recordingUuid: String) =
        RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio(recordingUuid), SlotStatus.PENDING)

    @Test
    fun `a record for a deleted walk is cleared and its transcoded voice swept`() = runBlocking {
        val deletedWalkUuid = UUID.randomUUID().toString()
        val artifact = seedArtifact(deletedWalkUuid, "rec-1")
        repairStore.prePopulate(deletedWalkUuid, "share-gone", listOf(pendingAudioSlot("rec-1")))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull("a record whose walk is gone can never be repaired — it must not survive", repairStore.load(deletedWalkUuid))
        assertFalse("and must stop protecting the walker's transcoded voice", artifact.exists())
        assertFalse(artifact.parentFile!!.exists())
    }

    @Test
    fun `a record for a live walk survives the sweep with its artifacts`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 601_000L)
        val artifact = seedArtifact(walk.uuid, "rec-1")
        repairStore.prePopulate(walk.uuid, "share-live", listOf(pendingAudioSlot("rec-1")))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNotNull("a live walk still owes those files a repair pass", repairStore.load(walk.uuid))
        assertTrue(artifact.exists())
    }
}
