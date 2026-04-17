// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingDataLayerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun makeWalk(start: Long = 1_000L) =
        repository.startWalk(startTimestamp = start)

    private fun makeRecording(
        walkId: Long,
        start: Long,
        duration: Long = 5_000L,
        transcription: String? = null,
    ) = VoiceRecording(
        walkId = walkId,
        startTimestamp = start,
        endTimestamp = start + duration,
        durationMillis = duration,
        fileRelativePath = "recordings/walk-$walkId/$start.wav",
        transcription = transcription,
    )

    @Test
    fun `insert and read back preserves all fields`() = runTest {
        val walk = makeWalk()
        val input = makeRecording(walk.id, start = 2_000L, transcription = "hello world").copy(
            wordsPerMinute = 120.0,
            isEnhanced = true,
        )

        val id = repository.recordVoice(input)
        val read = repository.getVoiceRecording(id)

        assertNotNull(read)
        assertEquals(walk.id, read?.walkId)
        assertEquals(2_000L, read?.startTimestamp)
        assertEquals(7_000L, read?.endTimestamp)
        assertEquals(5_000L, read?.durationMillis)
        assertEquals("recordings/walk-${walk.id}/2000.wav", read?.fileRelativePath)
        assertEquals("hello world", read?.transcription)
        assertEquals(120.0, read?.wordsPerMinute ?: 0.0, 0.001)
        assertEquals(true, read?.isEnhanced)
    }

    @Test
    fun `getForWalk orders by start_timestamp ascending`() = runTest {
        val walk = makeWalk()
        repository.recordVoice(makeRecording(walk.id, start = 3_000L))
        repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        repository.recordVoice(makeRecording(walk.id, start = 2_000L))

        val list = repository.voiceRecordingsFor(walk.id)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), list.map { it.startTimestamp })
    }

    @Test
    fun `walk deletion cascades to its recordings`() = runTest {
        val walk = makeWalk()
        repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        repository.recordVoice(makeRecording(walk.id, start = 2_000L))

        repository.deleteWalk(walk)

        assertTrue(repository.voiceRecordingsFor(walk.id).isEmpty())
    }

    @Test
    fun `recordings from different walks are isolated by getForWalk`() = runTest {
        val walkA = makeWalk(start = 1_000L)
        val walkB = makeWalk(start = 10_000L)
        repository.recordVoice(makeRecording(walkA.id, start = 2_000L))
        repository.recordVoice(makeRecording(walkA.id, start = 3_000L))
        repository.recordVoice(makeRecording(walkB.id, start = 11_000L))

        assertEquals(2, repository.countVoiceRecordingsFor(walkA.id))
        assertEquals(1, repository.countVoiceRecordingsFor(walkB.id))
        assertEquals(
            setOf(2_000L, 3_000L),
            repository.voiceRecordingsFor(walkA.id).map { it.startTimestamp }.toSet(),
        )
    }

    @Test
    fun `duplicate uuid insert is rejected by the unique constraint`() = runTest {
        val walk = makeWalk()
        val first = makeRecording(walk.id, start = 1_000L)
        repository.recordVoice(first)
        val duplicate = first.copy(id = 0, startTimestamp = 2_000L)

        try {
            repository.recordVoice(duplicate)
            fail("expected SQLiteConstraintException for duplicate uuid")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun `observeForWalk emits updates on insert`() = runTest {
        val walk = makeWalk()

        repository.observeVoiceRecordings(walk.id).test {
            assertEquals(emptyList<VoiceRecording>(), awaitItem())

            repository.recordVoice(makeRecording(walk.id, start = 1_000L))
            assertEquals(1, awaitItem().size)

            repository.recordVoice(makeRecording(walk.id, start = 2_000L))
            assertEquals(2, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `update writes transcription back onto the row`() = runTest {
        val walk = makeWalk()
        val id = repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        val original = repository.getVoiceRecording(id) ?: fail("recording should exist")
        original as VoiceRecording

        repository.updateVoiceRecording(
            original.copy(transcription = "late bloom", wordsPerMinute = 90.0),
        )
        val updated = repository.getVoiceRecording(id)

        assertEquals("late bloom", updated?.transcription)
        assertEquals(90.0, updated?.wordsPerMinute ?: 0.0, 0.001)
    }

    @Test
    fun `delete removes a recording from subsequent queries`() = runTest {
        val walk = makeWalk()
        val id = repository.recordVoice(makeRecording(walk.id, start = 1_000L))
        val recording = repository.getVoiceRecording(id)!!

        repository.deleteVoiceRecording(recording)

        assertNull(repository.getVoiceRecording(id))
        assertEquals(0, repository.countVoiceRecordingsFor(walk.id))
    }
}
