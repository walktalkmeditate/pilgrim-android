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
    }

    @After
    fun tearDown() {
        h.db.close()
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

    /**
     * The ready screen's engine pings once, the walker taps Start a little
     * later, `:tracker` starts the walk from the hand-off and its session,
     * and the UI sees the walk. Returns the walk and the time of the ready
     * screen's ping.
     */
    private suspend fun TestScope.beginWithHandOff(
        orchestrator: SeekOrchestrator,
        session: SeekTrackerSession,
        trackerScope: CoroutineScope,
        chain: org.walktalkmeditate.pilgrim.domain.seek.SeekChain,
        controller: org.walktalkmeditate.pilgrim.walk.WalkControllerImpl,
    ): Pair<Walk, Long> {
        sessionStore.set(pending(chain))
        runCurrent()
        emit(uiFixes, home)
        advance(intervalAt(chain.clearings[0].center))
        val readyPing = uiSound.pingTimes.single()
        advance(BEGIN_AFTER_PING_MILLIS)

        val start = checkNotNull(orchestrator.begin())
        val walk = h.startSeekWalk(controller, start)
        runCurrent()
        assertEquals(SeekSessionStart.Started(revived = false), session.start(trackerScope, walk.id, controller.state))
        runCurrent()
        emit(trackerFixes, home)
        uiWalkState.value = WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = walk.startTimestamp, mode = WalkMode.Seek))
        runCurrent()
        return walk to readyPing
    }

    // The Begin hand-off

    @Test
    fun `the Begin hand-off plays the sonar once, at the ready screen's cadence, from one engine`() = runTest(dispatcher) {
        val chain = chain(clearingCount = 2, spacingMeters = 1_000.0)
        val interval = intervalAt(chain.clearings[0].center)
        val orchestrator = startUi(processScope())
        val controller = h.newController()
        val trackerSound = SpySeekSound("tracker", h.clock, h.ops)
        val session = h.newSession(controller, trackerSound, trackerFixes)

        val (walk, readyPing) = beginWithHandOff(orchestrator, session, processScope(), chain, controller)
        assertEquals("the hand-off carried the ready screen's next pulse", readyPing + interval, h.row(walk.id).nextPulseDueAt)
        assertEquals("the UI's engine stopped when the walk took its session", 1, uiSound.stopCount)

        advance(readyPing + interval - h.clock.now() - 1)
        assertEquals("no double: nothing sounds before the ping is due", 0, trackerSound.pingTimes.size)
        advance(1)

        assertEquals("the ready screen's one ping, and nothing more from the UI", listOf(readyPing), uiSound.pingTimes)
        assertEquals(
            "no gap: :tracker's first ping lands where the ready screen's next was due",
            listOf(readyPing + interval),
            trackerSound.pingTimes,
        )
        advance(interval)
        assertEquals("the cadence carries on in :tracker", listOf(readyPing + interval, readyPing + 2 * interval), trackerSound.pingTimes)
        assertTrue("the map flares from the row :tracker writes", orchestrator.pulse.value.token > 0)
        assertNotNull(orchestrator.fogState.value)
        assertEquals(SeekEnginePhase.GUIDING, orchestrator.enginePhase.value)
        assertTrue("Begin handed the session over, so nothing was handed late", link.lateHandOffs.isEmpty())
        session.stop()
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
    }
}
