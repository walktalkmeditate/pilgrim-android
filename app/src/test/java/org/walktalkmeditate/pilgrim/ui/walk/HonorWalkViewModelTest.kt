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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.dismissedAt
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceState
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
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
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters
import org.walktalkmeditate.pilgrim.honor.HonorReplies
import org.walktalkmeditate.pilgrim.honor.HonorStageHandoff
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.ui.honor.CARD_RETIRE_MILLIS
import org.walktalkmeditate.pilgrim.ui.honor.COMMAND_CONFIRM_WINDOW_MILLIS
import org.walktalkmeditate.pilgrim.ui.honor.HONOR_ARRIVAL_CARD_ID
import org.walktalkmeditate.pilgrim.ui.honor.HonorArrivalSummary
import org.walktalkmeditate.pilgrim.ui.walk.map.WayGlyph
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPinTint
import org.walktalkmeditate.pilgrim.walk.BellTrigger
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.honor.HonorCommand
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness
import org.walktalkmeditate.pilgrim.walk.honor.HonorMediaFiles

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
        filesRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ---- Before Start ---------------------------------------------------

    @Test
    fun `before Start the pre-walk screen draws the Way of the walk it honors`() = runTest(dispatcher) {
        val sourceId = insertSourceWalk()
        val vm = viewModel()

        vm.showWay(HonorWayChoice.OwnWalk(sourceId))
        val state = vm.state.awaitValue { it != null }!!

        assertEquals("walk:$SOURCE_UUID" to listOf("waypoint-1"), state.line.wayId to state.pins.map { it.momentId })
    }

    @Test
    fun `before Start the pre-walk screen draws a listed shared Way, read from the store`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = viewModel()

        vm.showWay(HonorWayChoice.Stored(SHARE_WAY_ID))
        val state = vm.state.awaitValue { it != null }!!

        assertEquals(SHARE_WAY_ID to listOf("voice-1", "photo-1"), state.line.wayId to state.pins.map { it.momentId })
        assertNull(state.session)
    }

    // Owner decision 2: the pre-walk screen draws the copy Start will stage.
    @Test
    fun `before Start the pre-walk screen draws the stage its overview handed over, not the package redrawn since`() =
        runTest(dispatcher) {
            val atTheDoor = HonorHarness.stage()
            store.save(HonorHarness.stage(marks = emptyList(), title = "Larrasoaña to Pamplona, redrawn"))
            val vm = viewModel(stageHandoff = HonorStageHandoff().apply { hand(atTheDoor) })

            vm.showWay(HonorWayChoice.Stored(HonorHarness.STAGE_ID))

            assertEquals(atTheDoor, vm.state.awaitValue { it != null }!!.way)
        }

    @Test
    fun `a listed Way gone from the store draws nothing before Start`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        vm.showWay(HonorWayChoice.Stored(SHARE_WAY_ID))

        assertNull(vm.state.value)
    }

    @Test
    fun `before Start there is no session, so no companion stands on the Way`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showWay(HonorWayChoice.OwnWalk(insertSourceWalk()))
        backgroundScope.launch { vm.companion.collect {} }

        assertEquals(null to null, vm.state.awaitValue { it != null }!!.session to vm.companion.value)
    }

    @Test
    fun `a pin tapped before Start opens nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showWay(HonorWayChoice.OwnWalk(insertSourceWalk()))
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        vm.state.awaitValue { it != null }

        vm.onWayPinTap("waypoint-1")

        assertNull(vm.cards.value)
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

    @Test
    fun `a stage session draws the copy staged at Begin, not its package redrawn since`() = runTest(dispatcher) {
        val stageId = "pilgrimage:camino-frances:0"
        val staged = way(id = stageId, source = WaySource.Pilgrimage("camino-frances", 0)).copy(stage = stage())
        store.save(staged.copy(title = "redrawn by an Update", route = route.take(4)))
        startLiveWalk(way = staged, session = { it.copy(wayId = stageId, sourceKind = HonorSourceKind.PILGRIMAGE) })
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }

        val drawn = vm.state.awaitValue { it?.session != null }!!

        assertEquals("the long way" to route, drawn.way.title to drawn.way.route)
    }

    // ---- Pin taps and the fly-to -----------------------------------------

    @Test
    fun `a pin tapped during the walk raises its card above the waiting ones`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        vm.onWayPinTap("voice-2")

        val place = vm.cards.value!!.place!!
        assertEquals("voice-2" to 1, place.moment.id to place.pendingCount)
    }

    @Test
    fun `a tap on a moment the Way doesn't carry raises nothing`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        vm.state.awaitValue { it?.session != null }

        vm.onWayPinTap("voice-9")

        assertNull(vm.cards.awaitValue { it != null }!!.place)
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
        vm.showWay(HonorWayChoice.OwnWalk(insertSourceWalk()))
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.companion.collect {} }
        stepOneSecond()

        assertEquals(null to null, vm.state.value to vm.companion.value)
    }

    // ---- The card queue (parity spec E §7, D §5) --------------------------

    @Test
    fun `dismissing the top card brings the next one up`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0), reachedAt("rest-1", at = T0 + 1)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place?.moment?.id == "voice-1" }

        vm.dismissTopCard()

        assertEquals("rest-1", vm.cards.value?.place?.moment?.id)
    }

    @Test
    fun `a dismissal is written to the UI's own card row`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        vm.dismissTopCard()

        assertEquals(clock.now(), db.honorDao().getCardStates(liveWalkId).single { it.momentId == "rest-1" }.dismissedAt)
    }

    @Test
    fun `dismissing a card that flew the map brings the map home`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        val card = vm.cards.awaitValue { it?.place != null }!!.place!!
        vm.flyTo(card.moment)

        vm.dismissTopCard()

        assertNull(vm.focus.value)
    }

    @Test
    fun `an untouched voice card retires 20 s after its voice ends`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        advanceTimeBy(CARD_RETIRE_MILLIS)
        runCurrent()

        assertNull(vm.cards.value?.place)
    }

    @Test
    fun `a touched voice card waits for the walker`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        vm.touch("voice-1")
        advanceTimeBy(CARD_RETIRE_MILLIS * 2)
        runCurrent()

        assertEquals("voice-1", vm.cards.value?.place?.moment?.id)
    }

    // ---- The voice controls (correction 16, D §6) ------------------------

    @Test
    fun `a pause tap shows paused at once`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        vm.toggleListening()

        assertEquals(true, vm.sheet.value?.listening?.paused)
    }

    @Test
    fun `a pause tap stays paused when Room confirms it`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        vm.toggleListening()
        db.honorDao().updateVoiceState(
            HonorVoiceState(
                walkId = liveWalkId, playingMomentId = "voice-1", voicePaused = true, voiceStartedAt = null,
                voiceStartOffsetMillis = null, voicePauseOffsetMillis = 0L, voiceRate = 1.0,
            ),
        )
        advanceTimeBy(COMMAND_CONFIRM_WINDOW_MILLIS * 2)
        runCurrent()

        assertEquals(true, vm.sheet.value?.listening?.paused)
    }

    @Test
    fun `a command that never lands gives way to the persisted state after the window`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        vm.toggleListening()
        advanceTimeBy(COMMAND_CONFIRM_WINDOW_MILLIS)
        runCurrent()

        assertEquals(false, vm.sheet.value?.listening?.paused)
    }

    @Test
    fun `the chip's pause and skip go to the tracker as commands`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        vm.toggleListening()
        vm.skipVoice()

        assertEquals(listOf(HonorCommand.PauseResume("voice-1"), HonorCommand.Skip("voice-1")), sentCommands)
    }

    @Test
    fun `a skip hides the chip at once`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        vm.skipVoice()

        assertNull(vm.sheet.value?.listening)
    }

    @Test
    fun `the chip's clock ticks once a second from the voice's start`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        repeat(3) { stepOneSecond() }

        assertEquals(3.0, vm.sheet.value!!.listening!!.elapsedSeconds, 1e-9)
    }

    @Test
    fun `the chip's clock stands still while the tracker holds the voice, and runs on from where it stood`() =
        runTest(dispatcher) {
            presentVoices()
            startLiveWalk(
                session = { it.copy(playingMomentId = "voice-1", voiceStartedAt = null, voiceStartOffsetMillis = 7_000L) },
                rows = listOf(started("voice-1", at = T0)),
            )
            val vm = viewModel()
            collectCards(vm)
            vm.sheet.awaitValue { it?.listening != null }

            repeat(3) { stepOneSecond() }
            val held = vm.sheet.value!!.listening!!
            db.honorDao().updateVoiceState(
                HonorVoiceState(
                    walkId = liveWalkId, playingMomentId = "voice-1", voicePaused = false, voiceStartedAt = clock.now(),
                    voiceStartOffsetMillis = 7_000L, voicePauseOffsetMillis = null, voiceRate = 1.0,
                ),
            )
            repeat(2) { stepOneSecond() }

            assertEquals(HonorListening(elapsedSeconds = 7.0, paused = false), held)
            assertEquals(9.0, vm.sheet.value!!.listening!!.elapsedSeconds, 1e-9)
        }

    @Test
    fun `the clock ends at the voice's length, never past it`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it?.listening != null }

        repeat(25) { stepOneSecond() }

        assertEquals(20.0 to 20.0, vm.sheet.value!!.listening!!.elapsedSeconds to vm.cards.value!!.place!!.elapsedSeconds)
    }

    @Test
    fun `the chip's pause and skip send nothing once no voice is held`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        vm.toggleListening()
        vm.skipVoice()

        assertEquals(emptyList<HonorCommand>(), sentCommands)
    }

    @Test
    fun `a card whose voice isn't held starts it, and a card whose voice is pauses it`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        val playing = vm.cards.awaitValue { it?.place?.isPlaying == true }!!.place!!.moment
        vm.togglePlayback(playing)
        stepOneSecond()
        vm.onWayPinTap("voice-2")
        val other = vm.cards.awaitValue { it?.place?.moment?.id == "voice-2" }!!.place!!.moment

        vm.togglePlayback(other)

        assertEquals(listOf(HonorCommand.PauseResume("voice-1"), HonorCommand.TogglePlayback("voice-2")), sentCommands)
    }

    @Test
    fun `a voice whose file is gone plays nothing and sends nothing`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }
        vm.onWayPinTap("voice-2")
        val card = vm.cards.awaitValue { it?.place?.moment?.id == "voice-2" }!!.place!!

        vm.togglePlayback(card.moment)

        assertEquals(emptyList<HonorCommand>() to false, sentCommands to vm.cards.value!!.place!!.isPlaying)
    }

    @Test
    fun `a card's rate pill starts at 1x on every walk and steps the ladder`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel()
        collectCards(vm)
        val before = vm.cards.awaitValue { it?.place != null }!!.place!!.rate

        vm.cycleRate()

        assertEquals(1f to 1.25f, before to vm.cards.value!!.place!!.rate)
    }

    @Test
    fun `on a later honoring a voice with an earlier reply offers your reply`() = runTest(dispatcher) {
        presentVoices()
        presentFile("recordings/reply.wav")
        store.save(way())
        store.setReply(OWN_WAY_ID, originN = 1, relativePath = "recordings/reply.wav")
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)

        assertTrue(vm.cards.awaitValue { it?.place?.media != null }!!.place!!.media!!.hasEarlierReply)
    }

    @Test
    fun `a reply filed while its voice's card is up offers your reply at once`() = runTest(dispatcher) {
        presentVoices()
        store.save(way())
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)
        val before = vm.cards.awaitValue { it?.place?.media != null }!!.place!!.media!!.hasEarlierReply
        replies.arm(walkId = liveWalkId, wayId = OWN_WAY_ID, momentId = "voice-1")
        presentFile("recordings/reply.wav")

        replies.fileIfPending(
            VoiceRecording(
                walkId = liveWalkId, startTimestamp = T0, endTimestamp = T0 + 5_000L, durationMillis = 5_000L,
                fileRelativePath = "recordings/reply.wav",
            ),
        )

        assertEquals(false to true, before to vm.cards.awaitValue { it?.place?.media?.hasEarlierReply == true }!!.place!!.media!!.hasEarlierReply)
    }

    @Test
    fun `your reply is sent as the play-reply command`() = runTest(dispatcher) {
        presentVoices()
        presentFile("recordings/reply.wav")
        store.save(way())
        store.setReply(OWN_WAY_ID, originN = 1, relativePath = "recordings/reply.wav")
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)
        val card = vm.cards.awaitValue { it?.place?.media?.hasEarlierReply == true }!!.place!!

        vm.playReply(card.moment)

        assertEquals(listOf(HonorCommand.PlayReply("voice-1")), sentCommands)
    }

    // The arrival card's "your reply" on a stage (pilgrimage-stage spec P3 §13.4, P5 §8.3).

    @Test
    fun `your reply to a stage's closing line leaves as the play-reply command under the reflection's id`() =
        runTest(dispatcher) {
            val stageId = "pilgrimage:camino-frances:0"
            val staged = way(id = stageId, source = WaySource.Pilgrimage("camino-frances", 0), moments = emptyList())
                .copy(stage = stage())
            startLiveWalk(way = staged, session = { it.copy(wayId = stageId, sourceKind = HonorSourceKind.PILGRIMAGE) })
            val vm = viewModel(send = WalkActionPublisher(context)::sendHonorCommand)
            backgroundScope.launch { vm.state.collect {} }
            vm.state.awaitValue { it?.session != null }

            vm.playStageReflectionReply()

            assertEquals(listOf(HonorCommand.PlayReply(HonorPersistence.STAGE_REFLECTION_MOMENT_ID)), startedCommandIntents())
        }

    @Test
    fun `on a Way that isn't a stage no reflection reply is asked for`() = runTest(dispatcher) {
        startLiveWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        vm.state.awaitValue { it?.session != null }

        vm.playStageReflectionReply()

        assertEquals(emptyList<HonorCommand>(), sentCommands)
    }

    @Test
    fun `an earlier reply whose recording is gone offers none`() = runTest(dispatcher) {
        presentVoices()
        store.save(way())
        store.setReply(OWN_WAY_ID, originN = 1, relativePath = "recordings/deleted.wav")
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED)))
        val vm = viewModel()
        collectCards(vm)

        assertFalse(vm.cards.awaitValue { it?.place?.media != null }!!.place!!.media!!.hasEarlierReply)
    }

    @Test
    fun `a voice that failed to play shows the plain not-playing state`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FAILED)))
        val vm = viewModel()
        collectCards(vm)

        val card = vm.cards.awaitValue { it?.place != null }!!.place!!

        assertEquals(Triple("voice-1", false, 0.0), Triple(card.moment.id, card.isPlaying, card.elapsedSeconds))
    }

    @Test
    fun `a voice that failed to play retires 20 s later, untouched`() = runTest(dispatcher) {
        presentVoices()
        startLiveWalk(rows = listOf(started("voice-1", at = T0, end = HonorVoiceEnd.FAILED)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        advanceTimeBy(CARD_RETIRE_MILLIS)
        runCurrent()

        assertNull(vm.cards.value?.place)
    }

    // ---- The card's place (E §9) ------------------------------------------

    @Test
    fun `the card's distance runs from the walker's last fix`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        controller.state.value = WalkState.Active(accumulator().copy(lastLocation = fixAt(lat = 0.0, lon = 0.004)))
        val vm = viewModel()
        collectCards(vm)

        val distance = vm.cards.awaitValue { it?.place != null }!!.place!!.distanceMeters!!

        assertEquals(wgs84MidLatitudeMeters(0.0, 0.004, REST_PLACE.lat, REST_PLACE.lon), distance, 1e-9)
    }

    @Test
    fun `the tick points from the compass heading to the place`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        controller.state.value = WalkState.Active(accumulator().copy(lastLocation = fixAt(lat = 0.0, lon = 0.004)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.place != null }

        heading.value = 90.0

        val bearing = WayGeometry.bearing(WayCoordinate(0.0, 0.004), REST_PLACE)
        assertEquals((bearing - 90.0 + 360) % 360, vm.cards.value!!.place!!.tick!!, 1e-9)
    }

    @Test
    fun `with no settled compass there is no tick`() = runTest(dispatcher) {
        startLiveWalk(rows = listOf(reachedAt("rest-1", at = T0)))
        controller.state.value = WalkState.Active(accumulator().copy(lastLocation = fixAt(lat = 0.0, lon = 0.004)))
        val vm = viewModel()
        collectCards(vm)

        assertNull(vm.cards.awaitValue { it?.place != null }!!.place!!.tick)
    }

    // ---- The arrival card (E §11) -----------------------------------------

    @Test
    fun `after a restart a landed arrival card counts what was heard and passed before it`() = runTest(dispatcher) {
        startLiveWalk(
            session = { it.copy(phase = HonorPhase.ARRIVED) },
            rows = listOf(
                started("voice-1", at = T0, end = HonorVoiceEnd.FINISHED),
                reachedAt("rest-1", at = T0 + 1_000),
                started("voice-2", at = T0 + 9_000),
            ),
        )
        repository.recordEvent(
            org.walktalkmeditate.pilgrim.data.entity.WalkEvent(
                walkId = liveWalkId, timestamp = T0 + 5_000, eventType = WalkEventType.HONOR_ARRIVAL,
            ),
        )
        val vm = viewModel()
        collectCards(vm)

        val arrival = vm.cards.awaitValue { it?.arrival != null }!!.arrival

        assertEquals(HonorArrivalSummary(wayTitle = "the long way", voicesHeard = 1, placesPassed = 1), arrival)
    }

    @Test
    fun `the arrival card outranks every place card`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(phase = HonorPhase.ARRIVED) }, rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        collectCards(vm)

        assertNull(vm.cards.awaitValue { it?.arrival != null }!!.place)
    }

    @Test
    fun `continue lets the waiting place cards show`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(phase = HonorPhase.ARRIVED) }, rows = listOf(reachedAt("rest-1", at = T0)))
        val vm = viewModel()
        collectCards(vm)
        vm.cards.awaitValue { it?.arrival != null }

        vm.dismissArrival()

        assertEquals(null to "rest-1", vm.cards.value!!.arrival to vm.cards.value!!.place?.moment?.id)
    }

    @Test
    fun `after a restart an arrival card already continued past stays down`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(phase = HonorPhase.ARRIVED) })
        db.honorDao().markCardDismissed(liveWalkId, HONOR_ARRIVAL_CARD_ID, atMillis = T0)
        val vm = viewModel()
        collectCards(vm)

        assertNull(vm.cards.awaitValue { it != null }!!.arrival)
    }

    // ---- The stats sheet (E §10) ------------------------------------------

    @Test
    fun `Remaining is the Way's distance left`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(progressFrac = 0.4) })
        val vm = viewModel()
        collectCards(vm)

        val remaining = vm.sheet.awaitValue { it?.remainingMeters != null }!!.remainingMeters!!

        assertEquals(0.6 * WayGeometry(route).totalMeters, remaining, 1e-6)
    }

    @Test
    fun `before Start the honor walk's Remaining is unknown`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.showWay(HonorWayChoice.OwnWalk(insertSourceWalk()))
        collectCards(vm)

        val sheet = vm.sheet.awaitValue { it != null }!!

        assertNull(sheet.remainingMeters)
    }

    @Test
    fun `with the soft tap off nothing on screen comments on deviation`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(softTapArmed = true) })
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it != null }

        disarmSoftTap()

        assertNull(vm.sheet.value!!.softTapMeters)
    }

    @Test
    fun `a soft tap borrows the stat's slot with the metres off the Way, for 20 s`() = runTest(dispatcher) {
        startLiveWalk(session = { it.copy(softTapEnabled = true, softTapArmed = true) })
        controller.state.value = WalkState.Active(accumulator().copy(lastLocation = fixAt(lat = 0.003, lon = 0.002)))
        val vm = viewModel()
        collectCards(vm)
        vm.sheet.awaitValue { it != null }

        disarmSoftTap()
        val shown = vm.sheet.value!!.softTapMeters
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals((0.003 * 111_320).toLong() to null, shown to vm.sheet.value!!.softTapMeters)
    }

    // ---- Shared walks (shared spec S4 §10) ----------------------------------

    @Test
    fun `a shared voice whose media never arrived opens from its pin with the placeholder bar`() = runTest(dispatcher) {
        startSharedWalk()
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        collectCards(vm)
        vm.state.awaitValue { it?.session != null }

        vm.onWayPinTap("voice-1")

        assertNull(vm.cards.awaitValue { it?.place?.media != null }!!.place!!.media!!.waveform)
    }

    @Test
    fun `a shared photo not on this phone shows the plain plate`() = runTest(dispatcher) {
        startSharedWalk(rows = listOf(reachedAt("photo-1", at = T0)))
        val vm = viewModel()
        collectCards(vm)

        assertNull(vm.cards.awaitValue { it?.place?.media != null }!!.place!!.media!!.photoUri)
    }

    @Test
    fun `a photo that lands while its card shows appears only when the card next comes to the top`() =
        runTest(dispatcher) {
            startSharedWalk(rows = listOf(reachedAt("photo-1", at = T0)))
            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }
            collectCards(vm)
            vm.cards.awaitValue { it?.place?.media != null }
            val landed = store.mediaFile(SHARE_WAY_ID, "photos/0.jpg")!!.apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(16))
            }
            val whileShowing = vm.cards.value!!.place!!.media!!.photoUri

            vm.onWayPinTap("voice-1")
            vm.dismissTopCard()
            val photo = vm.cards.awaitValue { it?.place?.media?.photoUri != null }!!.place!!.media!!.photoUri

            assertEquals(null to android.net.Uri.fromFile(landed).toString(), whileShowing to photo)
        }

    @Test
    fun `a share walked without its voices arrives counting only what played`() = runTest(dispatcher) {
        startSharedWalk(
            session = { it.copy(phase = HonorPhase.ARRIVED) },
            rows = listOf(
                HonorMomentStateEntity(walkId = 0L, momentId = "voice-1", reachedAt = T0),
                reachedAt("photo-1", at = T0),
            ),
        )
        val vm = viewModel()
        collectCards(vm)

        val arrival = vm.cards.awaitValue { it?.arrival != null }!!.arrival!!

        assertEquals(0 to 1, arrival.voicesHeard to arrival.placesPassed)
    }

    // ---- The real command intents (house builder rule) -------------------

    @Test
    fun `the chip and card commands leave as the tracker's own intents`() = runTest(dispatcher) {
        context.getSharedPreferences("honor_commands", Context.MODE_PRIVATE).edit().clear().commit()
        presentVoices()
        presentFile("recordings/reply.wav")
        store.save(way())
        store.setReply(OWN_WAY_ID, originN = 1, relativePath = "recordings/reply.wav")
        startPlayingVoiceOne()
        val vm = viewModel(send = WalkActionPublisher(context)::sendHonorCommand)
        collectCards(vm)
        val card = vm.cards.awaitValue { it?.place?.media?.hasEarlierReply == true }!!.place!!

        vm.toggleListening()
        vm.togglePlayback(card.moment)
        vm.cycleRate()
        vm.scrub(card.moment, 0.5f)
        vm.playReply(card.moment)
        advanceTimeBy(1_000L)
        vm.togglePlayback(card.moment)
        vm.skipVoice()

        assertEquals(
            listOf(
                HonorCommand.PauseResume("voice-1"),
                HonorCommand.PauseResume("voice-1"),
                HonorCommand.CycleRate,
                HonorCommand.Scrub("voice-1", 0.5),
                HonorCommand.PlayReply("voice-1"),
                HonorCommand.TogglePlayback("voice-1"),
                HonorCommand.Skip("voice-1"),
            ),
            startedCommandIntents(),
        )
    }

    @Test
    fun `with the release flag off no command leaves`() = runTest(dispatcher) {
        startPlayingVoiceOne()
        val vm = viewModel(honorEnabled = false)
        collectCards(vm)

        vm.toggleListening()
        vm.cycleRate()

        assertEquals(emptyList<HonorCommand>(), sentCommands)
    }

    // ---- Harness ----------------------------------------------------------

    private suspend fun startPlayingVoiceOne() {
        presentVoices()
        startLiveWalk(
            session = {
                it.copy(playingMomentId = "voice-1", voiceStartedAt = T0, voiceStartOffsetMillis = 0L)
            },
            rows = listOf(started("voice-1", at = T0)),
        )
    }

    private suspend fun startSharedWalk(
        session: (HonorSessionEntity) -> HonorSessionEntity = { it },
        rows: List<HonorMomentStateEntity> = emptyList(),
    ) {
        store.save(sharedWay())
        startLiveWalk(
            way = null,
            session = { session(it.copy(wayId = SHARE_WAY_ID, sourceKind = HonorSourceKind.SHARE)) },
            rows = rows,
        )
    }

    private fun sharedWay() = way(
        id = SHARE_WAY_ID,
        source = WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234"),
        moments = listOf(
            WayMoment(
                id = "voice-1", frac = 0.2, at = null,
                kind = WayMomentKind.Voice(0.25, 0.0, VoiceKind.SPOKEN, WayMedia.File("audio/0.m4a")),
                place = "Rúa do Franco",
            ),
            WayMoment(id = "photo-1", frac = 0.5, at = null, kind = WayMomentKind.Photo(WayMedia.File("photos/0.jpg"))),
        ),
    )

    /** `:tracker` disarming the soft tap as it fires, through its own targeted update. */
    private suspend fun disarmSoftTap() {
        val session = db.honorDao().getSession(liveWalkId)!!
        db.honorDao().updateEngineState(
            org.walktalkmeditate.pilgrim.data.honor.HonorEngineState(
                walkId = liveWalkId, startFrac = session.startFrac, anchoredByFallback = session.anchoredByFallback,
                anchorActiveSeconds = session.anchorActiveSeconds, companionT0Seconds = session.companionT0Seconds,
                progressFrac = session.progressFrac, progressHighWater = session.progressHighWater,
                walkedFrac = session.walkedFrac, offWaySince = session.offWaySince,
                offWayActiveSeconds = session.offWayActiveSeconds, lastReacquireAttempt = session.lastReacquireAttempt,
                softTapSince = null, softTapArmed = false, arrivalInsideFixes = session.arrivalInsideFixes,
                lastNoticeSeconds = session.lastNoticeSeconds,
            ),
        )
    }

    private fun fixAt(lat: Double, lon: Double) = LocationPoint(timestamp = T0, latitude = lat, longitude = lon)

    /** Every Honor command intent the production publisher started, read back through the service's own decoder. */
    private fun startedCommandIntents(): List<HonorCommand?> = generateSequence {
        shadowOf(context as Application).nextStartedService
    }.filter { it.action == WalkTrackingService.ACTION_HONOR_COMMAND }.map { intent ->
        WalkTrackingService.honorCommandFromExtras(
            kind = intent.getStringExtra(WalkTrackingService.EXTRA_HONOR_COMMAND),
            momentId = intent.getStringExtra(WalkTrackingService.EXTRA_HONOR_MOMENT_ID),
            fraction = intent.getDoubleExtra(WalkTrackingService.EXTRA_HONOR_SCRUB_FRACTION, 0.0),
        )
    }.toList()

    private fun viewModel(
        honorEnabled: Boolean = true,
        send: (HonorCommand) -> Unit = { sentCommands += it },
        stageHandoff: HonorStageHandoff = HonorStageHandoff(),
    ) = HonorWalkViewModel(
        controller = controller,
        honorDao = db.honorDao(),
        repository = repository,
        wayStore = store,
        ownWalkWays = OwnWalkWays(repository, VoiceRecordingFileSystem(context), dispatcher, { UTC }, { Locale.US }),
        mediaFiles = HonorMediaFiles({ filesRoot }, store),
        replies = replies,
        headings = { heading },
        sendCommand = send,
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        clock = clock,
        ioDispatcher = dispatcher,
        tickMillis = 1_000L,
        loadWaveform = { FloatArray(150) { 0.5f } },
        stageHandoff = stageHandoff,
    ).also { viewModels += it }

    private val sentCommands = mutableListOf<HonorCommand>()
    private val heading = MutableStateFlow<Double?>(null)
    private val filesRoot = File(context.filesDir, "honor-walk-vm-files")
    private val replies by lazy { HonorReplies(store) }

    /** The own walk's recordings, as the Way's `recordings/…` paths name them. */
    private fun presentFile(relativePath: String): File =
        File(filesRoot, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }

    private fun presentVoices() {
        presentFile("recordings/v1.wav")
        presentFile("recordings/v2.wav")
    }

    private fun started(momentId: String, at: Long, end: HonorVoiceEnd? = null, endedAt: Long? = null) =
        HonorMomentStateEntity(
            walkId = 0L,
            momentId = momentId,
            voiceStartedAt = at,
            voiceEndedAt = endedAt ?: at.takeIf { end != null },
            voiceEnd = end,
            heard = true,
        )

    private fun reachedAt(momentId: String, at: Long) =
        HonorMomentStateEntity(walkId = 0L, momentId = momentId, reachedAt = at)

    private fun TestScope.collectCards(vm: HonorWalkViewModel) {
        backgroundScope.launch { vm.cards.collect {} }
        backgroundScope.launch { vm.sheet.collect {} }
        backgroundScope.launch { vm.replyingToMomentId.collect {} }
    }

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
