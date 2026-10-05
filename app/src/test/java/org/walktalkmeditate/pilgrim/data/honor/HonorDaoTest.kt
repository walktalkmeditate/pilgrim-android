// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase

/** The Honor tables: writes that never clobber, guards that win once, and what outlives a walk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorDaoTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var dao: HonorDao
    private var walkId = 0L
    private val walkUuid = "7b1a2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.honorDao()
        walkId = db.walkDao().insert(Walk(uuid = walkUuid, startTimestamp = 1_000L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session() = HonorSessionEntity(
        walkId = walkId,
        wayId = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
        sourceKind = HonorSourceKind.OWN_WALK,
        voicesEnabled = true,
        softTapEnabled = false,
    )

    private fun engine(progress: Double) = HonorEngineState(
        walkId = walkId,
        startFrac = 0.1,
        anchoredByFallback = false,
        anchorActiveSeconds = 42.0,
        companionT0Seconds = 30.0,
        progressFrac = progress,
        progressHighWater = progress,
        walkedFrac = progress - 0.1,
        offWaySince = null,
        offWayActiveSeconds = 0.0,
        lastReacquireAttempt = null,
        softTapSince = null,
        softTapArmed = true,
        arrivalInsideFixes = 2,
        lastNoticeSeconds = null,
    )

    @Test
    fun `a new session starts walking with no anchor, no finish, and generation zero`() = runTest {
        dao.insertSession(session())

        val row = dao.getSession(walkId)!!
        assertEquals(HonorPhase.WALKING, row.phase)
        assertNull(row.startFrac)
        assertNull(row.finishKind)
        assertEquals(0L, row.gateGeneration)
        assertEquals(1.0, row.voiceRate, 0.0)
    }

    @Test
    fun `a second session insert for the same walk fails rather than replacing the first`() = runTest {
        dao.insertSession(session())
        dao.bumpGateGeneration(walkId)

        val failure = runCatching { dao.insertSession(session().copy(wayId = "share:aaaaaaaaaa")) }.exceptionOrNull()

        assertTrue("$failure", failure is SQLiteConstraintException)
        assertEquals(1L, dao.getSession(walkId)!!.gateGeneration)
    }

    @Test
    fun `an engine update touches only the engine's columns`() = runTest {
        dao.insertSession(session())
        dao.recordFinishKind(walkId, HonorFinishKind.CLEAN)
        dao.bumpGateGeneration(walkId)
        dao.applyCommandSeq(walkId, 7)
        dao.updateVoiceState(
            HonorVoiceState(
                walkId = walkId,
                playingMomentId = "voice-2",
                voicePaused = true,
                voiceStartedAt = 5_000L,
                voiceStartOffsetMillis = 0L,
                voicePauseOffsetMillis = 3_000L,
                voiceRate = 1.5,
            ),
        )

        assertEquals(1, dao.updateEngineState(engine(progress = 0.4)))

        val row = dao.getSession(walkId)!!
        assertEquals(0.4, row.progressFrac, 0.0)
        assertEquals(2, row.arrivalInsideFixes)
        assertEquals(HonorFinishKind.CLEAN, row.finishKind)
        assertEquals(1L, row.gateGeneration)
        assertEquals(7L, row.lastCommandSeq)
        assertEquals("voice-2", row.playingMomentId)
        assertEquals(1.5, row.voiceRate, 0.0)
        assertEquals(HonorPhase.WALKING, row.phase)
    }

    @Test
    fun `arrival's compare-and-set wins once and keeps the first numbers`() = runTest {
        dao.insertSession(session())

        assertEquals(1, dao.recordArrival(walkId, theirSeconds = 2_400.0, yourSeconds = 2_100.0, walkedMeters = 9_000.0))
        assertEquals(0, dao.recordArrival(walkId, theirSeconds = 1.0, yourSeconds = 1.0, walkedMeters = 1.0))

        val row = dao.getSession(walkId)!!
        assertEquals(HonorPhase.ARRIVED, row.phase)
        assertEquals(2_400.0, row.arrivalTheirSeconds!!, 0.0)
        assertEquals(2_100.0, row.arrivalYourSeconds!!, 0.0)
        assertEquals(9_000.0, row.arrivalWalkedMeters!!, 0.0)
    }

    // Schema 12: a stage's notices, its quiet clock, and its identity

    @Test
    fun `an engine update writes the quiet clock and leaves the stage identity alone`() = runTest {
        dao.insertSession(session())
        dao.recordStageIdentity(walkId, "camino-frances", index = 3, name = "Larrasoaña to Pamplona", distanceKm = 15.6)

        dao.updateEngineState(engine(progress = 0.4).copy(lastNoticeSeconds = 1_234.4))

        val row = dao.getSession(walkId)!!
        assertEquals(1_234.4, row.lastNoticeSeconds!!, 0.0)
        assertEquals(
            listOf<Any?>("camino-frances", 3, "Larrasoaña to Pamplona", 15.6),
            listOf(row.stageRouteId, row.stageIndex, row.stageName, row.stageDistanceKm),
        )
    }

    @Test
    fun `a new session row is no stage, with nothing spoken and the first notice free`() = runTest {
        dao.insertSession(session())

        val row = dao.getSession(walkId)!!
        assertEquals(
            listOf<Any?>(null, null, null, null, null, null),
            listOf(
                row.lastNoticeSeconds, row.arrivalWalkedMeters, row.stageRouteId,
                row.stageIndex, row.stageName, row.stageDistanceKm,
            ),
        )
        assertTrue(dao.getNotices(walkId).isEmpty())
    }

    @Test
    fun `a notice is spoken once, and a second insert keeps the first`() = runTest {
        val first = HonorNoticeEntity(walkId, HonorNoticeKind.WATER, "wp-osm-water-node1", meters = 250.5, firedAt = 5_000L)
        dao.insertNotice(first)
        dao.insertNotice(first.copy(meters = 90.0, firedAt = 9_000L))

        assertEquals(listOf(first), dao.getNotices(walkId))
    }

    @Test
    fun `a notice of a kind this build doesn't know reads as unknown`() = runTest {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO honor_notices (walk_id, kind, ref_id, meters, fired_at) " +
                "VALUES ($walkId, 'STAMP', 'wp-temple-10', 1.0, 2)",
        )

        assertEquals(HonorNoticeKind.UNKNOWN, dao.getNotices(walkId).single().kind)
    }

    @Test
    fun `the first finish kind wins`() = runTest {
        dao.insertSession(session())

        assertEquals(1, dao.recordFinishKind(walkId, HonorFinishKind.CLEAN))
        assertEquals(0, dao.recordFinishKind(walkId, HonorFinishKind.RECOVERED))
        assertEquals(HonorFinishKind.CLEAN, dao.getSession(walkId)!!.finishKind)
    }

    @Test
    fun `a replayed command sequence id is refused`() = runTest {
        dao.insertSession(session())

        assertEquals(1, dao.applyCommandSeq(walkId, 3))
        assertEquals(0, dao.applyCommandSeq(walkId, 3))
        assertEquals(0, dao.applyCommandSeq(walkId, 2))
        assertEquals(1, dao.applyCommandSeq(walkId, 4))
        assertEquals(4L, dao.getSession(walkId)!!.lastCommandSeq)
    }

    @Test
    fun `a card's touch only turns on and its dismissal only moves on, whichever is set first`() = runTest {
        dao.insertSession(session())
        dao.markCardDismissed(walkId, "voice-1", atMillis = 5_000L)
        dao.markCardTouched(walkId, "voice-1")
        dao.markCardDismissed(walkId, "voice-1", atMillis = 3_000L)

        assertEquals(
            HonorCardStateEntity(walkId, "voice-1", dismissedAtMillis = 5_000L, touched = true),
            dao.getCardStates(walkId).single(),
        )
    }

    @Test
    fun `a later dismissal replaces the earlier one`() = runTest {
        dao.insertSession(session())
        dao.markCardDismissed(walkId, "voice-1", atMillis = 5_000L)
        dao.markCardDismissed(walkId, "voice-1", atMillis = 9_000L)

        assertEquals(9_000L, dao.getCardStates(walkId).single().dismissedAt)
    }

    @Test
    fun `a card tap on a finished or vanished walk writes nothing and does not throw`() = runTest {
        dao.insertSession(session())
        db.walkDao().getById(walkId)!!.let { db.walkDao().update(it.copy(endTimestamp = 2_000L)) }

        assertEquals(false, dao.markCardTouched(walkId, "voice-1"))
        assertTrue(dao.getCardStates(walkId).isEmpty())

        db.walkDao().deleteById(walkId)
        assertEquals(false, dao.markCardDismissed(walkId, "voice-1", atMillis = 3_000L))
    }

    @Test
    fun `the live-session guard counts only a session on an unfinished walk`() = runTest {
        assertEquals(0, dao.countLiveSessionOnUnfinishedWalk(walkId))
        dao.insertSession(session())
        assertEquals(1, dao.countLiveSessionOnUnfinishedWalk(walkId))
        assertTrue(dao.finishedWalkIdsWithLiveSessions().isEmpty())

        db.walkDao().getById(walkId)!!.let { db.walkDao().update(it.copy(endTimestamp = 2_000L)) }

        assertEquals(0, dao.countLiveSessionOnUnfinishedWalk(walkId))
        assertEquals(listOf(walkId), dao.finishedWalkIdsWithLiveSessions())
    }

    @Test
    fun `moment and card rows upsert in place`() = runTest {
        dao.insertSession(session())
        dao.upsertMomentState(HonorMomentStateEntity(walkId, "voice-1", reachedAt = 5_000L, queuePosition = 0))
        dao.upsertMomentState(
            HonorMomentStateEntity(walkId, "voice-1", reachedAt = 5_000L, voiceStartedAt = 6_000L, heard = true),
        )
        dao.markCardTouched(walkId, "voice-1")
        dao.markCardDismissed(walkId, "voice-1", atMillis = 7_000L)

        val moment = dao.getMomentStates(walkId).single()
        assertTrue(moment.heard)
        assertNull(moment.queuePosition)
        assertEquals(6_000L, moment.voiceStartedAt)
        assertEquals(
            HonorCardStateEntity(walkId, "voice-1", dismissedAtMillis = 7_000L, touched = true),
            dao.getCardStates(walkId).single(),
        )
    }

    @Test
    fun `the voice end round-trips`() = runTest {
        dao.upsertMomentState(
            HonorMomentStateEntity(walkId, "voice-1", voiceEndedAt = 9_000L, voiceEnd = HonorVoiceEnd.SKIPPED),
        )

        assertEquals(HonorVoiceEnd.SKIPPED, dao.getMomentStates(walkId).single().voiceEnd)
    }

    @Test
    fun `deleteLiveRows removes the walk's live rows and keeps its marker`() = runTest {
        dao.insertSession(session())
        dao.upsertMomentState(HonorMomentStateEntity(walkId, "voice-1"))
        dao.markCardTouched(walkId, "voice-1")
        dao.insertNotice(HonorNoticeEntity(walkId, HonorNoticeKind.WATER, "w1", meters = 200.0, firedAt = 5_000L))
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN))

        dao.deleteLiveRows(walkId)

        assertNull(dao.getSession(walkId))
        assertTrue(dao.getMomentStates(walkId).isEmpty())
        assertTrue(dao.getCardStates(walkId).isEmpty())
        assertTrue(dao.getNotices(walkId).isEmpty())
        assertNotNull(dao.getMarker(walkUuid))
    }

    @Test
    fun `deleting the walk row cascades the live rows but not the marker`() = runTest {
        dao.insertSession(session())
        dao.upsertMomentState(HonorMomentStateEntity(walkId, "voice-1"))
        dao.markCardTouched(walkId, "voice-1")
        dao.insertNotice(HonorNoticeEntity(walkId, HonorNoticeKind.WATER, "w1", meters = 200.0, firedAt = 5_000L))
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN))

        db.walkDao().deleteById(walkId)

        assertNull(dao.getSession(walkId))
        assertTrue(dao.getMomentStates(walkId).isEmpty())
        assertTrue(dao.getCardStates(walkId).isEmpty())
        assertTrue(dao.getNotices(walkId).isEmpty())
        assertEquals(HonorFinishKind.CLEAN, dao.getMarker(walkUuid)!!.finishKind)
    }

    @Test
    fun `a repeated marker insert keeps the first`() = runTest {
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN))
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid, finishedAt = 12_000L, finishKind = HonorFinishKind.RECOVERED))

        assertEquals(
            HonorWalkMarkerEntity(walkUuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN),
            dao.getMarker(walkUuid),
        )
    }

    @Test
    fun `a session needs its walk row`() = runTest {
        val failure = runCatching { dao.insertSession(session().copy(walkId = walkId + 99)) }.exceptionOrNull()

        assertTrue("$failure", failure is SQLiteConstraintException)
    }
}
