// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Deletion + full-wipe hygiene end to end (parity spec DAT-33/BEH-65/BEH-85):
 *  - a walk-level delete captures its recordings' uuids inside the same
 *    transaction and removes their stored contexts once it commits;
 *  - a single-recording row delete does the same;
 *  - the internal full-wipe API tombstones before it sweeps, and never
 *    touches the threadsAfterWalks/importGeneration prefs (DAT-56);
 *  - deleting only a recording's AUDIO FILE (the WalkSummary/RecordingsList
 *    "delete file, keep transcription" affordance) never touches its
 *    Room row, transcription, or stored Threads context (BEH-85).
 *
 * Importer clear+bump hygiene lives in `PilgrimPackageImporterTest` instead
 * — it needs that file's archive-building fixtures, which this class does
 * not duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsDeletionHygieneTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var threadsStore: TranscriptContextStore
    private lateinit var repository: WalkRepository
    private lateinit var fileSystem: VoiceRecordingFileSystem
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
        threadsStore = TranscriptContextStore(context, json)
        fileSystem = VoiceRecordingFileSystem(context)
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
            transcriptContextStore = threadsStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
    }

    private fun fixtureContext(uuid: String) = TranscriptContext(
        uuid = uuid,
        languageCode = "en",
        wordCount = 10,
        themes = emptyList(),
        markers = TranscriptMarkers(
            wordCount = 10,
            absolutistCount = 0,
            firstPersonCount = 0,
            insightCount = 0,
            causationCount = 0,
            discrepancyCount = 0,
            temporalLean = TemporalLean.PRESENT,
        ),
        transcriptHash = "hash-$uuid",
    )

    private var timestampCounter = 1_000L

    private suspend fun insertRecordingReturning(
        walkId: Long,
        transcription: String? = "text",
    ): VoiceRecording {
        val start = timestampCounter
        timestampCounter += 60_000L
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = start + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = "recordings/test-$start.wav",
            transcription = transcription,
        )
        val id = repository.recordVoice(recording)
        return recording.copy(id = id)
    }

    // ---- transaction capture: WalkRepository.deleteWalkById ----

    @Test
    fun `deleteWalkById removes stored contexts for every recording on that walk`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording1 = insertRecordingReturning(walk.id)
        val recording2 = insertRecordingReturning(walk.id)
        threadsStore.save(fixtureContext(recording1.uuid))
        threadsStore.save(fixtureContext(recording2.uuid))

        repository.deleteWalkById(walk.id)

        assertFalse(threadsStore.hasContext(recording1.uuid))
        assertFalse(threadsStore.hasContext(recording2.uuid))
        assertNull("the walk row itself must be gone", repository.getWalk(walk.id))
    }

    @Test
    fun `deleteWalkById for a walk with no recordings never touches the store`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val before = threadsStore.changeCount.value

        repository.deleteWalkById(walk.id)

        assertEquals("no recordings means nothing to clean up", before, threadsStore.changeCount.value)
    }

    @Test
    fun `deleteWalkById tombstones so a late-finishing analysis cannot resurrect a context`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecordingReturning(walk.id)
        threadsStore.save(fixtureContext(recording.uuid))

        repository.deleteWalkById(walk.id)
        // Simulates an analysis that was already in flight when the walk
        // was deleted — its save() call happens strictly after.
        val lateSave = threadsStore.save(fixtureContext(recording.uuid))

        assertTrue("a tombstoned save still reports true (accounted for)", lateSave)
        assertFalse("the tombstone must block the resurrection", threadsStore.hasContext(recording.uuid))
    }

    // ---- recording-row delete path (OrphanRecordingSweeper's use of deleteVoiceRecording) ----

    @Test
    fun `deleteVoiceRecording removes the recording's stored context`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecordingReturning(walk.id)
        threadsStore.save(fixtureContext(recording.uuid))

        repository.deleteVoiceRecording(recording)

        assertFalse(threadsStore.hasContext(recording.uuid))
        assertNull(repository.getVoiceRecording(recording.id))
    }

    // ---- full-wipe order (ThreadsFullWipe) ----

    @Test
    fun `wipe tombstones before sweeping so a save queued mid-wipe does not resurrect`() = runBlocking {
        val preferences = FakeThreadsPreferencesRepository()
        val wipe = ThreadsFullWipe(threadsStore, preferences)
        threadsStore.save(fixtureContext("u1"))
        threadsStore.save(fixtureContext("u2"))

        wipe.wipe(listOf("u1", "u2"))

        assertEquals(emptyList<String>(), threadsStore.allUuids())
        assertTrue("tombstoned save still reports true", threadsStore.save(fixtureContext("u1")))
        assertFalse("the wipe's tombstone must block resurrection", threadsStore.hasContext("u1"))
    }

    @Test
    fun `wipe clears the moon-line key but leaves threadsAfterWalks and importGeneration untouched`() = runBlocking {
        val preferences = FakeThreadsPreferencesRepository(
            initialThreadsAfterWalks = false,
            initialImportGeneration = 5,
        )
        val wipe = ThreadsFullWipe(threadsStore, preferences)

        wipe.wipe(emptyList())

        assertEquals(1, preferences.moonLineClearedCalls)
        assertFalse("threadsAfterWalks must survive a wipe (DAT-56)", preferences.threadsAfterWalks.value)
        assertEquals("importGeneration must survive a wipe (DAT-56)", 5, preferences.importGeneration.value)
    }

    @Test
    fun `wipe leaves the U6 backfill completion and checkpoint keys untouched`() = runBlocking {
        // U6/DAT-56: a wipe is not a preference reset — the completed
        // flag surviving is WHY recordings added after a wipe never
        // trigger a fresh sweep until an import or a toggle off/on calls
        // reset(). ThreadsFullWipe never touches these keys at all; this
        // pins that as a regression guard, not just an absence-of-code
        // observation.
        val preferences = FakeThreadsPreferencesRepository()
        preferences.setBackfillCompleted(version = TranscriptContext.ANALYSIS_VERSION, atImportGeneration = 3)
        preferences.setBackfillCheckpoint(BackfillCheckpoint(processedCount = 40, forImportGeneration = 3))
        val wipe = ThreadsFullWipe(threadsStore, preferences)

        wipe.wipe(listOf("some-uuid"))

        assertEquals(
            "backfillCompletedAtVersion must survive a wipe",
            TranscriptContext.ANALYSIS_VERSION,
            preferences.backfillCompletedAtVersion(),
        )
        assertEquals(
            "backfillCompletedAtImportGeneration must survive a wipe",
            3,
            preferences.backfillCompletedAtImportGeneration(),
        )
        assertEquals(
            "backfillCheckpoint must survive a wipe",
            BackfillCheckpoint(40, 3),
            preferences.backfillCheckpoint(),
        )
    }

    @Test
    fun `wipe with no recording uuids still sweeps whatever is already on disk`() = runBlocking {
        val preferences = FakeThreadsPreferencesRepository()
        val wipe = ThreadsFullWipe(threadsStore, preferences)
        threadsStore.save(fixtureContext("orphan"))

        // Empty snapshot — deleteAll's own filename-derived tombstoning
        // (DAT-25) must still account for anything already on disk.
        wipe.wipe(emptyList())

        assertEquals(emptyList<String>(), threadsStore.allUuids())
    }

    // ---- file-delete non-interference (BEH-85) ----

    @Test
    fun `deleting only the audio file leaves the row, transcription, and context untouched`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecordingReturning(walk.id, transcription = "kept transcript")
        threadsStore.save(fixtureContext(recording.uuid))
        val absolutePath = fileSystem.absolutePath(recording.fileRelativePath)
        absolutePath.parentFile?.mkdirs()
        absolutePath.writeText("fake wav bytes")

        fileSystem.deleteFile(recording.fileRelativePath)

        assertFalse("the audio file itself must be gone", absolutePath.exists())
        val stillThere = repository.getVoiceRecording(recording.id)
        assertNotNull("the Room row must survive a file-only delete", stillThere)
        assertEquals("kept transcript", stillThere!!.transcription)
        assertTrue("the context must survive a file-only delete", threadsStore.hasContext(recording.uuid))
    }
}
