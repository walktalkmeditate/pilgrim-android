// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.walktalkmeditate.pilgrim.audio.walk.FakeUiBinder
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGate
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind
import org.walktalkmeditate.pilgrim.audio.walk.ended
import org.walktalkmeditate.pilgrim.audio.walk.started
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngine
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngineTuning
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.service.TrackerStartExtras
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.SeekIntentAction
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.SeekSessionAction
import org.walktalkmeditate.pilgrim.service.WalkTrackingStarter
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.seek.SeekTrackerHarness.Companion.chain
import org.walktalkmeditate.pilgrim.walk.seek.SeekTrackerHarness.Companion.home

/**
 * Seek on the `:tracker` mechanism (plan U25), with each process modelled
 * as its own scope over one Room: the UI's [SeekOrchestrator] boots the
 * pre-departure engine and hands it over at Begin, and `:tracker`'s
 * [SeekTrackerSession] restarts from the seek session row. A UI reclaim
 * or a tracker kill is its scope cancelled with nothing torn down. Virtual
 * time drives both engines' sonar and stillness clocks; the clock reads it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekTrackerPlacementTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var h: SeekTrackerHarness

    private val uiWalkState = MutableStateFlow<WalkState>(WalkState.Idle)
    private val uiFixes = MutableSharedFlow<LocationPoint>(extraBufferCapacity = 64)
    private val trackerFixes = MutableSharedFlow<LocationPoint>(extraBufferCapacity = 64)
    private val sessionStore = SeekSessionStore()
    private val link = RecordingLink()

    private lateinit var uiSound: SpySeekSound

    @Before
    fun setUp() {
        h = SeekTrackerHarness(dispatcher)
        uiSound = SpySeekSound("ui", h.clock, h.ops)
        // The real sonar player's raw-resource players prepare under Robolectric.
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo() }
    }

    @After
    fun tearDown() {
        h.db.close()
        ShadowMediaPlayer.resetStaticState()
    }

    /** The UI side of the link, recording what it sends and reading the row as the UI does. */
    private inner class RecordingLink(override val placement: SeekPlacement = SeekPlacement.TRACKER) : SeekTrackerLink {
        val seekAnews = mutableListOf<Unit>()
        val lateHandOffs = mutableListOf<SeekStart>()
        override fun sonarSettings() = SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.5f, soundsEnabled = true)
        override fun session(walkId: Long): Flow<SeekSessionEntity?> = h.db.seekDao().observeSession(walkId)
        override fun seekAnew() {
            seekAnews += Unit
        }
        override fun handOffLate(start: SeekStart) {
            lateHandOffs += start
        }
    }

    private fun TestScope.processScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun TestScope.startUi(scope: CoroutineScope, trackerLink: SeekTrackerLink = link): SeekOrchestrator {
        val orchestrator = SeekOrchestrator(
            walkState = uiWalkState,
            scope = scope,
            sessionStore = sessionStore,
            repository = h.repository,
            locationSource = h.locationSource(uiFixes),
            powerTiers = MutableSharedFlow<SeekPowerTier>(),
            processForeground = MutableStateFlow(true),
            senses = h.senses(uiSound, label = "ui"),
            soundsPreferences = FakeSoundsPreferencesRepository(),
            clock = h.clock,
            glancePublisher = {},
            context = context,
            trackerLink = trackerLink,
        )
        orchestrator.start()
        runCurrent()
        return orchestrator
    }

    private fun pending(chain: org.walktalkmeditate.pilgrim.domain.seek.SeekChain) = SeekPendingSession(
        chain = chain,
        durationMinutes = 30,
        tint = null,
        seededAtEpochMillis = h.clock.now(),
        intention = "find the river",
        seed = SeekTrackerHarness.SEED,
    )

    private suspend fun TestScope.emit(flow: MutableSharedFlow<LocationPoint>, at: SeekPoint, times: Int = 1) {
        repeat(times) {
            flow.emit(h.fix(at))
            runCurrent()
        }
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun intervalAt(target: SeekPoint) =
        SeekEngine.pulseIntervalMillis(SeekChainGenerator.distance(home, target), SeekPowerTier.NORMAL)

    /** The ready screen's engine, booted on [chain] and pinging once from [home]; returns the ping's time. */
    private suspend fun TestScope.readyScreen(chain: org.walktalkmeditate.pilgrim.domain.seek.SeekChain): Long {
        sessionStore.set(pending(chain))
        runCurrent()
        emit(uiFixes, home)
        advance(intervalAt(chain.clearings[0].center))
        return uiSound.pingTimes.single()
    }

    /**
     * The ready screen's engine pings once, the walker taps Start
     * [beginAfterPingMillis] later, `:tracker` starts its pipeline
     * ([pipelineStart], where the service holds the UI's gates), the walk
     * from the hand-off, and its session, and the UI sees the walk. With
     * [trackerFix], `:tracker` has its first fix before the UI does.
     * Returns the walk and the time of the ready screen's ping.
     */
    private suspend fun TestScope.beginWithHandOff(
        orchestrator: SeekOrchestrator,
        session: SeekTrackerSession,
        trackerScope: CoroutineScope,
        chain: org.walktalkmeditate.pilgrim.domain.seek.SeekChain,
        controller: org.walktalkmeditate.pilgrim.walk.WalkControllerImpl,
        beginAfterPingMillis: Long = BEGIN_AFTER_PING_MILLIS,
        trackerFix: Boolean = true,
        pipelineStart: () -> Unit = {},
    ): Pair<Walk, Long> {
        val readyPing = readyScreen(chain)
        advance(beginAfterPingMillis)

        val start = checkNotNull(orchestrator.begin())
        pipelineStart()
        val walk = h.startSeekWalk(controller, start)
        runCurrent()
        assertEquals(SeekSessionStart.Started(revived = false), session.start(trackerScope, walk.id, controller.state))
        runCurrent()
        if (trackerFix) emit(trackerFixes, home)
        uiWalkState.value = activeSeekWalk(walk)
        runCurrent()
        return walk to readyPing
    }

    private fun activeSeekWalk(walk: Walk) =
        WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = walk.startTimestamp, mode = WalkMode.Seek))

    // The Begin hand-off, through the real sonar player and ping gate

    @Test
    fun `the Begin hand-off plays the sonar once, at the ready screen's cadence, from one engine`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val orchestrator = startUi(processScope())
        val controller = h.newController()
        val tracker = RealTrackerSenses(h.clock, backgroundScope)
        val session = h.newSession(controller, tracker, trackerFixes)

        val (walk, readyPing) = beginWithHandOff(
            orchestrator, session, processScope(), chain, controller,
            pipelineStart = tracker.uiGate::holdUntilRefreshed,
        )
        tracker.uiAnswers(seq = 1)
        assertEquals("the hand-off carried the ready screen's next pulse", readyPing + interval, h.row(walk.id).nextPulseDueAt)
        assertEquals("the UI's engine stopped when the walk took its session", 1, uiSound.stopCount)

        advance(readyPing + interval - h.clock.now() - 1)
        assertEquals("no double: nothing sounds before the ping is due", emptyList<Long>(), tracker.starts)
        advance(1)

        assertEquals("the ready screen's one ping, and nothing more from the UI", listOf(readyPing), uiSound.pingTimes)
        assertEquals(
            "no gap: :tracker's first ping lands where the ready screen's next was due",
            listOf(readyPing + interval),
            tracker.starts,
        )
        advance(interval)
        assertEquals("the cadence carries on in :tracker", listOf(readyPing + interval, readyPing + 2 * interval), tracker.starts)
        assertTrue("the map flares from the row :tracker writes", orchestrator.pulse.value.token > 0)
        assertNotNull(orchestrator.fogState.value)
        assertEquals(SeekEnginePhase.GUIDING, orchestrator.enginePhase.value)
        assertTrue("Begin handed the session over, so nothing was handed late", link.lateHandOffs.isEmpty())
        session.stop()
    }

    @Test
    fun `a ping due before the UI answers the tracker's gates waits for the answer, then plays once`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val orchestrator = startUi(processScope())
        val controller = h.newController()
        val tracker = RealTrackerSenses(h.clock, backgroundScope)
        val session = h.newSession(controller, tracker, trackerFixes)

        val (_, readyPing) = beginWithHandOff(
            orchestrator, session, processScope(), chain, controller,
            beginAfterPingMillis = interval - DUE_AFTER_BEGIN_MILLIS,
            trackerFix = false,
            pipelineStart = tracker.uiGate::holdUntilRefreshed,
        )
        val due = readyPing + interval
        advance(due - h.clock.now())
        assertEquals("held for the UI's answer, not skipped behind gates that only read held", emptyList<Long>(), tracker.starts)

        advance(UI_ANSWER_MILLIS)
        tracker.uiAnswers(seq = 1)
        runCurrent()
        assertEquals("the one ping plays as the UI answers", listOf(due + UI_ANSWER_MILLIS), tracker.starts)
        assertEquals(listOf(readyPing), uiSound.pingTimes)

        advance(interval - UI_ANSWER_MILLIS)
        assertEquals("the cadence keeps the engine's clock", listOf(due + UI_ANSWER_MILLIS, due + interval), tracker.starts)
        session.stop()
    }

    @Test
    fun `a whisper the walker taps in the UI holds the tracker's sonar, as it always has`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val orchestrator = startUi(processScope())
        val controller = h.newController()
        val tracker = RealTrackerSenses(h.clock, backgroundScope)
        val session = h.newSession(controller, tracker, trackerFixes)
        val (_, readyPing) = beginWithHandOff(
            orchestrator, session, processScope(), chain, controller,
            pipelineStart = tracker.uiGate::holdUntilRefreshed,
        )
        tracker.uiAnswers(seq = 1)

        tracker.uiGate.apply(started(UiAudioGateKind.WHISPER, seq = 2, token = FakeUiBinder()))
        advance(readyPing + interval - h.clock.now())
        assertEquals("no ping over the whisper", emptyList<Long>(), tracker.starts)

        tracker.uiGate.apply(ended(UiAudioGateKind.WHISPER, seq = 3))
        advance(interval)
        assertEquals("the next pulse sounds once it has ended", listOf(readyPing + 2 * interval), tracker.starts)
        session.stop()
    }

    @Test
    fun `with no UI to answer, a revived session's first ping plays once the gates' wait passes`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val controller = h.newController()
        val walk = h.startSeekWalk(controller, h.handOff(chain))
        runCurrent()
        val first = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
        val firstScope = processScope()
        first.start(firstScope, walk.id, controller.state)
        runCurrent()
        emit(trackerFixes, home)
        firstScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        advance(2 * intervalAt(chain.clearings[0].center))

        val revivedController = h.newController()
        checkNotNull(revivedController.restoreActiveWalk())
        val tracker = RealTrackerSenses(h.clock, backgroundScope)
        val revived = h.newSession(revivedController, tracker, trackerFixes)
        tracker.uiGate.holdUntilRefreshed()
        val revivedAt = h.clock.now()
        assertEquals(SeekSessionStart.Started(revived = true), revived.start(processScope(), walk.id, revivedController.state))
        runCurrent()
        assertEquals("the pulse overdue at the revival waits on the unanswered gates", emptyList<Long>(), tracker.starts)

        advance(UiAudioGate.REFRESH_WAIT_MILLIS - 1)
        assertEquals(emptyList<Long>(), tracker.starts)
        advance(1)
        assertEquals("then it plays, once", listOf(revivedAt + UiAudioGate.REFRESH_WAIT_MILLIS), tracker.starts)
        revived.stop()
    }

    // The map at Begin

    @Test
    fun `the first row carries the ready screen's fog and walker, so Begin draws no thick fog and keeps the crescent`() =
        runTest(dispatcher) {
            val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
            val orchestrator = startUi(processScope())
            val controller = h.newController()
            val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
            readyScreen(chain)
            val readyFog = checkNotNull(orchestrator.fogState.value)
            assertNotNull("the ready screen draws its crescent", readyFog.crescent)

            val start = checkNotNull(orchestrator.begin())
            val walk = h.startSeekWalk(controller, start)
            runCurrent()
            session.start(processScope(), walk.id, controller.state)
            runCurrent()
            uiWalkState.value = activeSeekWalk(walk)
            runCurrent()

            assertEquals("before :tracker's first fix, the map is the ready screen's", readyFog, orchestrator.fogState.value)
            assertEquals(SeekChainGenerator.distance(home, chain.clearings[0].center), h.row(walk.id).distanceToActiveMeters!!, 1e-6)
            assertEquals(home.latitude, h.row(walk.id).walkerLatitude!!, 1e-9)
            session.stop()
        }

    @Test
    fun `the crescent rides the UI's own fixes between the rows the tracker writes`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val orchestrator = startUi(processScope())
        val controller = h.newController()
        val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
        val (walk, _) = beginWithHandOff(orchestrator, session, processScope(), chain, controller)
        val ahead = SeekChainGenerator.destination(from = home, bearingDegrees = 0.0, distanceMeters = 40.0)

        emit(uiFixes, ahead)

        val crescent = checkNotNull(orchestrator.fogState.value?.crescent)
        assertEquals(ahead.latitude, crescent.position.latitude, 1e-9)
        assertEquals(ahead.longitude, crescent.position.longitude, 1e-9)
        assertEquals("the row still has the walker where :tracker last wrote", home.latitude, h.row(walk.id).walkerLatitude!!, 1e-9)
        session.stop()
    }

    // Seek anew around Begin

    @Test
    fun `a reroll tapped between Begin and the walk reaching the UI goes to the tracker once its session starts`() =
        runTest(dispatcher) {
            val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
            val orchestrator = startUi(processScope())
            val controller = h.newController()
            val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
            readyScreen(chain)
            val start = checkNotNull(orchestrator.begin())
            val clearingsAtBegin = orchestrator.fogState.value?.circles

            orchestrator.seekAnewRequested()
            runCurrent()
            assertTrue("no session in :tracker yet to take it", link.seekAnews.isEmpty())
            assertEquals("the quiet ready screen's engine keeps Begin's chain", clearingsAtBegin, orchestrator.fogState.value?.circles)

            val walk = h.startSeekWalk(controller, start)
            runCurrent()
            uiWalkState.value = activeSeekWalk(walk)
            runCurrent()
            assertTrue("the walk's row is there, but its session hasn't started", link.seekAnews.isEmpty())

            session.start(processScope(), walk.id, controller.state)
            runCurrent()
            assertEquals("sent once the session started", 1, link.seekAnews.size)

            orchestrator.seekAnewRequested()
            runCurrent()
            assertEquals("and every reroll after goes straight there", 2, link.seekAnews.size)
            session.stop()
        }

    @Test
    fun `a reroll tapped while a start that then fails was in flight rerolls the ready screen`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val orchestrator = startUi(processScope())
        readyScreen(chain)
        assertNotNull(orchestrator.begin())
        val clearingsAtBegin = orchestrator.fogState.value?.circles

        orchestrator.seekAnewRequested()
        runCurrent()
        assertEquals("quiet while the start is in flight", 1, uiSound.pingTimes.size)

        orchestrator.cancel()
        runCurrent()
        assertEquals("the reroll's own ping, from the ready screen's engine", 2, uiSound.pingTimes.size)
        assertNotEquals("the ready screen's chain was rerolled", clearingsAtBegin, orchestrator.fogState.value?.circles)
        assertTrue(link.seekAnews.isEmpty())
    }

    @Test
    fun `Begin quiets the ready screen, and a failed start gives the sonar back`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val orchestrator = startUi(processScope())
        sessionStore.set(pending(chain))
        runCurrent()
        emit(uiFixes, home)
        advance(interval)
        assertEquals(1, uiSound.pingTimes.size)

        assertNotNull(orchestrator.begin())
        advance(interval)
        assertEquals("quiet while :tracker takes over", 1, uiSound.pingTimes.size)

        orchestrator.cancel()
        advance(interval)
        assertEquals("the start failed: the ready screen pings again", 2, uiSound.pingTimes.size)
    }

    @Test
    fun `a chain that locks after Start is handed over late, and the tracker attaches it`() = runTest(dispatcher) {
        val orchestrator = startUi(processScope())
        assertNull("nothing staged at Begin", orchestrator.begin())
        val controller = h.newController()
        val walk = controller.startWalk(WalkStartRequest(mode = WalkMode.Seek))
        runCurrent()
        uiWalkState.value = WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = walk.startTimestamp, mode = WalkMode.Seek))
        runCurrent()

        sessionStore.set(pending(chain(1)))
        runCurrent()

        val late = link.lateHandOffs.single()
        assertEquals(chain(1), late.chain)
        assertNull("the staging is consumed by the walk", sessionStore.pending.value)
        assertEquals("no engine boots in the UI for a walk :tracker guides", 0, uiSound.prepareCount)

        val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
        assertEquals(SeekSessionStart.Started(revived = false), session.attach(processScope(), late, controller.state))
        runCurrent()
        assertEquals(SeekChainCodec.encode(chain(1)), h.row(walk.id).chain)
        assertEquals("a replayed hand-off attaches nothing", SeekSessionStart.NotSeek, session.attach(processScope(), late, controller.state))
        session.stop()
    }

    // AE14

    @Test
    fun `AE14 a pocketed seek walk after a UI reclaim keeps its sonar and haptics, and a clearing records once`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2)
        val clearing = chain.clearings[0].center
        val interval = intervalAt(clearing)
        val uiScope = processScope()
        val trackerScope = processScope()
        val orchestrator = startUi(uiScope)
        val controller = h.newController()
        val trackerSound = SpySeekSound("tracker", h.clock, h.ops)
        val session = h.newSession(controller, trackerSound, trackerFixes)
        val (walk, _) = beginWithHandOff(orchestrator, session, trackerScope, chain, controller)

        uiScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        val pingsAtReclaim = trackerSound.pingTimes.size
        advance(2 * interval)
        emit(trackerFixes, home)
        advance(interval)
        assertTrue("the sonar keeps coming with no UI", trackerSound.pingTimes.size >= pingsAtReclaim + 2)

        emit(trackerFixes, clearing, times = SeekEngineTuning.ARRIVAL_FIX_COUNT)
        emit(trackerFixes, clearing, times = 2)
        emit(trackerFixes, clearing, times = 3)

        assertEquals(1, h.countSeekArrivalEvents())
        assertEquals(1, h.countArrivalWaypoints())
        assertEquals(
            "the arrival haptic fires once, after its rows commit",
            listOf("tracker haptic:arrival(events=1,waypoints=1)"),
            h.ops.filter { it.contains("haptic:arrival") },
        )
        assertTrue("stillness breathes in", h.ops.contains("tracker haptic:breathIn"))
        assertEquals(SeekEnginePhase.ARRIVED, h.row(walk.id).phase)
        val waypoint = h.repository.waypointsFor(walk.id).single()
        assertEquals("Clearing 1", waypoint.label)
        assertEquals(clearing.latitude, waypoint.latitude, 1e-9)
        assertTrue("the UI heard none of it", h.ops.none { it.startsWith("ui haptic") })

        // A tracker kill mid-arrival, revived: the clearing is never recorded again.
        trackerScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        val revivedController = h.newController()
        checkNotNull(revivedController.restoreActiveWalk())
        val revivedSound = SpySeekSound("revived", h.clock, h.ops)
        val revived = h.newSession(revivedController, revivedSound, trackerFixes)
        assertEquals(SeekSessionStart.Started(revived = true), revived.start(processScope(), walk.id, revivedController.state))
        runCurrent()
        emit(trackerFixes, clearing, times = 4)

        assertEquals(1, h.countSeekArrivalEvents())
        assertEquals(1, h.countArrivalWaypoints())
        assertTrue(h.ops.none { it.startsWith("revived haptic:arrival") })
        revived.stop()
    }

    // Revival

    @Test
    fun `a watchdog revival resumes the chain from Room without re-seeding`() = runTest(dispatcher) {
        val controller = h.newController()
        val sound = SpySeekSound("tracker", h.clock, h.ops)
        val session = h.newSession(controller, sound, trackerFixes)
        val trackerScope = processScope()
        val walk = h.startSeekWalk(controller, h.handOff(chain(clearingCount = 2)))
        runCurrent()
        session.start(trackerScope, walk.id, controller.state)
        runCurrent()

        val first = chain(2).clearings[0].center
        emit(trackerFixes, first, times = SeekEngineTuning.ARRIVAL_FIX_COUNT + 2)
        advance(SeekEngineTuning.GRACE_MILLIS + SeekEngineTuning.STILLNESS_CHECK_INTERVAL_MILLIS)
        assertEquals("the first clearing revealed the next", 1, sound.bowlCount)
        emit(trackerFixes, first)
        session.seekAnew(seq = 1)
        runCurrent()
        val before = h.row(walk.id)
        assertEquals(1, before.activeIndex)
        assertEquals(SeekEnginePhase.GUIDING, before.phase)
        assertNotEquals("the reroll moved the clearing ahead", SeekChainCodec.encode(chain(2)), before.chain)

        trackerScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        val revivedController = h.newController()
        val revivedSound = SpySeekSound("revived", h.clock, h.ops)
        val revived = h.newSession(revivedController, revivedSound, trackerFixes)
        val starter = WalkTrackingStarter(revivedController, h.repository, null, revived) { null }
        val watchdogStart = TrackerStartExtras(isFreshStart = false, request = WalkStartRequest(), honorGlanceUnits = null)
        assertTrue(starter.resolve(watchdogStart))
        starter.startSeek(processScope())
        runCurrent()

        val after = h.row(walk.id)
        assertEquals("Room's chain, not a new one", before.chain, after.chain)
        assertEquals(SeekTrackerHarness.SEED, after.seed)
        assertEquals(1, after.activeIndex)
        assertEquals("the revival bumps the generation the UI re-sends its gates for", before.gateGeneration + 1, after.gateGeneration)
        assertEquals("nothing replays at the revival", 0, revivedSound.bowlCount + revivedSound.pingTimes.size)

        val rerolled = SeekChainCodec.decode(after.chain)!!.clearings[1].center
        emit(trackerFixes, home)
        assertEquals(
            SeekGlanceModel.distanceBucket(SeekChainGenerator.distance(home, rerolled)),
            revived.glance.value!!.distanceBucketMeters,
        )
        emit(trackerFixes, rerolled, times = SeekEngineTuning.ARRIVAL_FIX_COUNT)
        assertEquals(2, h.countSeekArrivalEvents())
        assertEquals(
            listOf("Clearing 1", "Clearing 2"),
            h.repository.waypointsFor(walk.id).filter { SeekPersistence.isArrivalWaypoint(it.icon) }.map { it.label },
        )
        revived.stop()
    }

    @Test
    fun `a seek-anew or a setting applies once by its number`() = runTest(dispatcher) {
        val controller = h.newController()
        val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes)
        val walk = h.startSeekWalk(controller, h.handOff(chain(2)))
        runCurrent()
        session.start(processScope(), walk.id, controller.state)
        runCurrent()
        emit(trackerFixes, home)

        session.seekAnew(seq = 5)
        runCurrent()
        val rerolled = h.row(walk.id).chain
        session.seekAnew(seq = 5)
        session.seekAnew(seq = 4)
        session.applyPreferences(seq = 9, SeekSonarSettings(sonarEnabled = false, sonarVolume = 0.2f, soundsEnabled = true))
        session.applyPreferences(seq = 8, SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.9f, soundsEnabled = true))
        runCurrent()

        val row = h.row(walk.id)
        assertEquals(rerolled, row.chain)
        assertEquals(5L, row.lastCommandSeq)
        assertEquals(9L, row.lastPreferenceSeq)
        assertEquals(false, row.sonarEnabled)
        assertEquals(0.2f, row.sonarVolume, 0f)
        session.stop()
    }

    @Test
    fun `a revival after the chain is complete reads no fixes and arms no sonar`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 1)
        val controller = h.newController()
        val sound = SpySeekSound("tracker", h.clock, h.ops)
        val session = h.newSession(controller, sound, trackerFixes)
        val trackerScope = processScope()
        val walk = h.startSeekWalk(controller, h.handOff(chain))
        runCurrent()
        session.start(trackerScope, walk.id, controller.state)
        runCurrent()
        emit(trackerFixes, chain.clearings[0].center, times = SeekEngineTuning.ARRIVAL_FIX_COUNT)
        advance(SeekEngineTuning.GRACE_MILLIS + SeekEngineTuning.STILLNESS_CHECK_INTERVAL_MILLIS)
        assertEquals(1, sound.completionBowlCount)
        assertEquals(SeekEnginePhase.COMPLETE, h.row(walk.id).phase)

        trackerScope.coroutineContext[Job]!!.cancel()
        runCurrent()
        val revivedController = h.newController()
        checkNotNull(revivedController.restoreActiveWalk())
        val revivedSound = SpySeekSound("revived", h.clock, h.ops)
        val revived = h.newSession(revivedController, revivedSound, trackerFixes)
        assertEquals(SeekSessionStart.Started(revived = true), revived.start(processScope(), walk.id, revivedController.state))
        runCurrent()

        assertEquals("nothing left to seek, so no fix is read for the rest of the walk", 0, trackerFixes.subscriptionCount.value)
        assertEquals(0, revivedSound.prepareCount)
        assertEquals(true, revived.glance.value?.isComplete)
        revived.stop()
    }

    @Test
    fun `a write SQLite refuses is retried, and the sonar plays on meanwhile`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val controller = h.newController()
        val sound = SpySeekSound("tracker", h.clock, h.ops)
        val session = h.newSession(controller, sound, trackerFixes)
        val walk = h.startSeekWalk(controller, h.handOff(chain))
        runCurrent()
        session.start(processScope(), walk.id, controller.state)
        runCurrent()
        emit(trackerFixes, home)
        val tokenBefore = h.row(walk.id).pulseToken
        h.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_seek_writes BEFORE UPDATE ON seek_sessions BEGIN SELECT RAISE(ABORT, 'locked'); END",
        )

        advance(interval)
        assertEquals("the pulse rang though its row was refused", 1, sound.pingTimes.size)
        assertEquals(tokenBefore, h.row(walk.id).pulseToken)
        assertEquals("the session is still the walk's", walk.id, session.walkId)

        h.db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_seek_writes")
        val ahead = SeekChainGenerator.destination(from = home, bearingDegrees = 0.0, distanceMeters = 40.0)
        emit(trackerFixes, ahead)
        assertEquals(
            "the next input wrote the row again",
            SeekChainGenerator.distance(ahead, chain.clearings[0].center),
            h.row(walk.id).distanceToActiveMeters!!,
            1e-6,
        )
        advance(interval)
        assertEquals("the session guides on", 2, sound.pingTimes.size)
        assertEquals("and its pulses reach the row again", tokenBefore + 1, h.row(walk.id).pulseToken)
        session.stop()
    }

    @Test
    fun `the reveal whisper follows the Sounds switch the UI sends, not the tracker's own frozen read`() = runTest(dispatcher) {
        val tracker = RealTrackerSenses(h.clock, backgroundScope)
        val settings = tracker.settings
        val controller = h.newController()
        val sounds = SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.5f, soundsEnabled = false)
        val walk = h.startSeekWalk(controller, h.handOff(chain(2), sonar = sounds))
        runCurrent()
        assertNull("nothing sent yet: the whisper player reads its own preferences", settings.whisperSounds.soundsEnabled())
        val session = h.newSession(controller, tracker, trackerFixes)
        session.start(processScope(), walk.id, controller.state)
        runCurrent()
        assertEquals(false, settings.whisperSounds.soundsEnabled())

        session.applyPreferences(seq = 1, sounds.copy(soundsEnabled = true))
        runCurrent()
        assertEquals("Sounds turned on in the UI reaches the whisper player", true, settings.whisperSounds.soundsEnabled())

        session.stop()
        assertNull("the session over, the whisper player reads its own preferences again", settings.whisperSounds.soundsEnabled())
    }

    // Placement decisions, with both flag values

    @Test
    fun `the release flag alone moves Seek into the tracker`() {
        assertEquals(SeekPlacement.UI_PROCESS, SeekPlacement.of(releaseFlagOn = false))
        assertEquals(SeekPlacement.TRACKER, SeekPlacement.of(releaseFlagOn = true))
    }

    @Test
    fun `seek intents apply only with Seek in the tracker, and never redelivered`() {
        listOf(false, true).forEach { flag ->
            val placement = SeekPlacement.of(flag)
            assertEquals(
                SeekIntentAction.StopNoPipeline,
                WalkTrackingService.decideSeekIntentAction(placement, redelivered = false, pipelineActive = false),
            )
            assertEquals(
                if (flag) SeekIntentAction.Apply else SeekIntentAction.Ignore,
                WalkTrackingService.decideSeekIntentAction(placement, redelivered = false, pipelineActive = true),
            )
            assertEquals(
                SeekIntentAction.Ignore,
                WalkTrackingService.decideSeekIntentAction(placement, redelivered = true, pipelineActive = true),
            )
        }
    }

    @Test
    fun `a seek walk in progress gets its session, and anything else ends it`() {
        val seek = WalkAccumulator(walkId = 4L, startedAt = 0L, mode = WalkMode.Seek)
        listOf(
            WalkState.Active(seek),
            WalkState.Paused(seek, pausedAt = 1L),
            WalkState.Meditating(seek, meditationStartedAt = 1L),
        ).forEach { assertEquals(SeekSessionAction.Start(4L), WalkTrackingService.decideSeekSessionAction(it)) }
        listOf(
            WalkState.Idle,
            WalkState.Active(seek.copy(mode = WalkMode.Wander)),
            WalkState.Active(seek.copy(mode = WalkMode.Honor)),
            WalkState.Finished(seek, endedAt = 2L),
        ).forEach { assertEquals(SeekSessionAction.Stop, WalkTrackingService.decideSeekSessionAction(it)) }
    }

    @Test
    fun `with the flag off nothing hands over, and the tracker runs no seek session`() = runTest(dispatcher) {
        val orchestrator = startUi(processScope(), trackerLink = SeekTrackerLink.UiProcess)
        sessionStore.set(pending(chain(1)))
        runCurrent()
        assertNull(orchestrator.begin())

        val controller = h.newController(honorEnabled = false)
        val walk = h.startSeekWalk(controller, h.handOff(chain(1)))
        runCurrent()
        assertEquals(
            listOf(WalkEventType.SEEK_MODE),
            h.repository.eventsFor(walk.id).map { it.eventType },
        )
        assertNull("the hand-off is never written with the flag off", h.db.seekDao().getSession(walk.id))
        val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), trackerFixes, honorEnabled = false)
        assertEquals(SeekSessionStart.Disabled, session.start(processScope(), walk.id, controller.state))

        uiWalkState.value = WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = walk.startTimestamp, mode = WalkMode.Seek))
        runCurrent()
        assertNull("the UI adopts its own engine, as it always has", sessionStore.pending.value)
        assertEquals(0, uiSound.stopCount)
    }

    private companion object {
        const val BEGIN_AFTER_PING_MILLIS = 5_000L

        /** Begin this long before the next pulse, inside the gates' wait. */
        const val DUE_AFTER_BEGIN_MILLIS = 1_000L

        /** The UI's re-send landing this long after the pulse fell due. */
        const val UI_ANSWER_MILLIS = 400L
    }
}
