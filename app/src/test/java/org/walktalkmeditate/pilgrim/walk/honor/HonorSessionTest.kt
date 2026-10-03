// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.honor.BeginHonorWalk
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.STAGE_ID
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.water

/**
 * The `:tracker` Honor session against a real Room, Ways store, and
 * controller, with fakes at the audio ports (U18 owns the audio itself).
 * Each test drives the session's inputs and waits for its actor to drain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSessionTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private val way = HonorHarness.way()
    private val dao get() = h.db.honorDao()

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
        h.writeRecordings(way)
    }

    @After
    fun tearDown() {
        h.close()
    }

    private suspend fun HonorSession.begin(
        walk: Walk,
        initialFix: LocationPoint? = null,
        controller: WalkControllerImpl = h.controller,
        scope: CoroutineScope = h.serviceScope,
    ): HonorSessionStart = start(scope, walk.id, controller.state, initialFix).also { awaitIdle() }

    private suspend fun HonorSession.walkTo(vararg lons: Double) {
        for (lon in lons) {
            h.clock.millis += 1_000
            onFix(fix(lon, h.clock.millis))
        }
        awaitIdle()
    }

    private suspend fun HonorSession.send(seq: Long, command: HonorCommand) {
        command(seq, command)
        awaitIdle()
    }

    private fun trailhead() = fix(0.0, h.clock.millis)

    private suspend fun row(walk: Walk, momentId: String): HonorMomentStateEntity =
        dao.getMomentStates(walk.id).single { it.momentId == momentId }

    // Begin and persistence

    @Test
    fun `Begin feeds the trailhead fix before the gates open, as iOS's bind does`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)

        assertEquals(HonorSessionStart.Started(revived = false), session.begin(walk, trailhead()))

        // The golden traces' straight-42n, inputs 0–3: start on the default open
        // gates, pause on bind's not-yet-recording status, resume when it lands.
        assertEquals(listOf("duck", "play voice-1 1.0", "pause", "resume"), ports.voiceCalls())
        val voice = row(walk, "voice-1")
        assertTrue(voice.heard)
        assertNotNull(voice.reachedAt)
        assertNotNull(voice.voiceStartedAt)
        assertNull(voice.queuePosition)
        val session1 = dao.getSession(walk.id)!!
        assertEquals("a start bumps the gate generation", 1L, session1.gateGeneration)
        assertEquals(0.0, session1.startFrac!!, 0.0)
        assertEquals("voice-1", session1.playingMomentId)
        assertFalse(session1.voicePaused)
        assertEquals(HonorGlanceState(1_100, isOnWay = true, isArrived = false), session.glance.value)
    }

    @Test
    fun `every row a voice start changes has committed before its first sound`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        var rowsAtPlay: List<HonorMomentStateEntity> = emptyList()
        var sessionAtPlay: HonorSessionEntity? = null
        ports.onPlay = {
            runBlocking {
                rowsAtPlay = dao.getMomentStates(walk.id)
                sessionAtPlay = dao.getSession(walk.id)
            }
        }

        h.newSession(ports).begin(walk, trailhead())

        assertTrue(rowsAtPlay.single { it.momentId == "voice-1" }.heard)
        assertEquals("voice-1", sessionAtPlay!!.playingMomentId)
    }

    @Test
    fun `a voice whose file is gone is never heard and hands the turn straight back`() = runBlocking {
        File(h.filesRoot, "recordings/${HonorHarness.SOURCE_UUID}/voice-1.wav").delete()
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)

        session.begin(walk, trailhead())

        assertEquals(emptyList<String>(), ports.voiceCalls())
        val voice = row(walk, "voice-1")
        assertNotNull(voice.reachedAt)
        assertFalse(voice.heard)
        assertNull(voice.voiceStartedAt)

        session.walkTo(0.001, 0.002, 0.003, 0.004, 0.005)
        assertEquals("the next voice plays at its spot", listOf("duck", "play voice-2 1.0"), ports.voiceCalls())
    }

    @Test
    fun `a voice that ends on its own marks its end, and the next waiting voice starts`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        session.walkTo(0.001, 0.002, 0.003, 0.004, 0.005)
        assertEquals("voice-2 waits behind voice-1", 0, row(walk, "voice-2").queuePosition)
        ports.clear()

        ports.finishLatest()
        session.awaitIdle()

        assertEquals(listOf("restore", "duck", "play voice-2 1.0"), ports.voiceCalls())
        assertEquals(HonorVoiceEnd.FINISHED, row(walk, "voice-1").voiceEnd)
        assertTrue(row(walk, "voice-2").heard)
        assertNull(row(walk, "voice-2").queuePosition)
    }

    @Test
    fun `a play that fails still counted as heard, and ends as failed`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())

        ports.failLatest()
        session.awaitIdle()

        val voice = row(walk, "voice-1")
        assertTrue(voice.heard)
        assertEquals(HonorVoiceEnd.FAILED, voice.voiceEnd)
        assertNull(dao.getSession(walk.id)!!.playingMomentId)
    }

    @Test
    fun `an engine voice the player refuses as it is handed over ends failed at its start`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())

        ports.failLatest(atHandOff = true)
        session.awaitIdle()

        assertEquals(HonorVoiceEnd.FAILED_AT_START, row(walk, "voice-1").voiceEnd)
    }

    @Test
    fun `a replay the player refuses at hand-off ends plainly failed, as iOS's togglePlayback raises no card`() =
        runBlocking {
            val walk = h.startHonorWalk(way)
            val ports = FakePorts()
            val session = h.newSession(ports)
            session.begin(walk, trailhead())
            session.send(1, HonorCommand.TogglePlayback("voice-3"))

            ports.failLatest(atHandOff = true)
            session.awaitIdle()

            assertEquals(HonorVoiceEnd.FAILED, row(walk, "voice-3").voiceEnd)
        }

    @Test
    fun `a moment reached persists, then vibrates`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())

        session.walkTo(0.001, 0.002, 0.003)

        assertNotNull(row(walk, "waypoint-1").reachedAt)
        assertEquals(1, ports.calls.count { it == "haptic moment" })
    }

    // Gates

    @Test
    fun `pausing the walk pauses the voice in place, and resuming resumes it`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        h.controller.pauseWalk()
        session.awaitIdle()
        assertEquals(listOf("pause"), ports.voiceCalls())
        assertTrue(dao.getSession(walk.id)!!.voicePaused)

        h.controller.resumeWalk()
        session.awaitIdle()
        assertEquals(listOf("pause", "resume"), ports.voiceCalls())
    }

    @Test
    fun `a sitting holds the voice as a pause does`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        h.controller.startMeditation()
        session.awaitIdle()
        h.controller.endMeditation()
        session.awaitIdle()

        assertEquals(listOf("pause", "resume"), ports.voiceCalls())
    }

    @Test
    fun `a recording in the UI holds the voice through the gate port`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        ports.gateFlow.value = HonorExternalGates(recording = true)
        session.awaitIdle()
        ports.gateFlow.value = HonorExternalGates()
        session.awaitIdle()

        assertEquals(listOf("pause", "resume"), ports.voiceCalls())
    }

    // The voice held still behind a prompt or a call (spec C §3.6–§3.7)

    @Test
    fun `a voice held behind a prompt keeps listening with its clock still, and runs on from there`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        h.clock.millis += 5_000
        ports.holdVoice(true)
        session.awaitIdle()
        val held = dao.getSession(walk.id)!!
        h.clock.millis += 30_000
        ports.holdVoice(false)
        session.awaitIdle()
        val released = dao.getSession(walk.id)!!

        assertEquals(
            listOf("voice-1", false, null, 5_000L),
            listOf(held.playingMomentId, held.voicePaused, held.voiceStartedAt, held.voiceStartOffsetMillis),
        )
        assertEquals(h.clock.millis to 5_000L, released.voiceStartedAt to released.voiceStartOffsetMillis)
        assertEquals("the arbiter holds the voice; the session only stops its clock", emptyList<String>(), ports.voiceCalls())
    }

    @Test
    fun `a voice handed over while the player holds its voice stands at its start until it sounds`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.holdVoice(true)
        session.awaitIdle()

        session.send(1, HonorCommand.TogglePlayback("voice-3"))
        h.clock.millis += 4_000
        val parked = dao.getSession(walk.id)!!
        ports.holdVoice(false)
        session.awaitIdle()

        assertEquals(null to 0L, parked.voiceStartedAt to parked.voiceStartOffsetMillis)
        assertEquals(h.clock.millis to 0L, dao.getSession(walk.id)!!.let { it.voiceStartedAt to it.voiceStartOffsetMillis })
    }

    @Test
    fun `a pause during the hold pauses where the voice was held, and the hold ending leaves it paused`() =
        runBlocking {
            val walk = h.startHonorWalk(way)
            val ports = FakePorts()
            val session = h.newSession(ports)
            session.begin(walk, trailhead())
            h.clock.millis += 5_000
            ports.holdVoice(true)
            session.awaitIdle()

            h.clock.millis += 10_000
            session.send(1, HonorCommand.PauseResume("voice-1"))
            ports.holdVoice(false)
            session.awaitIdle()

            val paused = dao.getSession(walk.id)!!
            assertEquals(true to 5_000L, paused.voicePaused to paused.voicePauseOffsetMillis)
        }

    @Test
    fun `the headphones going pause the voice where it stood, and one tap resumes it`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        h.clock.millis += 6_000
        ports.pauseLatestForRoute()
        session.awaitIdle()
        val paused = dao.getSession(walk.id)!!
        session.send(1, HonorCommand.PauseResume("voice-1"))

        assertEquals(true to 6_000L, paused.voicePaused to paused.voicePauseOffsetMillis)
        assertEquals("the player paused itself; the tap resumes it", listOf("resume"), ports.voiceCalls())
        assertFalse(dao.getSession(walk.id)!!.voicePaused)
    }

    // Commands about the voice held name the voice the walker saw

    @Test
    fun `a pause or skip for a voice no longer held changes nothing`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        session.walkTo(0.001, 0.002, 0.003, 0.004, 0.005)
        ports.finishLatest()
        session.awaitIdle()
        ports.clear()

        session.send(1, HonorCommand.PauseResume("voice-1"))
        session.send(2, HonorCommand.Skip("voice-1"))

        assertEquals(emptyList<String>(), ports.voiceCalls())
        val held = dao.getSession(walk.id)!!
        assertEquals("voice-2" to false, held.playingMomentId to held.voicePaused)
    }

    @Test
    fun `a pause that arrives after its voice ended never starts it again`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.finishLatest()
        session.awaitIdle()
        ports.clear()

        session.send(1, HonorCommand.PauseResume("voice-1"))

        assertEquals(emptyList<String>() to null, ports.voiceCalls() to dao.getSession(walk.id)!!.playingMomentId)
    }

    // Revival and cached processes

    @Test
    fun `a revival keeps the anchor, never replays the voice it lost, and plays the next at its spot`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        first.begin(walk, trailhead(), scope = firstProcess)
        first.walkTo(0.001, 0.002)
        // The process dies: nothing tears down, and the player's voice goes with it.
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()

        val revivedController = h.newController().also { it.restoreActiveWalk() }
        val ports = FakePorts()
        val second = h.newSession(ports, arrivalRecorder = revivedController)

        assertEquals(HonorSessionStart.Started(revived = true), second.begin(walk, trailhead(), revivedController))

        assertEquals("only the walk's rate is re-applied", listOf("rate 1.0"), ports.voiceCalls())
        assertEquals(HonorVoiceEnd.INTERRUPTED, row(walk, "voice-1").voiceEnd)
        val revived = dao.getSession(walk.id)!!
        assertEquals(0.0, revived.startFrac!!, 0.0)
        assertEquals(2L, revived.gateGeneration)
        assertNull(revived.playingMomentId)

        second.walkTo(0.003, 0.004, 0.005)
        assertEquals(listOf("rate 1.0", "duck", "play voice-2 1.0"), ports.voiceCalls())
        assertEquals("no re-anchor", 0.0, dao.getSession(walk.id)!!.startFrac!!, 0.0)
    }

    @Test
    fun `a cached process's next walk starts fresh, carrying nothing but the player's rate`() = runBlocking {
        val ports = FakePorts()
        val session = h.newSession(ports)
        val first = h.startHonorWalk(way)
        session.begin(first, trailhead())
        session.send(1, HonorCommand.CycleRate)
        assertEquals(1.25, dao.getSession(first.id)!!.voiceRate, 0.0)

        h.controller.finishWalk()
        session.awaitIdle()
        assertEquals(listOf("stop", "restore"), ports.voiceCalls().takeLast(2))
        assertNull(session.walkId)
        assertNull(session.glance.value)
        ports.clear()

        val second = h.startHonorWalk(way)
        assertEquals(HonorSessionStart.Started(revived = false), session.begin(second))

        // The player keeps its rate across walks while the new walk reads 1x (pilgrim-ios #105).
        assertEquals(emptyList<String>(), ports.voiceCalls())
        val fresh = dao.getSession(second.id)!!
        assertEquals(1.0, fresh.voiceRate, 0.0)
        assertNull(fresh.startFrac)
        assertNull(fresh.playingMomentId)
        assertEquals(HonorGlanceState(1_100, isOnWay = false, isArrived = false), session.glance.value)

        session.walkTo(0.0)
        assertEquals(listOf("duck", "play voice-1 1.0"), ports.voiceCalls())
    }

    // Arrival

    @Test
    fun `arrival commits once, then vibrates, and a revival after it starts arrived`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())

        session.walkTo(*(1..19).map { it * 0.0005 }.toDoubleArray())
        session.walkTo(0.0098, 0.0099, 0.01)
        session.walkTo(0.01, 0.01)

        val events = h.repository.eventsFor(walk.id)
        assertEquals(1, events.count { it.eventType == WalkEventType.HONOR_ARRIVAL })
        val waypoint = h.repository.waypointsFor(walk.id).single()
        assertEquals(HonorPersistence.ARRIVAL_WAYPOINT_ICON, waypoint.icon)
        assertEquals("Walked their way: Morning loop", waypoint.label)
        assertEquals(0.01, waypoint.longitude, 0.0)
        assertEquals(1, ports.calls.count { it == "haptic arrival" })
        assertEquals(HonorPhase.ARRIVED, dao.getSession(walk.id)!!.phase)
        assertTrue(session.glance.value!!.isArrived)

        session.stop()
        val revivedPorts = FakePorts()
        val revived = h.newSession(revivedPorts)
        revived.begin(walk)
        assertTrue("arrived before the first fix", revived.glance.value!!.isArrived)
        revived.walkTo(0.01)

        assertEquals(1, h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.HONOR_ARRIVAL })
        assertEquals(0, revivedPorts.calls.count { it == "haptic arrival" })
    }

    // A pilgrimage stage (pilgrimage-stage spec P3 §6, §9, §11, §12; owner decision 2)

    private val stage = HonorHarness.stage()

    private suspend fun notices(walk: Walk): List<HonorNoticeEntity> = dao.getNotices(walk.id)

    /** Where the engine puts the walker at [lon]: the stage's own geometry, as iOS's `ahead` measures it. */
    private fun aheadOfWater(markLon: Double, walkerLon: Double): Double =
        (markLon - walkerLon) / HonorHarness.END_LON * WayGeometry(stage.route).totalMeters

    @Test
    fun `a stage's water notice commits before its haptic plays`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        val ports = FakePorts()
        var noticesAtHaptic: List<HonorNoticeEntity>? = null
        var clockAtHaptic: Double? = null
        ports.onWaterAhead = {
            runBlocking {
                noticesAtHaptic = notices(walk)
                clockAtHaptic = dao.getSession(walk.id)!!.lastNoticeSeconds
            }
        }
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        assertTrue("334 m out, too far yet", notices(walk).isEmpty())

        session.walkTo(0.001)

        val notice = noticesAtHaptic!!.single()
        assertEquals("wp-osm-water-node1" to h.clock.millis, notice.refId to notice.firedAt)
        assertEquals(aheadOfWater(0.003, 0.001), notice.meters, 1e-6)
        assertEquals("the Begin's clock, the first notice free", 0.0, clockAtHaptic!!, 0.0)
        assertEquals(1, ports.calls.count { it == "haptic water" })
    }

    /** A first `:tracker` speaks node 1 at 0.001 and is killed; a second revives the walk from Room on [ports]. */
    private suspend fun revivedAfterNodeOne(walk: Walk, ports: FakePorts): HonorSession {
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        first.begin(walk, trailhead(), scope = firstProcess)
        first.walkTo(0.001)
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()
        val revivedController = h.newController().also { it.restoreActiveWalk() }
        val revived = h.newSession(ports, arrivalRecorder = revivedController)
        assertEquals(HonorSessionStart.Started(revived = true), revived.begin(walk, trailhead(), revivedController))
        return revived
    }

    @Test
    fun `a revival keeps the water spoken, so node 1 stays silent with the hour out and still ahead`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        val ports = FakePorts()
        val revived = revivedAfterNodeOne(walk, ports)
        val spoken = notices(walk).single()

        h.clock.millis += 3_600_000
        revived.tickNow()
        revived.walkTo(0.002)

        assertEquals("node 1, 111 m ahead, never speaks twice", 0, ports.calls.count { it == "haptic water" })
        assertEquals("the quiet clock still reads node 1's", 0.0, dao.getSession(walk.id)!!.lastNoticeSeconds!!, 0.0)

        revived.walkTo(0.003, 0.004, 0.005, 0.006)

        assertEquals("the hour is out: node 2 speaks", listOf(spoken.refId, "wp-osm-water-node2"), notices(walk).map { it.refId })
        assertEquals(spoken, notices(walk).first())
        assertEquals(1, ports.calls.count { it == "haptic water" })
    }

    @Test
    fun `a revival keeps the quiet hour, so node 2 in reach waits for it`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        val ports = FakePorts()
        val revived = revivedAfterNodeOne(walk, ports)
        val spoken = notices(walk)

        revived.walkTo(0.002, 0.003, 0.004, 0.005, 0.006)

        assertEquals("node 2, 222 m ahead inside the hour, stays silent", spoken, notices(walk))
        assertEquals(0, ports.calls.count { it == "haptic water" })

        h.clock.millis += 3_600_000
        revived.tickNow()
        revived.walkTo(0.006)

        assertEquals(listOf("wp-osm-water-node1", "wp-osm-water-node2"), notices(walk).map { it.refId })
        assertEquals(1, ports.calls.count { it == "haptic water" })
    }

    // iOS `testWaterAheadBorrowsTheCaptionLineAndNothingElse` (`PilgrimageStageWalkTests.swift:356-367@7c200bf`),
    // its "nothing else" half (P3 §6.1); the caption is U39's.
    @Test
    fun `a water notice writes nothing else, no moment or card row, no walk event, no waypoint`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        val session = h.newSession(FakePorts())
        session.begin(walk, trailhead())
        val momentRows = dao.getMomentStates(walk.id)
        val events = h.repository.eventsFor(walk.id)

        session.walkTo(0.001)

        assertEquals("the notice spoke", listOf("wp-osm-water-node1"), notices(walk).map { it.refId })
        assertEquals(momentRows, dao.getMomentStates(walk.id))
        assertTrue("a mark is never a card", dao.getCardStates(walk.id).isEmpty())
        assertEquals(events, h.repository.eventsFor(walk.id))
        assertTrue(h.repository.waypointsFor(walk.id).isEmpty())
    }

    // iOS `testTheEngineReportsWhetherItEverAnchoredOnTheWay` (`PilgrimageStageWalkTests.swift:115-127@7c200bf`),
    // the row twin of `HonorEngineTest`'s port (P3 §10.2): what U36's outcome will read.
    @Test
    fun `a stage begun a kilometre north of the line reads the fallback on its row until a fix joins it`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        assertNull("no fix yet", dao.getSession(walk.id)!!.startFrac)
        val session = h.newSession(FakePorts())

        session.begin(walk, fix(0.0, h.clock.millis, lat = 0.01))
        val approach = dao.getSession(walk.id)!!
        session.walkTo(0.001)
        val joined = dao.getSession(walk.id)!!

        assertEquals("the frac-0 fallback is not a stage joined", 0.0 to true, approach.startFrac to approach.anchoredByFallback)
        assertFalse(joined.anchoredByFallback)
        assertEquals(0.1, joined.startFrac!!, 1e-9)
    }

    // Correctness review P3-1: a vibration still playing is cut by the next one, where iOS layers the two.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `water on arrival's own fix taps once arrival's three taps have played`() = runTest {
        val plaza = water("wp-osm-water-plaza", 0.0099)
        val walk = h.startHonorWalk(HonorHarness.stage(marks = listOf(water("wp-osm-water-node1", 0.003), plaza)))
        val ports = FakePorts()
        val played = mutableListOf<Pair<String, Long>>()
        ports.onHaptic = { name -> played += name to testScheduler.currentTime }
        val session = h.newSession(ports, sessionDispatcher = StandardTestDispatcher(testScheduler))
        try {
            session.begin(walk, trailhead())
            session.walkTo(*(1..19).map { it * 0.0005 }.toDoubleArray())
            // Inside the end's 30 m twice, the plaza 11 m ahead and silent in node 1's hour.
            session.walkTo(0.0098, 0.0098)
            h.clock.millis += 3_600_000
            session.tickNow()
            session.walkTo(0.0098)

            assertEquals("arrival's taps play, alone", "haptic arrival", ports.calls.last())
            testScheduler.advanceTimeBy(FakePorts.ARRIVAL_MILLIS)
            assertEquals("until they end", "haptic arrival", ports.calls.last())
            testScheduler.runCurrent()
            val (arrival, plazaTap) = played.takeLast(2)
            assertEquals("water" to arrival.second + FakePorts.ARRIVAL_MILLIS, plazaTap)

            assertEquals(HonorPhase.ARRIVED, dao.getSession(walk.id)!!.phase)
            assertEquals(listOf("wp-osm-water-node1", plaza.id), notices(walk).map { it.refId })
        } finally {
            withContext(NonCancellable) { session.stop() }
        }
    }

    @Test
    fun `a cached tracker's second walk on the same stage starts with no water spoken`() = runBlocking {
        val ports = FakePorts()
        val session = h.newSession(ports)
        val first = h.startHonorWalk(stage)
        session.begin(first, trailhead())
        session.walkTo(0.001)
        h.controller.finishWalk()
        session.awaitIdle()
        assertTrue("the finalize took the notices with the live rows", notices(first).isEmpty())

        val second = h.startHonorWalk(stage)
        session.begin(second, trailhead())
        session.walkTo(0.001)

        val notice = notices(second).single()
        assertEquals("wp-osm-water-node1" to h.clock.millis, notice.refId to notice.firedAt)
        assertEquals(2, ports.calls.count { it == "haptic water" })
    }

    @Test
    fun `a stage start never schedules a soft tap, whatever the preference says`() = runBlocking {
        h.store.save(stage)
        val begin = BeginHonorWalk(
            repository = h.repository,
            wayStore = h.store,
            walkController = h.controller,
            recordingFiles = VoiceRecordingFileSystem(ApplicationProvider.getApplicationContext<Context>()),
            releaseFlags = FixedReleaseFlags(honor = true),
            ioDispatcher = Dispatchers.IO,
            mintWalkUuid = { "11111111-2222-4333-8444-555555555555" },
            zone = { ZoneId.of("UTC") },
            locale = { Locale.US },
        )
        val started = begin(
            BeginHonorWalk.Request(
                way = HonorWayChoice.Stored(STAGE_ID),
                intention = null,
                settings = HonorSettings(voicesEnabled = true, softTapEnabled = true),
            ),
        ) as BeginHonorWalk.Result.Started
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(started.walk, trailhead())

        walkOffTheWayForTwoMinutes(session)

        assertFalse(dao.getSession(started.walk.id)!!.softTapEnabled)
        assertEquals(0, ports.calls.count { it == "haptic softTap" })
    }

    @Test
    fun `an own walk with the soft tap on taps two minutes off the way, the stage case's control`() = runBlocking {
        val walk = h.startHonorWalk(way, settings = HonorSettings(voicesEnabled = true, softTapEnabled = true))
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())

        walkOffTheWayForTwoMinutes(session)

        assertEquals(1, ports.calls.count { it == "haptic softTap" })
    }

    /** Onto the line, then 280 m north of it for two minutes and a second, each fix read at its own time. */
    private suspend fun walkOffTheWayForTwoMinutes(session: HonorSession) {
        session.walkTo(0.001)
        for (step in 0..2) {
            h.clock.millis += if (step == 0) 1_000 else 60_500
            session.onFix(fix(0.001, h.clock.millis, lat = 280.0 / 111_320))
            session.awaitIdle()
        }
    }

    @Test
    fun `the stage walked lands on the session row at Start`() = runBlocking {
        val walk = h.startHonorWalk(stage)

        h.newSession().begin(walk, trailhead())

        val row = dao.getSession(walk.id)!!
        assertEquals(
            listOf<Any?>("camino-frances", 4, "Larrasoaña to Pamplona", 15.6),
            listOf(row.stageRouteId, row.stageIndex, row.stageName, row.stageDistanceKm),
        )
    }

    @Test
    fun `an own walk's row names no stage`() = runBlocking {
        val walk = h.startHonorWalk(way)

        h.newSession().begin(walk, trailhead())

        assertNull(dao.getSession(walk.id)!!.stageRouteId)
    }

    @Test
    fun `a stage walks the copy staged at Begin, revival included, not the package redrawn since`() = runBlocking {
        h.store.save(stage)
        val walk = h.startHonorWalk(stage)
        val redrawn = HonorHarness.stage(marks = listOf(water("wp-osm-water-node9", 0.0015)), title = "Redrawn")
        h.store.save(redrawn)
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        first.begin(walk, trailhead(), scope = firstProcess)
        first.walkTo(0.001)
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()
        h.store.save(redrawn.copy(marks = emptyList()))

        val revivedController = h.newController().also { it.restoreActiveWalk() }
        val second = h.newSession(FakePorts(), arrivalRecorder = revivedController)
        second.begin(walk, trailhead(), revivedController)
        h.clock.millis += 3_600_000
        second.tickNow()
        second.walkTo(0.002, 0.003, 0.004, 0.005, 0.006)

        assertEquals(listOf("wp-osm-water-node1", "wp-osm-water-node2"), notices(walk).map { it.refId })
        assertEquals("Larrasoaña to Pamplona", dao.getSession(walk.id)!!.stageName)
    }

    @Test
    fun `a stage's arrival freezes the walked distance while the walk goes on`() = runBlocking {
        val walk = h.startHonorWalk(stage)
        val session = h.newSession(FakePorts())
        session.begin(walk, trailhead())
        session.walkTo(*(1..19).map { it * 0.0005 }.toDoubleArray())
        session.walkTo(0.0098, 0.0098, 0.0098)
        val arrived = dao.getSession(walk.id)!!
        assertEquals(HonorPhase.ARRIVED, arrived.phase)

        session.walkTo(0.0099, 0.01)

        val later = dao.getSession(walk.id)!!
        assertTrue("the walk credits on", later.walkedFrac > arrived.walkedFrac)
        assertEquals(arrived.walkedFrac * WayGeometry(stage.route).totalMeters, later.arrivalWalkedMeters!!, 0.0)
    }

    // Finish, and writes after it

    @Test
    fun `Finish mid-voice stops the voice, and no Honor row lands after the finalize`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        h.controller.finishWalk()
        session.awaitIdle()

        assertEquals(listOf("stop", "restore"), ports.voiceCalls())
        assertNull(dao.getSession(walk.id))
        assertEquals(HonorFinishKind.CLEAN, dao.getMarker(walk.uuid)!!.finishKind)

        session.onFix(fix(0.003, h.clock.millis + 1_000))
        session.awaitIdle()
        assertTrue(dao.getMomentStates(walk.id).isEmpty())
        assertNull(dao.getSession(walk.id))
    }

    @Test
    fun `a write after the walk finished in Room is refused, and ends the session`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        h.repository.finishWalkAtomic(walk.id, endTimestamp = h.clock.millis, finishKind = HonorFinishKind.CLEAN)
        ports.clear()

        session.walkTo(0.001, 0.002, 0.003)

        assertEquals(listOf("stop", "restore"), ports.voiceCalls())
        assertNull("the waypoint's reach never landed", dao.getMomentStates(walk.id).firstOrNull { it.momentId == "waypoint-1" })
        assertNull(session.walkId)
    }

    // Refusals

    @Test
    fun `a session whose Way no longer loads is refused, and nothing starts`() = runBlocking {
        val walk = h.startHonorWalk(way)
        h.store.discardStaged(walk.uuid)
        val ports = FakePorts()
        val session = h.newSession(ports)

        assertTrue(session.begin(walk, trailhead()) is HonorSessionStart.Refused)

        assertEquals(emptyList<String>(), ports.calls)
        assertNull(session.walkId)
        assertNull(session.glance.value)
        assertEquals(0L, dao.getSession(walk.id)!!.gateGeneration)
    }

    @Test
    fun `a session whose Way id the store rejects is refused`() = runBlocking {
        val walk = h.controller.startWalk()
        dao.insertSession(
            HonorSessionEntity(
                walkId = walk.id,
                wayId = "walk:../../databases/pilgrim.db-00000000000",
                sourceKind = HonorSourceKind.OWN_WALK,
                voicesEnabled = true,
                softTapEnabled = false,
            ),
        )

        assertTrue(h.newSession().begin(walk) is HonorSessionStart.Refused)
    }

    @Test
    fun `with the release flag off nothing Honor runs`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()

        assertEquals(HonorSessionStart.Disabled, h.newSession(ports, honorEnabled = false).begin(walk, trailhead()))

        assertEquals(emptyList<String>(), ports.calls)
        assertEquals(0L, dao.getSession(walk.id)!!.gateGeneration)
    }

    @Test
    fun `an ordinary walk has no session`() = runBlocking {
        val walk = h.controller.startWalk()

        assertEquals(HonorSessionStart.NotHonor, h.newSession().begin(walk))
    }

    // Commands

    @Test
    fun `skip hands the engine its turn, and a replayed command changes nothing`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        session.walkTo(0.001, 0.002, 0.003, 0.004, 0.005)
        ports.clear()

        session.send(1, HonorCommand.Skip("voice-1"))
        assertEquals(listOf("stop", "restore", "duck", "play voice-2 1.0"), ports.voiceCalls())
        assertEquals(HonorVoiceEnd.SKIPPED, row(walk, "voice-1").voiceEnd)
        ports.clear()

        session.send(1, HonorCommand.Skip("voice-2"))
        session.send(0, HonorCommand.CycleRate)
        assertEquals(emptyList<String>(), ports.voiceCalls())
        assertEquals(1L, dao.getSession(walk.id)!!.lastCommandSeq)
    }

    @Test
    fun `the walker pauses and resumes the held voice, plays another, and cycles the rate`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        session.send(1, HonorCommand.TogglePlayback("voice-1"))
        assertTrue(dao.getSession(walk.id)!!.voicePaused)
        session.send(2, HonorCommand.TogglePlayback("voice-1"))
        session.send(3, HonorCommand.CycleRate)
        session.send(4, HonorCommand.TogglePlayback("voice-3"))
        session.send(5, HonorCommand.TogglePlayback("waypoint-1"))

        assertEquals(
            listOf("pause", "resume", "rate 1.25", "stop", "restore", "duck", "play voice-3 1.0"),
            ports.voiceCalls(),
        )
        assertEquals(HonorVoiceEnd.REPLACED, row(walk, "voice-1").voiceEnd)
        assertTrue("a replay is heard", row(walk, "voice-3").heard)
        val session1 = dao.getSession(walk.id)!!
        assertEquals("voice-3", session1.playingMomentId)
        assertEquals(1.25, session1.voiceRate, 0.0)
    }

    @Test
    fun `a scrub on a voice not held starts it, then seeks it`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        ports.clear()

        session.send(1, HonorCommand.Scrub("voice-2", fraction = 0.5))

        assertEquals(listOf("stop", "restore", "duck", "play voice-2 1.0", "seek 0.5"), ports.voiceCalls())
        assertEquals(15_000L, dao.getSession(walk.id)!!.voiceStartOffsetMillis)
    }

    @Test
    fun `your reply gives up the held voice, and when it ends the engine gets its turn back`() = runBlocking {
        h.store.save(way)
        h.store.setReply(way.id, originN = 1, relativePath = "recordings/replies/r1.wav")
        h.writeRecording("recordings/replies/r1.wav")
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, trailhead())
        session.walkTo(0.001, 0.002, 0.003, 0.004, 0.005)
        ports.clear()

        session.send(1, HonorCommand.PlayReply("voice-1"))
        assertEquals(listOf("stop", "restore", "duck", "reply r1"), ports.voiceCalls())
        assertEquals(HonorVoiceEnd.INTERRUPTED, row(walk, "voice-1").voiceEnd)
        assertNull("a reply is not the held voice", dao.getSession(walk.id)!!.playingMomentId)
        ports.clear()

        ports.finishLatest()
        session.awaitIdle()
        assertEquals(listOf("restore", "duck", "play voice-2 1.0"), ports.voiceCalls())
    }
}
