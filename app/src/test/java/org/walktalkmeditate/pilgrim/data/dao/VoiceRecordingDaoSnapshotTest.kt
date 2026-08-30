// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * U6: the backfill's own snapshot query — (uuid, transcription) pairs for
 * every TRANSCRIBED recording, the raw material [ThreadsBackfillRunner]
 * sweeps. Mirrors iOS `DataManager.transcribedRecordingsSnapshot()`'s
 * narrow two-column projection (parity spec BEH-61).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingDaoSnapshotTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var dao: VoiceRecordingDao
    private lateinit var walkDao: WalkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.voiceRecordingDao()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newWalk(): Long = walkDao.insert(Walk(startTimestamp = 1_000L))

    private fun recording(
        uuid: String,
        walkId: Long,
        transcription: String?,
        path: String,
    ) = VoiceRecording(
        uuid = uuid,
        walkId = walkId,
        startTimestamp = 1_000L,
        endTimestamp = 2_000L,
        durationMillis = 1_000L,
        fileRelativePath = path,
        transcription = transcription,
    )

    @Test
    fun `transcribedSnapshot returns only recordings with a non-null transcription`() = runTest {
        val walkId = newWalk()
        dao.insert(recording("u-transcribed", walkId, "hello world", "recordings/w/u-transcribed.wav"))
        dao.insert(recording("u-pending", walkId, null, "recordings/w/u-pending.wav"))

        val snapshot = dao.transcribedSnapshot()

        assertEquals(1, snapshot.size)
        assertEquals("u-transcribed", snapshot.single().uuid)
        assertEquals("hello world", snapshot.single().transcription)
    }

    @Test
    fun `transcribedSnapshot is empty when no recordings are transcribed yet`() = runTest {
        val walkId = newWalk()
        dao.insert(recording("u-pending", walkId, null, "recordings/w/u-pending.wav"))

        assertTrue(dao.transcribedSnapshot().isEmpty())
    }

    @Test
    fun `transcribedSnapshot spans multiple walks`() = runTest {
        val walkA = newWalk()
        val walkB = newWalk()
        dao.insert(recording("a1", walkA, "from walk a", "recordings/a/a1.wav"))
        dao.insert(recording("b1", walkB, "from walk b", "recordings/b/b1.wav"))

        val uuids = dao.transcribedSnapshot().map { it.uuid }.toSet()
        assertEquals(setOf("a1", "b1"), uuids)
    }
}
