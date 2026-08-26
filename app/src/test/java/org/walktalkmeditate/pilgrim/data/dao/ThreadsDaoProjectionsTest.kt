// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * U7: the three DISTINCT recording-level/walk-level projection indices
 * `ThreadsDossierBuilder` (and `ThreadStore`'s `recordingToWalk` join)
 * consume — parity spec `docs/parity/2026-08-25-threads-engine-port.md`
 * DAT-62/DAT-64: `voiceRecordingWalkIndex` (recording uuid → WalkLite),
 * `voiceRecordingTimestampIndex` (recording uuid → its own timestamp),
 * `voiceRecordingPaceIndex` (recording uuid → wordsPerMinute), plus
 * `WalkDao`'s own `WalkLite` projection by walk id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsDaoProjectionsTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var voiceRecordingDao: VoiceRecordingDao
    private lateinit var walkDao: WalkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        voiceRecordingDao = db.voiceRecordingDao()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newWalk(
        startTimestamp: Long,
        intention: String? = null,
        weatherCondition: String? = null,
    ): Long = walkDao.insert(
        Walk(startTimestamp = startTimestamp, intention = intention, weatherCondition = weatherCondition),
    )

    private fun recording(
        uuid: String,
        walkId: Long,
        startTimestamp: Long = 1_000L,
        wordsPerMinute: Double? = null,
    ) = VoiceRecording(
        uuid = uuid,
        walkId = walkId,
        startTimestamp = startTimestamp,
        endTimestamp = startTimestamp + 1_000L,
        durationMillis = 1_000L,
        fileRelativePath = "recordings/$uuid.wav",
        transcription = "hello",
        wordsPerMinute = wordsPerMinute,
    )

    // --- VoiceRecordingDao: uuid -> WalkLite -----------------------------------

    @Test
    fun `recordingWalkLiteIndex joins recordings to their owning walk`() = runTest {
        val walkId = newWalk(startTimestamp = 5_000L, intention = "presence", weatherCondition = "SUNNY")
        voiceRecordingDao.insert(recording("r1", walkId))

        val rows = voiceRecordingDao.recordingWalkLiteIndex()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("r1", row.uuid)
        assertEquals(walkId, row.walkId)
        assertEquals(5_000L, row.startTimestamp)
        assertEquals("presence", row.intention)
        assertEquals("SUNNY", row.weatherCondition)
    }

    @Test
    fun `recordingWalkLiteIndex spans multiple recordings on the same walk`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        voiceRecordingDao.insert(recording("r1", walkId))
        voiceRecordingDao.insert(recording("r2", walkId))

        val uuids = voiceRecordingDao.recordingWalkLiteIndex().map { it.uuid }.toSet()

        assertEquals(setOf("r1", "r2"), uuids)
    }

    @Test
    fun `recordingWalkLiteIndex is empty when no recordings exist`() = runTest {
        newWalk(startTimestamp = 1_000L)
        assertTrue(voiceRecordingDao.recordingWalkLiteIndex().isEmpty())
    }

    // --- VoiceRecordingDao: uuid -> own timestamp (distinct granularity) ------

    @Test
    fun `recordingTimestampIndex reports each recording's own start timestamp, not its walk's`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        voiceRecordingDao.insert(recording("r1", walkId, startTimestamp = 9_999L))

        val row = voiceRecordingDao.recordingTimestampIndex().single()

        assertEquals("r1", row.uuid)
        assertEquals(9_999L, row.startTimestamp)
    }

    // --- VoiceRecordingDao: uuid -> wordsPerMinute -----------------------------

    @Test
    fun `recordingPaceIndex reports wordsPerMinute per recording, nullable`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        voiceRecordingDao.insert(recording("r1", walkId, wordsPerMinute = 142.5))
        voiceRecordingDao.insert(recording("r2", walkId, wordsPerMinute = null))

        val rows = voiceRecordingDao.recordingPaceIndex().associateBy { it.uuid }

        assertEquals(142.5, rows.getValue("r1").wordsPerMinute!!, 1e-9)
        assertNull(rows.getValue("r2").wordsPerMinute)
    }

    // --- WalkDao: WalkLite by walk id -------------------------------------------

    @Test
    fun `getWalkLite projects id, start timestamp, intention, weather condition`() = runTest {
        val walkId = newWalk(startTimestamp = 42_000L, intention = "release", weatherCondition = "CLOUDY")

        val row = walkDao.getWalkLite(walkId)

        assertEquals(walkId, row!!.walkId)
        assertEquals(42_000L, row.startTimestamp)
        assertEquals("release", row.intention)
        assertEquals("CLOUDY", row.weatherCondition)
    }

    @Test
    fun `getWalkLite returns null for an unknown walk id`() = runTest {
        assertNull(walkDao.getWalkLite(999_999L))
    }

    @Test
    fun `RecordingWalkLiteRow converts to core WalkLite with Instant conversion`() = runTest {
        val walkId = newWalk(startTimestamp = 7_000L, intention = "presence")
        voiceRecordingDao.insert(recording("r1", walkId))

        val row = voiceRecordingDao.recordingWalkLiteIndex().single()
        val walkLite = row.toWalkLite()

        assertEquals(walkId, walkLite.walkId)
        assertEquals(java.time.Instant.ofEpochMilli(7_000L), walkLite.startedAt)
        assertEquals("presence", walkLite.intention)
    }
}
