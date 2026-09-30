// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.HonorStart
import org.walktalkmeditate.pilgrim.walk.UiWalkController
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.WalkTrackingWatchdog
import org.walktalkmeditate.pilgrim.walk.honor.FakePorts
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix
import org.walktalkmeditate.pilgrim.walk.honor.HonorSession

/**
 * The `:tracker` location job's opening, as [WalkTrackingService] runs it:
 * [WalkTrackingStarter] resolving each kind of start against a real
 * controller and Room, then the Honor session tapped onto
 * [collectWalkFixes]. Each "process" is its own controller and session;
 * a kill is its scope cancelled with nothing torn down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTrackingHonorPipelineTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val way = HonorHarness.way()
    private val dao get() = h.db.honorDao()
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val uiScopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
        h.writeRecordings(way)
    }

    @After
    fun tearDown() {
        runBlocking { uiScopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
        h.close()
    }

    /** What an honor walk's ACTION_START carries, as the tracker reads it; the OS redelivers it verbatim. */
    private fun honorStart(walkUuid: String = uuid) = TrackerStartExtras(
        isFreshStart = true,
        request = WalkStartRequest(
            mode = WalkMode.Honor,
            walkUuid = walkUuid,
            honor = HonorStart(way.id, HonorSettings(voicesEnabled = true, softTapEnabled = false)),
        ),
        honorGlanceUnits = UnitSystem.Metric,
    )

    /** The watchdog's revival: a bare ACTION_START. */
    private val watchdogStart = TrackerStartExtras(isFreshStart = false, request = WalkStartRequest(), honorGlanceUnits = null)

    private fun trailhead() = fix(0.0, h.clock.millis)

    private fun starter(
        session: HonorSession?,
        controller: WalkControllerImpl = h.controller,
        lastKnown: LocationPoint? = null,
    ) = WalkTrackingStarter(controller, h.repository, session) { lastKnown }

    /** Begin staged the Way under its uuid; the tracker resolves the start, then starts the session. */
    private suspend fun beginInTracker(
        session: HonorSession,
        scope: CoroutineScope = h.serviceScope,
        walkUuid: String = uuid,
    ): Walk {
        h.store.stage(walkUuid, way)
        val starter = starter(session, lastKnown = trailhead())
        assertTrue(starter.resolve(honorStart(walkUuid)))
        assertSame(session, starter.startHonor(scope))
        session.awaitIdle()
        return h.repository.walkByUuid(walkUuid)!!
    }

    /** Fixes through the service's collector: the engine first, then the reducer. */
    private suspend fun walkTo(session: HonorSession?, controller: WalkControllerImpl, vararg lons: Double) {
        val fixes = flow {
            for (lon in lons) {
                h.clock.millis += 1_000
                emit(fix(lon, h.clock.millis))
            }
        }
        collectWalkFixes(fixes, session) { controller.recordLocation(it) }
        session?.awaitIdle()
    }

    private fun uiController(): UiWalkController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { uiScopes += it }
        return UiWalkController(
            repository = h.repository,
            actionPublisher = WalkActionPublisher(app),
            watchdog = WalkTrackingWatchdog(app),
            scope = scope,
            releaseFlags = FixedReleaseFlags(honor = true),
            unitsPreferences = FakeUnitsPreferencesRepository(UnitSystem.Imperial),
        )
    }

    // Starting

    @Test
    fun `a fresh honor start inserts the walk under Begin's uuid and feeds the last known fix at Begin`() = runBlocking {
        val ports = FakePorts()
        val session = h.newSession(ports)

        val walk = beginInTracker(session)

        assertEquals(walk.id, session.walkId)
        assertEquals(1, h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.HONOR_MODE })
        assertEquals(1L, dao.getSession(walk.id)!!.gateGeneration)
        assertEquals("the trailhead voice starts from the Begin fix", listOf("duck", "play voice-1 1.0", "pause", "resume"), ports.voiceCalls())
    }

    @Test
    fun `the UI's start carries Begin's uuid to the tracker, and its await resolves to that walk`() = runBlocking {
        val ui = uiController()
        h.store.stage(uuid, way)

        val started = async(start = CoroutineStart.UNDISPATCHED) { ui.startWalk(honorStart().request) }
        val extras = WalkTrackingService.startExtrasFrom(shadowOf(app).nextStartedService, honorEnabled = true)
        assertEquals("the glance's units are read at Start", UnitSystem.Imperial, extras.honorGlanceUnits)
        assertTrue(starter(h.newSession()).resolve(extras))

        assertEquals(uuid, started.await().uuid)
    }

    @Test
    fun `an honor start whose Way no longer loads is refused loudly, with no walk and no session`() = runBlocking {
        // Nothing staged under the uuid, and no Way listed.
        val session = h.newSession()

        assertFalse(starter(session).resolve(honorStart()))

        assertEquals(emptyList<Walk>(), h.repository.allWalks())
        assertNull(session.walkId)
    }

    // Revival

    @Test
    fun `a watchdog revival with no extras keeps the anchor, replays no voice, and plays the next at its spot`() = runBlocking {
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        val walk = beginInTracker(first, scope = firstProcess)
        walkTo(first, h.controller, 0.001, 0.002)
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()

        val revivedController = h.newController()
        val ports = FakePorts()
        val second = h.newSession(ports, arrivalRecorder = revivedController)
        val starter = starter(second, controller = revivedController)
        assertTrue(starter.resolve(watchdogStart))
        starter.startHonor(h.serviceScope)
        second.awaitIdle()

        assertEquals("only the walk's rate is re-applied", listOf("rate 1.0"), ports.voiceCalls())
        assertEquals("same anchor", 0.0, dao.getSession(walk.id)!!.startFrac!!, 0.0)
        assertEquals(2L, dao.getSession(walk.id)!!.gateGeneration)

        walkTo(second, revivedController, 0.003, 0.004, 0.005)
        assertEquals(listOf("rate 1.0", "duck", "play voice-2 1.0"), ports.voiceCalls())
        assertEquals(1, h.repository.allWalks().size)
        assertEquals(5, h.repository.locationSamplesFor(walk.id).size)
    }

    @Test
    fun `the OS redelivering START after a kill adopts the unfinished walk, and the session resumes without a replay`() = runBlocking {
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        val walk = beginInTracker(first, scope = firstProcess)
        walkTo(first, h.controller, 0.001, 0.002)
        firstProcess.coroutineContext[Job]!!.cancelAndJoin()

        val revivedController = h.newController()
        val ports = FakePorts()
        val second = h.newSession(ports, arrivalRecorder = revivedController)
        val starter = starter(second, controller = revivedController)
        assertTrue("the stale fresh-start extras yield to Room", starter.resolve(honorStart()))
        starter.startHonor(h.serviceScope)
        second.awaitIdle()

        assertEquals(listOf(walk.id), h.repository.allWalks().map { it.id })
        assertEquals(walk.id, second.walkId)
        assertEquals(listOf("rate 1.0"), ports.voiceCalls())
        walkTo(second, revivedController, 0.003, 0.004, 0.005)
        assertEquals(listOf("rate 1.0", "duck", "play voice-2 1.0"), ports.voiceCalls())
    }

    @Test
    fun `a cached process still holding a finished walk adopts the redelivered walk rather than inserting one`() = runBlocking {
        h.controller.startWalk()
        h.controller.finishWalk()
        // Another process inserted the honor walk before it was killed.
        h.store.stage(uuid, way)
        val honorWalk = h.newController().startWalk(honorStart().request)
        assertTrue(h.controller.state.value is WalkState.Finished)

        val session = h.newSession()
        val starter = starter(session)
        assertTrue(starter.resolve(honorStart()))
        starter.startHonor(h.serviceScope)
        session.awaitIdle()

        assertEquals(honorWalk.id, (h.controller.state.value as WalkState.Active).walk.walkId)
        assertEquals(2, h.repository.allWalks().size)
        assertEquals(honorWalk.id, session.walkId)
    }

    // Starts Room says are over

    @Test
    fun `a redelivered START for a finished walk stops, with no second walk and no session`() = runBlocking {
        val walk = beginInTracker(h.newSession())
        h.controller.finishWalk()

        val controller = h.newController()
        val session = h.newSession(arrivalRecorder = controller)
        assertFalse(starter(session, controller = controller).resolve(honorStart()))

        assertEquals(listOf(walk.id), h.repository.allWalks().map { it.id })
        assertNull(session.walkId)
        assertNull(dao.getSession(walk.id))
        assertTrue(controller.state.value is WalkState.Idle)
    }

    @Test
    fun `a redelivered START whose walk survives only in its marker stops`() = runBlocking {
        val walk = beginInTracker(h.newSession())
        h.controller.finishWalk()
        h.db.walkDao().deleteById(walk.id)

        val controller = h.newController()
        assertFalse(starter(h.newSession(arrivalRecorder = controller), controller = controller).resolve(honorStart()))

        assertEquals("no phantom walk under the old uuid", emptyList<Walk>(), h.repository.allWalks())
    }

    @Test
    fun `UI recovery finishing the walk while the redelivered start is pending leaves no phantom walk or session`() = runBlocking {
        val trackerProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = h.newSession(FakePorts())
        val walk = beginInTracker(first, scope = trackerProcess)
        walkTo(first, h.controller, 0.001)
        trackerProcess.coroutineContext[Job]!!.cancelAndJoin()

        // The UI's cold launch, with no live service: recovery, then the launch's Honor maintenance.
        assertEquals(walk.id, uiController().recoverStaleWalks())
        h.finalizer.runAtLaunch()

        val controller = h.newController()
        val session = h.newSession(arrivalRecorder = controller)
        assertFalse(starter(session, controller = controller).resolve(honorStart()))

        val walks = h.repository.allWalks()
        assertEquals(listOf(walk.id), walks.map { it.id })
        assertNotEquals(null, walks.single().endTimestamp)
        assertNull(session.walkId)
        assertNull("the Honor step ran", dao.getSession(walk.id))
        assertEquals(HonorFinishKind.RECOVERED, dao.getMarker(uuid)!!.finishKind)
    }

    // During the walk

    @Test
    fun `Finish from the notification mid-voice stops the voice, and no Honor row lands after the finalize`() = runBlocking {
        val ports = FakePorts()
        val session = h.newSession(ports)
        val walk = beginInTracker(session)
        ports.clear()

        // ACTION_FINISH's handler.
        h.controller.finishWalk()
        session.awaitIdle()

        assertEquals(listOf("stop", "restore"), ports.voiceCalls())
        assertNull(dao.getSession(walk.id))
        assertEquals(HonorFinishKind.CLEAN, dao.getMarker(uuid)!!.finishKind)

        walkTo(session, h.controller, 0.003)
        session.stop()
        assertTrue(dao.getMomentStates(walk.id).isEmpty())
        assertEquals("the service's teardown adds nothing", listOf("stop", "restore"), ports.voiceCalls())
    }

    @Test
    fun `with the UI reclaimed the session keeps writing its rows, and voices and arrival go on (AE1)`() = runBlocking {
        val ports = FakePorts()
        val session = h.newSession(ports)
        val walk = beginInTracker(session)

        walkTo(session, h.controller, *(1..10).map { it * 0.0005 }.toDoubleArray())
        ports.finishLatest()
        session.awaitIdle()
        walkTo(session, h.controller, *(11..19).map { it * 0.0005 }.toDoubleArray())
        walkTo(session, h.controller, 0.0098, 0.0099, 0.01, 0.01, 0.01)

        val rows = dao.getMomentStates(walk.id).associateBy { it.momentId }
        assertTrue(rows.getValue("waypoint-1").reachedAt != null)
        assertTrue(rows.getValue("voice-2").heard)
        assertEquals(1, h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.HONOR_ARRIVAL })
        assertEquals(1, ports.calls.count { it == "haptic arrival" })
        assertTrue(session.glance.value!!.isArrived)
    }

    @Test
    fun `the route pipeline records the same samples with the engine tap in place`() = runBlocking {
        val lons = doubleArrayOf(0.0, 0.0004, 0.0011, 0.0019, 0.0026, 0.0031)

        val session = h.newSession()
        val tapped = beginInTracker(session)
        val tappedStart = h.clock.millis
        walkTo(session, h.controller, *lons)
        h.controller.finishWalk()
        val tappedDistance = (h.controller.state.value as WalkState.Finished).walk.distanceMeters

        val secondUuid = "22222222-3333-4444-8555-666666666666"
        h.store.stage(secondUuid, way)
        assertTrue(starter(session = null).resolve(honorStart(secondUuid)))
        val untapped = h.repository.walkByUuid(secondUuid)!!
        h.clock.millis = tappedStart
        walkTo(null, h.controller, *lons)
        h.controller.finishWalk()
        val untappedDistance = (h.controller.state.value as WalkState.Finished).walk.distanceMeters

        fun samplesOf(walk: Walk) = runBlocking {
            h.repository.locationSamplesFor(walk.id).map {
                listOf(it.timestamp, it.latitude, it.longitude, it.horizontalAccuracyMeters, it.speedMetersPerSecond)
            }
        }
        assertEquals(lons.size, samplesOf(tapped).size)
        assertEquals(samplesOf(tapped), samplesOf(untapped))
        assertEquals(tappedDistance, untappedDistance, 0.0)
    }

    // The release flag

    @Test
    fun `with the release flag off no Honor path runs, from the intent to the session`() = runBlocking {
        h.store.stage(uuid, way)
        WalkActionPublisher(app).start(honorStart().request, UnitSystem.Metric)
        val extras = WalkTrackingService.startExtrasFrom(shadowOf(app).nextStartedService, honorEnabled = false)
        val controller = h.newController(honorEnabled = false)
        val starter = WalkTrackingStarter(controller, h.repository, honorSession = null) { error("no Begin fix without Honor") }

        assertTrue(starter.resolve(extras))
        assertNull(starter.startHonor(h.serviceScope))

        val walk = h.repository.allWalks().single()
        assertNotEquals(uuid, walk.uuid)
        assertNull(dao.getSession(walk.id))
        assertEquals(WalkMode.Wander, (controller.state.value as WalkState.Active).walk.mode)
        assertTrue(h.repository.eventsFor(walk.id).none { it.eventType == WalkEventType.HONOR_MODE })
    }

    @Test
    fun `a wander walk in a cached process runs no session, and the honor walk's glance is gone`() = runBlocking {
        val session = h.newSession()
        beginInTracker(session)
        h.controller.finishWalk()
        session.awaitIdle()

        val starter = starter(session)
        assertTrue(starter.resolve(TrackerStartExtras(isFreshStart = true, request = WalkStartRequest(), honorGlanceUnits = null)))
        starter.startHonor(h.serviceScope)

        assertTrue(h.controller.state.value is WalkState.Active)
        assertNull(session.walkId)
        assertNull(session.glance.value)
    }
}
