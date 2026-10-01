// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.ui.walk.map.WayGlyph
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPinTint
import org.walktalkmeditate.pilgrim.walk.BellTrigger
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * The walk screen's Honor state (parity spec E §1–§6): the Way before
 * Start, the rebuild from Room after a UI restart (AE1), the companion's
 * clock and cadence, pin taps, and the fly-to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorWalkViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock { T0 + dispatcher.scheduler.currentTime }
    private val controller = FakeWalkController()
    private val storeDirectory = File(context.filesDir, "honor-walk-vm-ways")
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var store: WayStore
    private val viewModels = mutableListOf<HonorWalkViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Room and every IO hop run on the test dispatcher, so nothing
        // outlives db.close() and Room's flows settle as they're written.
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
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
        store = WayStore({ storeDirectory }, clock)
    }

    @After
    fun tearDown() {
        runBlocking {
            withTimeout(10_000L) { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        }
        db.close()
        storeDirectory.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ---- Before Start ---------------------------------------------------

    @Test
    fun `before Start the pre-walk screen draws the Way of the walk it honors`() = runTest(dispatcher) {
        val sourceId = insertSourceWalk()
        val vm = viewModel()

        vm.showWay(sourceId)
        val state = vm.state.awaitValue { it != null }!!

        assertEquals("walk:$SOURCE_UUID" to listOf("waypoint-1"), state.line.wayId to state.pins.map { it.momentId })
    }

    @Test
    fun `before Start there is no session, so no companion stands on the Way`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showWay(insertSourceWalk())
        backgroundScope.launch { vm.companion.collect {} }

        assertEquals(null to null, vm.state.awaitValue { it != null }!!.session to vm.companion.value)
    }

    @Test
    fun `a pin tapped before Start opens nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showWay(insertSourceWalk())
        backgroundScope.launch { vm.state.collect {} }
        vm.state.awaitValue { it != null }

        vm.onWayPinTap("waypoint-1")

        assertNull(vm.tappedMomentId.value)
    }

    // ---- Rebuilt from Room after a UI restart (AE1) ----------------------

    @Test
    fun `after a restart a heard voice's pin is stone and an unheard one fog`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(heard("voice-1")))
        val vm = viewModel()

        val pins = vm.state.awaitValue { it?.session != null }!!.pins.associateBy { it.momentId }

        assertEquals(WayPinTint.STONE to WayPinTint.FOG, pins.getValue("voice-1").tint to pins.getValue("voice-2").tint)
    }

    @Test
    fun `after a restart the anchor and progress are the persisted ones`() = runTest(dispatcher) {
        startLiveWalk(
            session = {
                it.copy(startFrac = 0.1, anchorActiveSeconds = 30.0, companionT0Seconds = 60.0, progressFrac = 0.4)
            },
        )
        val vm = viewModel()

        val session = vm.state.awaitValue { it?.session != null }!!.session!!

        assertEquals(
            HonorAnchor(
                startFrac = 0.1,
                anchoredByFallback = false,
                anchorActiveSeconds = 30.0,
                companionT0Seconds = 60.0,
            ) to 0.4,
            session.anchor to session.progressFrac,
        )
    }

    @Test
    fun `after a restart an arrival that landed reads as arrived`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(phase = HonorPhase.ARRIVED) })
        val vm = viewModel()

        assertTrue(vm.state.awaitValue { it?.session != null }!!.session!!.arrived)
    }

    @Test
    fun `after a restart the reached moments and the playing voice are the persisted ones`() = runTest(dispatcher) {
        startLiveWalk(
            session = { it.copy(playingMomentId = "voice-2", voicePaused = true) },
            rows = listOf(reached("rest-1"), heard("voice-2")),
        )
        val vm = viewModel()

        val session = vm.state.awaitValue { it?.session != null }!!.session!!

        assertEquals(
            Triple(setOf("rest-1"), "voice-2", true),
            Triple(session.reachedMomentIds, session.playingMomentId, session.voicePaused),
        )
    }

    @Test
    fun `a shared Way with a missing or unknown icon draws mappin`() = runTest(dispatcher) {
        val shared = way(
            id = SHARE_WAY_ID,
            source = WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234"),
            moments = listOf(
                WayMoment(
                    id = "waypoint-1", frac = 0.3, at = null,
                    kind = WayMomentKind.Waypoint(label = "", icon = ""),
                ),
                WayMoment(
                    id = "waypoint-2", frac = 0.5, at = null,
                    kind = WayMomentKind.Waypoint(label = "the gate", icon = "sun.haze.fill"),
                ),
            ),
        )
        store.save(shared)
        startLiveWalk(way = null, session = { it.copy(wayId = SHARE_WAY_ID, sourceKind = HonorSourceKind.SHARE) })
        val vm = viewModel()

        val pins = vm.state.awaitValue { it?.session != null }!!.pins

        assertEquals(listOf(WayGlyph.Waypoint("mappin"), WayGlyph.Waypoint("mappin")), pins.map { it.glyph })
    }

    // ---- The companion ---------------------------------------------------

    @Test
    fun `the companion starts where the Way's walker was at this walk's engine time`() = runTest(dispatcher) {
        startLiveWalk()
        advanceTimeBy(90_000L)
        val vm = viewModel()
        backgroundScope.launch { vm.companion.collect {} }
        vm.state.awaitValue { it?.session != null }

        assertCoordinate(positionAt(seconds = 90.0), vm.companion.value)
    }

    @Test
    fun `the companion moves at most once every 2 seconds`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        val moves = recordCompanion(vm)

        repeat(10) { stepOneSecond() }

        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L), moves.map { (at, _) -> at - liveStartedAt })
    }

    @Test
    fun `the companion stands still for the whole of a pause`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        val moves = recordCompanion(vm)
        repeat(4) { stepOneSecond() }
        val before = moves.size

        controller.state.value = WalkState.Paused(accumulator(), pausedAt = clock.now())
        repeat(10) { stepOneSecond() }

        assertEquals(before, moves.size)
    }

    @Test
    fun `the companion isn't moved while meditating`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        val moves = recordCompanion(vm)
        val before = moves.size

        controller.state.value = WalkState.Meditating(accumulator(), meditationStartedAt = clock.now())
        repeat(10) { stepOneSecond() }

        assertEquals(before, moves.size)
    }

    @Test
    fun `back from a sitting the companion jumps to where the clock, sitting counted, puts it`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        recordCompanion(vm)
        controller.state.value = WalkState.Meditating(accumulator(), meditationStartedAt = clock.now())
        repeat(10) { stepOneSecond() }

        controller.state.value = WalkState.Active(accumulator().copy(totalMeditatedMillis = 10_000L))

        assertCoordinate(positionAt(seconds = 10.0), vm.companion.value)
    }

    @Test
    fun `until the first fix anchors the walker the companion waits at the Way's first point`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(startFrac = null) })
        advanceTimeBy(90_000L)
        val vm = viewModel()
        backgroundScope.launch { vm.companion.collect {} }
        vm.state.awaitValue { it?.session != null }

        assertCoordinate(WayCoordinate(lat = 0.0, lon = 0.0), vm.companion.value)
    }

    @Test
    fun `a pilgrimage stage has no companion`() = runTest(dispatcher) {
        startLiveWalk(way = way().copy(stage = stage()))
        val vm = viewModel()
        backgroundScope.launch { vm.companion.collect {} }
        vm.state.awaitValue { it?.session != null }
        repeat(3) { stepOneSecond() }

        assertNull(vm.companion.value)
    }

    // ---- Pin taps and the fly-to -----------------------------------------

    @Test
    fun `a pin tapped during the walk records its moment for the card host`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.state.awaitValue { it?.session != null }

        vm.onWayPinTap("voice-2")

        assertEquals("voice-2", vm.tappedMomentId.value)
    }

    @Test
    fun `a tap on a moment the Way doesn't carry records nothing`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.state.awaitValue { it?.session != null }

        vm.onWayPinTap("voice-9")

        assertNull(vm.tappedMomentId.value)
    }

    @Test
    fun `a header tap flies to the moment's own place, and the same tap again brings the map home`() =
        runTest(dispatcher) {
            startLiveWalk()
            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            val moment = vm.state.awaitValue { it?.session != null }!!.way.moments.first { it.id == "rest-1" }

            vm.flyTo(moment)
            val flown = vm.focus.value
            vm.flyTo(moment)

            assertEquals(REST_PLACE to null, flown to vm.focus.value)
        }

    @Test
    fun `a moment placed only on the line flies to the line's point at its frac`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        val moment = vm.state.awaitValue { it?.session != null }!!.way.moments.first { it.id == "voice-2" }

        vm.flyTo(moment)

        assertCoordinate(WayGeometry(route).coordinate(atFrac = 0.6), vm.focus.value)
    }

    @Test
    fun `with the release flag off the walk screen has no Way`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel(honorEnabled = false)
        vm.showWay(insertSourceWalk())
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.companion.collect {} }
        stepOneSecond()

        assertEquals(null to null, vm.state.value to vm.companion.value)
    }

    // ---- Harness ----------------------------------------------------------

    private fun viewModel(honorEnabled: Boolean = true) = HonorWalkViewModel(
        controller = controller,
        honorDao = db.honorDao(),
        repository = repository,
        wayStore = store,
        ownWalkWays = OwnWalkWays(repository, VoiceRecordingFileSystem(context), dispatcher, { UTC }, { Locale.US }),
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        clock = clock,
        ioDispatcher = dispatcher,
        tickMillis = 1_000L,
    ).also { viewModels += it }

    private var liveWalkId = 0L

    /**
     * A walk in progress honoring [way] (staged under the walk, as Start
     * stages it; null stages nothing), its session row as [session] shapes
     * it, and its moment rows; the controller reports the walk Active since
     * now, as a revived UI's would.
     */
    private suspend fun startLiveWalk(
        way: Way? = way(),
        session: (HonorSessionEntity) -> HonorSessionEntity = { it },
        rows: List<HonorMomentStateEntity> = emptyList(),
    ) {
        liveStartedAt = clock.now()
        liveWalkId = db.walkDao().insert(Walk(uuid = LIVE_UUID, startTimestamp = liveStartedAt))
        way?.let { store.stage(LIVE_UUID, it) }
        db.honorDao().insertSession(
            session(
                HonorSessionEntity(
                    walkId = liveWalkId,
                    wayId = OWN_WAY_ID,
                    sourceKind = HonorSourceKind.OWN_WALK,
                    voicesEnabled = true,
                    softTapEnabled = false,
                    startFrac = 0.0,
                ),
            ),
        )
        rows.forEach { db.honorDao().upsertMomentState(it.copy(walkId = liveWalkId)) }
        controller.state.value = WalkState.Active(accumulator())
    }

    private var liveStartedAt = 0L

    private fun accumulator() = WalkAccumulator(walkId = liveWalkId, startedAt = liveStartedAt, mode = WalkMode.Honor)

    private fun heard(momentId: String) = HonorMomentStateEntity(walkId = 0L, momentId = momentId, heard = true)

    private fun reached(momentId: String) = HonorMomentStateEntity(walkId = 0L, momentId = momentId, reachedAt = T0)

    private suspend fun insertSourceWalk(): Long {
        val departed = T0 - 3_600_000L
        val id = db.walkDao().insert(
            Walk(uuid = SOURCE_UUID, startTimestamp = departed, endTimestamp = departed + 600_000L),
        )
        route.forEachIndexed { i, point ->
            db.routeDataSampleDao().insert(
                RouteDataSample(
                    walkId = id, timestamp = departed + i * 60_000L, latitude = point.lat, longitude = point.lon,
                ),
            )
        }
        db.waypointDao().insert(
            Waypoint(
                walkId = id, timestamp = departed + 300_000L, latitude = 0.0, longitude = 0.005,
                label = "the bridge", icon = "leaf",
            ),
        )
        return id
    }

    /** Every place the companion stands at, with the virtual time it got there. */
    private suspend fun TestScope.recordCompanion(vm: HonorWalkViewModel): List<Pair<Long, WayCoordinate>> {
        val moves = mutableListOf<Pair<Long, WayCoordinate>>()
        backgroundScope.launch { vm.companion.collect { at -> if (at != null) moves += clock.now() to at } }
        vm.state.awaitValue { it?.session != null }
        return moves
    }

    private fun TestScope.stepOneSecond() {
        advanceTimeBy(1_000L)
        runCurrent()
    }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T =
        withTimeout(5_000L) { first(predicate) }

    private fun positionAt(seconds: Double): WayCoordinate {
        val geometry = WayGeometry(route)
        return geometry.coordinate(atFrac = geometry.frac(atElapsed = seconds))
    }

    private fun assertCoordinate(expected: WayCoordinate, actual: WayCoordinate?) {
        assertNotNull(actual)
        assertEquals(expected.lat, actual!!.lat, 1e-12)
        assertEquals(expected.lon, actual.lon, 1e-12)
    }

    private val route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.001, alt = null, t = i * 60.0) }

    private fun way(
        id: String = OWN_WAY_ID,
        source: WaySource = WaySource.OwnWalk(SOURCE_UUID),
        moments: List<WayMoment> = listOf(
            WayMoment(
                id = "voice-1", frac = 0.2, at = null,
                kind = WayMomentKind.Voice(0.25, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v1.wav")),
            ),
            WayMoment(id = "rest-1", frac = 0.4, at = REST_PLACE, kind = WayMomentKind.Rest(minutes = 4)),
            WayMoment(
                id = "voice-2", frac = 0.6, at = null,
                kind = WayMomentKind.Voice(0.65, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v2.wav")),
            ),
        ),
    ) = Way(
        id = id,
        source = source,
        title = "the long way",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = "UTC",
        expires = null,
        route = route,
        totalDistanceMeters = 1_111.95,
        theirActiveSeconds = 600.0,
        moments = moments,
        weather = null,
    )

    private fun stage() = org.walktalkmeditate.pilgrim.domain.honor.WayStage(
        routeId = "camino-frances", index = 0, count = 33, name = "Saint-Jean", theme = "", narrative = "",
        closing = "", warnings = emptyList(), distanceKm = 1.1, gainMeters = 0.0,
        hours = org.walktalkmeditate.pilgrim.domain.honor.WayStageHours(1.0, 2.0), difficulty = "easy",
        start = org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace("a", WayCoordinate(0.0, 0.0)),
        end = org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace("b", WayCoordinate(0.0, 0.01)),
    )

    private class FakeWalkController : WalkController {
        override val state = MutableStateFlow<WalkState>(WalkState.Idle)
        override val bellTriggers: SharedFlow<BellTrigger> = MutableSharedFlow()
        override val liveSteps: StateFlow<Int?> = MutableStateFlow(null)
        override suspend fun startWalk(intention: String?, mode: WalkMode): Walk = error("not used")
        override suspend fun pauseWalk() = Unit
        override suspend fun resumeWalk() = Unit
        override suspend fun startMeditation() = Unit
        override suspend fun endMeditation(endMillis: Long?) = Unit
        override suspend fun finishWalk() = Unit
        override suspend fun discardWalk() = Unit
        override suspend fun recordLocation(point: LocationPoint) = Unit
        override suspend fun setIntention(text: String) = Unit
        override suspend fun recordWaypoint(label: String?, icon: String?) = Unit
        override suspend fun recoverStaleWalks(): Long? = null
        override suspend fun restoreActiveWalk(): Walk? = null
    }

    private companion object {
        const val T0 = 1_700_100_000_000L
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val LIVE_UUID = "7f3c2a10-9d4e-4b8a-8c1f-5e6d7a8b9c0d"
        const val OWN_WAY_ID = "walk:$SOURCE_UUID"
        const val SHARE_WAY_ID = "share:AbCdEf1234"
        val UTC: ZoneId = ZoneId.of("UTC")
        val REST_PLACE = WayCoordinate(lat = 0.0001, lon = 0.004)
    }
}
