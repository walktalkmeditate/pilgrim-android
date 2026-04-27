// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
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
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * Exercises [OrphanRecordingSweeper] against a real Room database and
 * a tmpfs filesDir. Each test sets up a controlled mix of disk + Room
 * state and asserts the post-sweep counts and side effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrphanRecordingSweeperTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var scheduler: FakeTranscriptionScheduler
    private lateinit var sweeper: OrphanRecordingSweeper

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
        scheduler = FakeTranscriptionScheduler()
        sweeper = OrphanRecordingSweeper(context, repository, scheduler)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `case_a orphan WAV deleted when Room has no matching row`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val orphanFile = writeWav(walk.uuid, "orphan.wav", dataBytes = 100)

        val result = sweeper.sweep(walk.id)

        assertEquals(1, result.orphanFilesDeleted)
        assertFalse(Files.exists(orphanFile))
    }

    @Test
    fun `case_b orphan row deleted when WAV file is missing`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        // Insert a row whose file was never written to disk.
        val recording = insertRecording(walk.id, fileRelativePath = "recordings/${walk.uuid}/never-written.wav")

        val result = sweeper.sweep(walk.id)

        assertEquals(1, result.orphanRowsDeleted)
        assertNull(repository.getVoiceRecording(recording.id))
    }

    @Test
    fun `case_c zombie row+wav deleted when transcription null and dataSize zero`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id, transcription = null)
        // Write a 44-byte canonical header with dataSize = 0.
        val file = writeWav(walk.uuid, Path.of(recording.fileRelativePath).fileName.toString(), dataBytes = 0)

        val result = sweeper.sweep(walk.id)

        assertEquals(1, result.zombieRowsDeleted)
        assertNull(repository.getVoiceRecording(recording.id))
        assertFalse(Files.exists(file))
    }

    @Test
    fun `case_d valid wav with null transcription reschedules transcription`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id, transcription = null)
        // Write a real-ish WAV with non-zero data.
        writeWav(walk.uuid, Path.of(recording.fileRelativePath).fileName.toString(), dataBytes = 1024)

        val result = sweeper.sweep(walk.id)

        assertEquals(1, result.rescheduledWalks)
        assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
        // Row preserved; case (d) reschedules but does NOT delete.
        assertNotNull(repository.getVoiceRecording(recording.id))
    }

    @Test
    fun `mixed a_b_c_d in one sweep counts correctly`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        // (a) orphan file
        val orphanFile = writeWav(walk.uuid, "orphan.wav", dataBytes = 50)
        // (b) row pointing at missing file
        val orphanRow = insertRecording(walk.id, fileRelativePath = "recordings/${walk.uuid}/missing.wav")
        // (c) zombie: row + 0-byte WAV
        val zombieRow = insertRecording(walk.id, transcription = null)
        writeWav(walk.uuid, Path.of(zombieRow.fileRelativePath).fileName.toString(), dataBytes = 0)
        // (d) row with valid WAV, null transcription
        val pendingRow = insertRecording(walk.id, transcription = null)
        writeWav(walk.uuid, Path.of(pendingRow.fileRelativePath).fileName.toString(), dataBytes = 200)

        val result = sweeper.sweep(walk.id)

        assertEquals(1, result.orphanFilesDeleted)
        assertEquals(1, result.orphanRowsDeleted)
        assertEquals(1, result.zombieRowsDeleted)
        assertEquals(1, result.rescheduledWalks)

        assertFalse(Files.exists(orphanFile))
        assertNull(repository.getVoiceRecording(orphanRow.id))
        assertNull(repository.getVoiceRecording(zombieRow.id))
        assertNotNull(repository.getVoiceRecording(pendingRow.id))
        assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
    }

    @Test
    fun `path guard refuses to delete file outside recordings root`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        // A .wav file directly under filesDir, not under recordings/<uuid>/.
        val outsideFile = context.filesDir.toPath().resolve("rogue.wav")
        Files.newOutputStream(outsideFile).use { it.write(ByteArray(10)) }
        // Plus an orphan WAV inside the canonical layout to ensure the
        // sweep actually runs and the safe one is the one preserved.
        val canonicalOrphan = writeWav(walk.uuid, "real-orphan.wav", dataBytes = 50)

        val result = sweeper.sweep(walk.id)

        // Only the canonical orphan was deleted; the rogue file outside
        // the recordings root is untouched (the sweep didn't even
        // consider it because it isn't inside walkDir).
        assertEquals(1, result.orphanFilesDeleted)
        assertFalse(Files.exists(canonicalOrphan))
        assertTrue("rogue file outside recordings root must be preserved", Files.exists(outsideFile))
    }

    @Test
    fun `path guard refuses to delete non-wav file in walk dir`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        // A non-.wav file inside the walk's recordings dir — possibly a
        // future stage's metadata file. Sweeper must NOT treat it as
        // an orphan.
        val walkDir = context.filesDir.toPath().resolve("recordings/${walk.uuid}")
        Files.createDirectories(walkDir)
        val txtFile = walkDir.resolve("notes.txt")
        Files.newOutputStream(txtFile).use { it.write(ByteArray(5)) }

        val result = sweeper.sweep(walk.id)

        assertEquals(0, result.orphanFilesDeleted)
        assertTrue(Files.exists(txtFile))
    }

    @Test
    fun `sweep returns empty result when walk does not exist`() = runBlocking {
        val result = sweeper.sweep(walkId = 99_999L)
        assertEquals(SweepResult(), result)
        assertTrue(scheduler.scheduledWalkIds.isEmpty())
    }

    @Test
    fun `sweep no-op when row's transcription is already populated`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id, transcription = "hello world")
        writeWav(walk.uuid, Path.of(recording.fileRelativePath).fileName.toString(), dataBytes = 500)

        val result = sweeper.sweep(walk.id)

        assertEquals(SweepResult(), result)
        assertTrue(scheduler.scheduledWalkIds.isEmpty())
    }

    @Test
    fun `sweepAll iterates finished walks`() = runBlocking {
        val walk1 = repository.startWalk(startTimestamp = 0L)
        val walk2 = repository.startWalk(startTimestamp = 100_000L)
        // sweepAll skips active walks (endTimestamp == null) so an
        // in-flight recording isn't mistaken for an orphan. Finalize
        // both before invoking.
        repository.finishWalk(walk1, endTimestamp = 60_000L)
        repository.finishWalk(walk2, endTimestamp = 160_000L)
        // walk1 has an orphan file; walk2 has an orphan row.
        writeWav(walk1.uuid, "stray.wav", dataBytes = 10)
        insertRecording(walk2.id, fileRelativePath = "recordings/${walk2.uuid}/missing.wav")

        val result = sweeper.sweepAll()

        assertEquals(1, result.orphanFilesDeleted)
        assertEquals(1, result.orphanRowsDeleted)
    }

    @Test
    fun `case_e sweepAll deletes recordings dir for walks with no row`() = runBlocking {
        // Stage 9.5-C scenario: user discarded a walk whose row had a
        // mid-recording WAV on disk. The walk row + voice_recordings rows
        // were cascade-deleted, but the on-disk recordings/<uuid>/ tree
        // is still there. case (e) reclaims it.
        val ghostUuid = "11111111-2222-3333-4444-555555555555"
        val ghostFile = writeWav(ghostUuid, "leftover.wav", dataBytes = 200)
        // A real finished walk in parallel — its dir must NOT be touched.
        val livingWalk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(livingWalk, endTimestamp = 60_000L)
        val livingFile = writeWav(livingWalk.uuid, "kept.wav", dataBytes = 200)
        insertRecording(livingWalk.id, fileRelativePath = "recordings/${livingWalk.uuid}/kept.wav")

        val result = sweeper.sweepAll()

        assertEquals(1, result.orphanDirsDeleted)
        assertFalse("orphan dir's WAV must be deleted", Files.exists(ghostFile))
        assertFalse(
            "orphan dir itself must be deleted",
            Files.exists(context.filesDir.toPath().resolve("recordings/$ghostUuid")),
        )
        assertTrue("finished walk's WAV must survive", Files.exists(livingFile))
    }

    @Test
    fun `case_e preserves active walk dir even though it has no finished-walk row`() = runBlocking {
        // Active walk: row exists in walks table with endTimestamp=null.
        // sweepAll filters per-walk reconciliation to finished walks but
        // case (e) consults the FULL walk list — its uuid IS known, so
        // the dir survives.
        val activeWalk = repository.startWalk(startTimestamp = 0L)
        val inFlight = writeWav(activeWalk.uuid, "in-flight.wav", dataBytes = 100)

        val result = sweeper.sweepAll()

        assertEquals(0, result.orphanDirsDeleted)
        assertTrue("active walk's dir must be preserved", Files.exists(inFlight))
    }

    @Test
    fun `case_e refuses to delete non-uuid named dirs`() = runBlocking {
        // A dir whose name isn't a valid UUID — e.g. a future stage's
        // metadata or a manually-created folder. case (e) skips it
        // rather than deleting on a name we don't recognize.
        val weirdDir = context.filesDir.toPath().resolve("recordings/not-a-uuid")
        Files.createDirectories(weirdDir)
        val weirdFile = weirdDir.resolve("strange.wav")
        Files.newOutputStream(weirdFile).use { it.write(ByteArray(10)) }

        val result = sweeper.sweepAll()

        assertEquals(0, result.orphanDirsDeleted)
        assertTrue("non-uuid dir must be preserved", Files.exists(weirdDir))
        assertTrue(Files.exists(weirdFile))
    }

    @Test
    fun `deleteRecordingIfSafe removes a single WAV under recordings root`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val target = writeWav(walk.uuid, "victim.wav", dataBytes = 50)
        val relative = "recordings/${walk.uuid}/victim.wav"

        val deleted = sweeper.deleteRecordingIfSafe(relative)

        assertTrue(deleted)
        assertFalse(Files.exists(target))
    }

    @Test
    fun `deleteRecordingIfSafe refuses paths that escape recordings root`() = runBlocking {
        // A relative path that escapes filesDir/recordings via "..".
        // The canonical-path guard must reject it without touching disk.
        val outsideFile = context.filesDir.toPath().resolve("rogue.wav")
        Files.newOutputStream(outsideFile).use { it.write(ByteArray(10)) }

        val deleted = sweeper.deleteRecordingIfSafe("../rogue.wav")

        assertFalse(deleted)
        assertTrue("file outside recordings root must be preserved", Files.exists(outsideFile))
    }

    @Test
    fun `sweepAll skips active walk so its in-flight WAV is preserved`() = runBlocking {
        val activeWalk = repository.startWalk(startTimestamp = 0L)
        // Simulate an in-flight recording: WAV written but Room row
        // not yet inserted. Without the active-walk filter, sweepAll's
        // case (a) would delete this file.
        val inFlight = writeWav(activeWalk.uuid, "in-flight.wav", dataBytes = 100)

        val result = sweeper.sweepAll()

        assertEquals(0, result.orphanFilesDeleted)
        assertTrue("in-flight WAV must be preserved", java.nio.file.Files.exists(inFlight))
    }

    private val timestampCounter = AtomicLong(1_000_000L)

    private fun insertRecording(
        walkId: Long,
        transcription: String? = null,
        durationMillis: Long = 5_000L,
        fileRelativePath: String? = null,
    ): VoiceRecording = runBlocking {
        val start = timestampCounter.getAndAdd(60_000L)
        val end = start + durationMillis
        val walk = repository.getWalk(walkId)!!
        val path = fileRelativePath ?: "recordings/${walk.uuid}/rec-${start}.wav"
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = end,
            durationMillis = durationMillis,
            fileRelativePath = path,
            transcription = transcription,
        )
        val id = repository.recordVoice(recording)
        recording.copy(id = id)
    }

    private fun writeWav(walkUuid: String, fileName: String, dataBytes: Int): Path {
        val dir = context.filesDir.toPath().resolve("recordings/$walkUuid")
        Files.createDirectories(dir)
        val file = dir.resolve(fileName)
        // Canonical 44-byte WAV header with fixed format (16 kHz mono PCM
        // 16-bit). Only the dataSize field at offset 40 matters for the
        // sweeper; other fields are filled with plausible values so a
        // future test that actually parses the header doesn't choke.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)             // RIFF size = 36 + dataSize
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                          // fmt chunk size
            putShort(1)                         // PCM
            putShort(1)                         // mono
            putInt(16_000)                      // sample rate
            putInt(32_000)                      // byte rate
            putShort(2)                         // block align
            putShort(16)                        // bits per sample
            put("data".toByteArray())
            putInt(dataBytes)                   // data size at offset 40
        }
        Files.newOutputStream(file).use { out ->
            out.write(header.array())
            if (dataBytes > 0) out.write(ByteArray(dataBytes))
        }
        return file
    }
}
