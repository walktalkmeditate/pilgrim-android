// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import androidx.room.Room
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.FakeHonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.FakeWayMediaDownloadScheduler
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.InternetConnectionProbe
import org.walktalkmeditate.pilgrim.data.honor.WayMediaReport
import org.walktalkmeditate.pilgrim.data.honor.WayMediaWork
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeWalkSignals
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.InMemoryTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCorridor
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.recordingHandler
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.data.weather.FakeWeatherFetching
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorStageHandoff
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.honor.WayMediaDownloader
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageFormat
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageMorningCardModel
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformCache
import org.walktalkmeditate.pilgrim.ui.walk.map.CameraFitDecision
import org.walktalkmeditate.pilgrim.ui.walk.map.CameraFitPaddingDp
import org.walktalkmeditate.pilgrim.ui.walk.map.decideCameraFit
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/**
 * The overview's Way, lines, framing inputs, toggle, and preview player
 * (parity spec F §8–§14), and a pilgrimage stage's branches: its stage
 * line, today's whole weather, and the once-ever offline note
 * (pilgrimage-stage spec P4 §6). Then a stage's maps flag for its morning
 * card (offline-maps spec D C4 §2.2): saved and unsaved, AE10's redrawn
 * stage, the wait for the store and past it, the live change on
 * regions-changed, and no read for any other Way. The tiles manager runs
 * over C2's fake loader on the test's dispatcher, virtual time and all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorOverviewViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources get() = context.resources
    private val dispatcher = UnconfinedTestDispatcher()
    private val utc = ZoneId.of("UTC")
    private val sourceUuid = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
    private val recordingPath = "recordings/$sourceUuid/r1.wav"
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private val storeDirectory = File(context.filesDir, "overview-ways")
    private val store = WayStore({ storeDirectory }, syncDirectory = { true })
    private val importScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var heldImport: Continuation<Way>? = null
    private val scheduler = FakeWayMediaDownloadScheduler()
    private val downloader by lazy { WayMediaDownloader(store, scheduler, importScope, dispatcher) }
    private val imports = HonorImportCoordinator(
        importShare = { suspendCoroutine { heldImport = it } },
        honorEnabled = true,
        scope = importScope,
        media = { downloader },
    )
    private val sharedWaveform = FloatArray(4) { 0.5f }
    private val viewModels = mutableListOf<HonorOverviewViewModel>()
    private val tilesEscaped = CopyOnWriteArrayList<Throwable>()
    private val tilesScope = CoroutineScope(SupervisorJob() + dispatcher + recordingHandler(tilesEscaped))
    private val tilesLoader = FakeTileRegionLoader()
    private val tiles = PilgrimageTilesManager(tilesLoader, InMemoryTilesCalibration(), FakeWalkSignals(), dispatcher, tilesScope)
    private var tilesResolutions = 0
    private var sourceId = 0L
    private var recordingId = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(dispatcher)
        // Room and every IO hop run on the test dispatcher, so the overview
        // settles synchronously and nothing outlives db.close().
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
        sourceId = db.walkDao().insert(
            Walk(
                uuid = sourceUuid,
                startTimestamp = 1_700_000_000_000L,
                endTimestamp = 1_700_000_600_000L,
                intention = "the long way",
                weatherCondition = "lightRain",
                weatherTemperature = 9.0,
            ),
        )
        (0..10).forEach { i ->
            db.routeDataSampleDao().insert(
                RouteDataSample(
                    walkId = sourceId,
                    timestamp = 1_700_000_000_000L + i * 60_000L,
                    latitude = 0.0,
                    longitude = i * 0.001,
                ),
            )
        }
        recordingId = db.voiceRecordingDao().insert(
            VoiceRecording(
                walkId = sourceId,
                startTimestamp = 1_700_000_060_000L,
                endTimestamp = 1_700_000_090_000L,
                durationMillis = 30_000L,
                fileRelativePath = recordingPath,
                transcription = "the bridge where we stopped.",
            ),
        )
        File(context.filesDir, recordingPath).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64) { 1 })
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            withTimeout(10_000L) { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        }
        importScope.cancel()
        tilesScope.cancel()
        db.close()
        File(context.filesDir, "recordings").deleteRecursively()
        storeDirectory.deleteRecursively()
        Dispatchers.resetMain()
        assertTrue("escaped the tiles manager's posted work: $tilesEscaped", tilesEscaped.isEmpty())
    }

    private fun overview(
        walkId: Long = sourceId,
        location: FakeLocationSource = FakeLocationSource(),
        weather: FakeWeatherFetching = FakeWeatherFetching(),
        preferences: HonorPreferencesRepository = FakeHonorPreferencesRepository(),
        playback: FakeVoicePlaybackController = FakeVoicePlaybackController(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_SOURCE_WALK_ID to walkId)),
        stageHandoff: HonorStageHandoff = HonorStageHandoff(),
        connectivity: InternetConnectionProbe = InternetConnectionProbe { true },
    ) = HonorOverviewViewModel(
        savedStateHandle = savedStateHandle,
        ownWalkWays = OwnWalkWays(repository, VoiceRecordingFileSystem(context), dispatcher, { utc }, { Locale.US }),
        wayStore = store,
        imports = imports,
        honorPreferences = preferences,
        unitsPreferences = FakeUnitsPreferencesRepository(),
        locationSource = location,
        weatherFetching = weather,
        playback = playback,
        recordingFiles = VoiceRecordingFileSystem(context),
        waveformCache = WaveformCache(),
        ioDispatcher = dispatcher,
        tiles = Provider { tiles.also { tilesResolutions++ } },
        tilesDispatcher = dispatcher,
        stageHandoff = stageHandoff,
        connectivity = connectivity,
        loadSharedWaveform = { sharedWaveform },
    ).also { viewModels += it }

    private fun TestScope.settled(vm: HonorOverviewViewModel): HonorOverviewUiState {
        advanceUntilIdle()
        return vm.state.value
    }

    private fun TestScope.ready(vm: HonorOverviewViewModel): HonorOverview =
        (settled(vm) as HonorOverviewUiState.Ready).overview

    @Test
    fun `the overview builds the source walk's Way once, with its pins and bounds`() = runTest(dispatcher) {
        val overview = ready(overview())

        assertEquals("walk:$sourceUuid", overview.way.id)
        assertEquals("the long way", overview.way.title)
        assertEquals(overview.way.moments.map { it.id }, overview.pins.map { it.momentId })
        assertEquals(MapCameraBounds(swLat = 0.0, swLng = 0.0, neLat = 0.0, neLng = 10 * 0.001), overview.bounds)
        assertEquals(overview.way.id, overview.line.wayId)
    }

    // Owner decision 2: iOS's Begin hands its captured `way` on (`MainCoordinatorView.swift:94@7c200bf`).
    @Test
    fun `Begin on a stage's overview hands over the stage it loaded, whatever the package holds since`() =
        runTest(dispatcher) {
            val loaded = HonorHarness.stage()
            store.save(loaded)
            val handoff = HonorStageHandoff()
            val vm = overview(
                savedStateHandle = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_WAY_ID to HonorHarness.STAGE_ID)),
                stageHandoff = handoff,
            )
            ready(vm)
            store.save(HonorHarness.stage(marks = emptyList(), title = "Larrasoaña to Pamplona, redrawn"))

            assertEquals(HonorWayChoice.Stored(HonorHarness.STAGE_ID), vm.begin())
            assertEquals(loaded, handoff.stage(HonorHarness.STAGE_ID))
        }

    @Test
    fun `Begin on an own walk's overview hands nothing over`() = runTest(dispatcher) {
        val handoff = HonorStageHandoff()
        val vm = overview(stageHandoff = handoff)
        val way = ready(vm).way

        assertEquals(HonorWayChoice.OwnWalk(sourceId), vm.begin())
        assertNull(handoff.stage(way.id))
    }

    @Test
    fun `a source walk that is gone, or has no route to follow, closes the overview`() = runTest(dispatcher) {
        val stub = db.walkDao().insert(
            Walk(uuid = "22222222-2222-4222-8222-222222222222", startTimestamp = 5L, endTimestamp = 9L),
        )

        assertEquals(HonorOverviewUiState.Unavailable, settled(overview(walkId = 999L)))
        assertEquals(HonorOverviewUiState.Unavailable, settled(overview(walkId = stub)))
    }

    @Test
    fun `the distance to the start and today's weather come from the phone's last fix`() = runTest(dispatcher) {
        val here = LocationPoint(timestamp = 1L, latitude = 0.0, longitude = -0.001)
        val weather = FakeWeatherFetching(
            WeatherSnapshot(
                WeatherCondition.CLEAR,
                temperatureCelsius = 12.0,
                humidityFraction = null,
                windSpeedMps = null,
            ),
        )
        val vm = overview(location = FakeLocationSource(lastKnown = here), weather = weather)

        val overview = ready(vm)

        assertEquals(111.3, overview.distanceToStartMeters!!, 0.5)
        assertEquals("clear", overview.todayCondition)
    }

    @Test
    fun `a fix that reaches the phone after the overview opens still brings the distance and today's weather`() =
        runTest(dispatcher) {
            val location = FakeLocationSource(lastKnown = null)
            val weather = FakeWeatherFetching(
                WeatherSnapshot(WeatherCondition.CLEAR, temperatureCelsius = 12.0, humidityFraction = null, windSpeedMps = null),
            )
            val vm = overview(location = location, weather = weather)
            advanceTimeBy(HonorOverviewViewModel.FIX_RETRY_INTERVAL_MILLIS * 3)

            location.lastKnown = LocationPoint(timestamp = 1L, latitude = 0.0, longitude = -0.001)
            val overview = ready(vm)

            assertEquals(111.3, overview.distanceToStartMeters!!, 0.5)
            assertEquals("clear", overview.todayCondition)
            assertEquals(1, weather.callCount.get())
        }

    @Test
    fun `the overview stops looking for a fix once its retries run out`() = runTest(dispatcher) {
        val location = FakeLocationSource(lastKnown = null)
        val vm = overview(location = location)
        advanceUntilIdle()

        location.lastKnown = LocationPoint(timestamp = 1L, latitude = 0.0, longitude = -0.001)

        assertNull(ready(vm).distanceToStartMeters)
    }

    @Test
    fun `without a fix there is no distance row and no Today sentence`() = runTest(dispatcher) {
        val weather = FakeWeatherFetching()
        val overview = ready(overview(weather = weather))

        assertNull(overview.distanceToStartMeters)
        assertNull(overview.todayCondition)
        assertEquals(0, weather.callCount.get())
    }

    // pilgrim-ios #109, matched: the toggle is the sticky preference alone,
    // so it reads on whatever the app's Sounds switch says.
    @Test
    fun `the toggle is the sticky preference, written on every change`() = runTest(dispatcher) {
        val preferences = FakeHonorPreferencesRepository()
        val vm = overview(preferences = preferences)
        assertTrue(vm.voicesEnabled.value)

        vm.setVoicesEnabled(false)
        advanceUntilIdle()

        assertFalse(preferences.voicesEnabled.value)
        assertFalse(overview(preferences = preferences).voicesEnabled.value)
    }

    @Test
    fun `a voice whose recording is here plays through the app's voice player`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController()
        val vm = overview(playback = playback)
        val voiceId = ready(vm).way.moments.single { it.kind is WayMomentKind.Voice }.id

        vm.openPreview(voiceId)
        vm.togglePreviewVoice(voiceId)
        vm.togglePreviewVoice(voiceId)
        vm.cyclePreviewSpeed()
        vm.closePreview()

        assertEquals(listOf(1f, 1.5f, 1f), playback.setSpeedCalls)
        assertEquals(listOf(recordingId), playback.playCalls)
        assertEquals(1, playback.pauseCalls.get())
        assertEquals(1, playback.stopCalls.get())
    }

    // F §14.3: iOS's preview player is its own, so its rate dies with it.
    @Test
    fun `a preview plays at 1x and gives the player back its own speed when it closes`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController().apply { setPlaybackSpeed(2f) }
        val vm = overview(playback = playback)
        val voiceId = ready(vm).way.moments.single { it.kind is WayMomentKind.Voice }.id

        vm.openPreview(voiceId)
        assertEquals(1f, playback.playbackSpeed.value)
        vm.cyclePreviewSpeed()
        vm.closePreview()

        assertEquals(2f, playback.playbackSpeed.value)
    }

    @Test
    fun `an overview that goes with its preview open gives the player back its own speed`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController().apply { setPlaybackSpeed(2f) }
        val vm = overview(playback = playback)
        vm.openPreview(ready(vm).way.moments.single { it.kind is WayMomentKind.Voice }.id)
        vm.cyclePreviewSpeed()

        androidx.lifecycle.ViewModelStore().apply { put("overview", vm) }.clear()

        assertEquals(2f, playback.playbackSpeed.value)
        assertEquals(1, playback.stopCalls.get())
    }

    // The screen opens the preview from its saved id, so a rotation opens
    // it again: the preview keeps the speed the walker chose in it.
    @Test
    fun `opening the preview that is already open changes nothing`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController()
        val vm = overview(playback = playback)
        val voiceId = ready(vm).way.moments.single { it.kind is WayMomentKind.Voice }.id
        vm.openPreview(voiceId)
        vm.cyclePreviewSpeed()

        vm.openPreview(voiceId)

        assertEquals(1.5f, playback.playbackSpeed.value)
    }

    // F2: after the process is killed with a preview open, a new model gets
    // the restored id from the screen and sets the preview up as a tap does.
    @Test
    fun `a preview restored onto a new overview loads its waveform at 1x`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController().apply { setPlaybackSpeed(2f) }
        val restored = overview(playback = playback)
        val voiceId = ready(restored).way.moments.single { it.kind is WayMomentKind.Voice }.id

        restored.openPreview(voiceId)
        advanceUntilIdle()

        assertEquals(setOf(recordingId), restored.waveforms.value.keys)
        assertEquals(1f, playback.playbackSpeed.value)
    }

    @Test
    fun `a scrub before play starts the voice, then seeks`() = runTest(dispatcher) {
        val playback = FakeVoicePlaybackController()
        val vm = overview(playback = playback)
        val voiceId = ready(vm).way.moments.single { it.kind is WayMomentKind.Voice }.id

        vm.seekPreviewVoice(voiceId, 0.4f)
        advanceUntilIdle()

        assertEquals(listOf(recordingId), playback.playCalls)
        assertEquals(listOf(0.4f), playback.seekCalls)
    }

    // Shared-walk spec S4 §8–§9: the overview of a listed Way.

    @Test
    fun `a listed Way's overview is read back from its id alone, as after a process death`() = runTest(dispatcher) {
        store.save(sharedWay())

        val vm = overview(savedStateHandle = stored())
        val overview = ready(vm)

        assertEquals(HonorWayChoice.Stored(SHARED_ID), overview.choice)
        assertEquals("Rúa do Franco → Obradoiro", overview.way.title)
        assertEquals(overview.way.moments.map { it.id }, overview.pins.map { it.momentId })
        assertTrue("a shared voice still gathering has nothing to play yet", overview.playableVoices.isEmpty())
    }

    @Test
    fun `a listed Way gone from the store closes the overview, and gathers nothing`() = runTest(dispatcher) {
        val vm = overview(savedStateHandle = stored())

        assertEquals(HonorOverviewUiState.Unavailable, settled(vm))
        assertEquals(HonorImportState.Idle, vm.importState.value)
    }

    @Test
    fun `a shared photo shows from its media file once that file is here, and is missing until then`() = runTest(dispatcher) {
        store.save(sharedWay())
        assertTrue(ready(overview(savedStateHandle = stored())).photoUris.isEmpty())

        val file = store.mediaFile(SHARED_ID, "photos/1.jpg")!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }

        assertEquals(mapOf("photo-1" to Uri.fromFile(file).toString()), ready(overview(savedStateHandle = stored())).photoUris)
    }

    // S4 §8.1: the first frame never shows an enabled Begin before the first gathering state.
    @Test
    fun `a shared overview is already gathering when its card first shows`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        var importStateWhenShown: HonorImportState? = null
        val watcher = backgroundScope.launch {
            vm.state.collect { if (it is HonorOverviewUiState.Ready && importStateWhenShown == null) importStateWhenShown = vm.importState.value }
        }

        ready(vm)

        assertEquals(HonorImportState.Gathering(0.0), importStateWhenShown)
        assertEquals(listOf(SHARED_ID), scheduler.gathers.map { it.wayId })
        watcher.cancel()
    }

    // S4 §8.3: disk full, then gathering, then missing, then ready.
    @Test
    fun `the overview's line follows the gather to missing voices, and its two buttons do what iOS's do`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        ready(vm)

        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 1, failures = emptyList(), diskFull = false))
        assertEquals(HonorImportState.Gathering(0.5), vm.importState.value)
        scheduler.report(SHARED_ID, WayMediaWork.State.SUCCEEDED, WayMediaReport(2, unfinished = 0, failures = listOf("photos/1.jpg"), diskFull = false))
        assertEquals(HonorImportState.MediaMissing(listOf("photos/1.jpg")), vm.importState.value)

        vm.retryMedia()
        assertEquals("try again gathers once more", HonorImportState.Gathering(0.0), vm.importState.value)
        assertEquals(listOf(false, true), scheduler.gathers.map { it.replace })
        scheduler.report(SHARED_ID, WayMediaWork.State.SUCCEEDED, WayMediaReport(2, unfinished = 0, failures = listOf("photos/1.jpg"), diskFull = false))

        vm.walkWithoutMissingVoices()
        assertEquals(HonorImportState.Ready, vm.importState.value)
    }

    @Test
    fun `disk full outranks the gather still going`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        ready(vm)

        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 1, failures = listOf("audio/1.m4a"), diskFull = true))

        assertEquals(HonorImportState.Failed(WayError.DISK_FULL), vm.importState.value)
    }

    @Test
    fun `a closed overview stops watching, and its transfers go on`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        ready(vm)

        androidx.lifecycle.ViewModelStore().apply { put("shared", vm) }.clear()
        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 1, failures = emptyList(), diskFull = false))

        assertEquals(HonorImportState.Idle, imports.state.value)
        assertTrue(scheduler.cancels.isEmpty())
    }

    // The preview plays a shared Way's `media/audio/<n>.m4a` through the same player, by file.
    @Test
    fun `a shared voice that has landed plays by file, scrubs, and shows its bars`() = runTest(dispatcher) {
        store.save(sharedWay())
        val file = store.mediaFile(SHARED_ID, "audio/1.m4a")!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }
        val playback = FakeVoicePlaybackController()
        val vm = overview(savedStateHandle = stored(), playback = playback)
        val voice = ready(vm).playableVoices.getValue("voice-1")

        vm.openPreview("voice-1")
        advanceUntilIdle()
        vm.togglePreviewVoice("voice-1")
        vm.seekPreviewVoice("voice-1", 0.25f)
        advanceUntilIdle()

        assertTrue("an id no recording row has", voice.playbackId < 0)
        assertEquals(40.0, voice.totalSeconds, 0.0)
        assertEquals(listOf(file), playback.playedFiles)
        assertEquals(listOf(0.25f), playback.seekCalls)
        assertEquals(sharedWaveform.toList(), vm.waveforms.value.getValue(voice.playbackId).toList())
    }

    // S4 §9.3: a preview opened before its voice arrived turns into the player once it has.
    @Test
    fun `a shared voice that lands while the overview is up becomes playable`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        assertTrue(ready(vm).playableVoices.isEmpty())

        store.mediaFile(SHARED_ID, "audio/1.m4a")!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }
        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 1, failures = emptyList(), diskFull = false))

        assertEquals(setOf("voice-1"), ready(vm).playableVoices.keys)
    }

    // S3 §8: disk full stops only its own file, so a later voice can still land, and the line no longer moves.
    @Test
    fun `a voice that lands after disk full still becomes playable`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        ready(vm)
        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 1, failures = listOf("photos/1.jpg"), diskFull = true))
        assertEquals(HonorImportState.Failed(WayError.DISK_FULL), vm.importState.value)

        store.mediaFile(SHARED_ID, "audio/1.m4a")!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }
        scheduler.report(SHARED_ID, WayMediaWork.State.RUNNING, WayMediaReport(2, unfinished = 0, failures = listOf("photos/1.jpg"), diskFull = true))

        assertEquals("the line holds still", HonorImportState.Failed(WayError.DISK_FULL), vm.importState.value)
        assertEquals(setOf("voice-1"), ready(vm).playableVoices.keys)
    }

    @Test
    fun `a voice that lands after walking on without the missing ones still becomes playable`() = runTest(dispatcher) {
        store.save(sharedWay())
        val vm = overview(savedStateHandle = stored())
        ready(vm)
        vm.walkWithoutMissingVoices()

        store.mediaFile(SHARED_ID, "audio/1.m4a")!!.apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(8))
        }
        scheduler.report(SHARED_ID, WayMediaWork.State.SUCCEEDED, WayMediaReport(2, unfinished = 0, failures = emptyList(), diskFull = false))

        assertEquals(setOf("voice-1"), ready(vm).playableVoices.keys)
    }

    // iOS `gather` and `handleOverviewDismiss` (S1 §6.3).
    @Test
    fun `the overview gathers its Way as it shows, and only its own real close hands the state back`() = runTest(dispatcher) {
        store.save(sharedWay())
        val own = overview()
        ready(own)
        assertEquals(HonorImportState.Ready, imports.state.value)
        val shared = overview(savedStateHandle = stored())
        ready(shared)

        androidx.lifecycle.ViewModelStore().apply { put("own", own) }.clear()
        assertEquals("the overview that replaced it holds the state", HonorImportState.Gathering(0.0), imports.state.value)
        androidx.lifecycle.ViewModelStore().apply { put("shared", shared) }.clear()

        assertEquals(HonorImportState.Idle, imports.state.value)
    }

    // S1 §8.16: a second link while an overview is up.
    @Test
    fun `a link fetched while the overview is up shows its line there`() = runTest(dispatcher) {
        val vm = overview()
        ready(vm)

        imports.openWay("Second1234")
        assertEquals(HonorImportState.Fetching, vm.importState.value)
        heldImport!!.resumeWithException(WayImportException(WayError.UNAVAILABLE))

        assertEquals(HonorImportState.Failed(WayError.UNAVAILABLE), vm.importState.value)
    }

    // F §9: the card's measured height is the bottom inset, padded 40 top,
    // 30 sides, and 40 + min(card, map − 240) bottom.
    @Test
    fun `the fit's bottom padding is the card height, capped to leave the route its room`() = runTest(dispatcher) {
        val bounds = ready(overview()).bounds!!

        fun fit(cardHeight: Double) = decideCameraFit(
            bounds,
            bottomInsetDp = cardHeight,
            viewWidthDp = 400.0,
            viewHeightDp = 800.0,
            lastApplied = null,
        )
        fun padding(bottom: Double) =
            CameraFitDecision.Fit(CameraFitPaddingDp(top = 40.0, left = 30.0, bottom = bottom, right = 30.0))

        assertEquals(padding(bottom = 340.0), fit(cardHeight = 300.0))
        assertEquals(padding(bottom = 600.0), fit(cardHeight = 700.0))
    }

    @Test
    fun `counts read voices then photos, else a quiet way`() {
        assertEquals("9 voices · 4 photos", HonorOverviewModel.countsLine(resources, way(voices = 9, photos = 4)))
        assertEquals("1 voice", HonorOverviewModel.countsLine(resources, way(voices = 1, photos = 0)))
        assertEquals("1 photo", HonorOverviewModel.countsLine(resources, way(voices = 0, photos = 1)))
        assertEquals("a quiet way", HonorOverviewModel.countsLine(resources, way(voices = 0, photos = 0)))
    }

    // iOS picks each word by `count == 1` (`HonorOverviewView.swift:9-10@7c200bf`), whatever the phone's plural rules.
    @Test
    @Config(qualifiers = "fr")
    fun `counts keep iOS's rule under French plural rules`() {
        assertEquals("1 voice · 1 photo", HonorOverviewModel.countsLine(resources, voiceCount = 1, photoCount = 1))
        assertEquals("2 voices · 2 photos", HonorOverviewModel.countsLine(resources, voiceCount = 2, photoCount = 2))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `counts keep iOS's rule under Japanese plural rules`() {
        assertEquals("1 voice · 1 photo", HonorOverviewModel.countsLine(resources, voiceCount = 1, photoCount = 1))
        assertEquals("12 voices · 20 photos", HonorOverviewModel.countsLine(resources, voiceCount = 12, photoCount = 20))
    }

    @Test
    fun `the duration is their active time in hours and minutes, seconds never shown`() {
        assertEquals("1h 5m", HonorOverviewModel.durationText(3_930.0))
        assertEquals("42m", HonorOverviewModel.durationText(2_545.0))
        assertEquals("0m", HonorOverviewModel.durationText(59.0))
        assertEquals("0m", HonorOverviewModel.durationText(Double.NaN))
    }

    @Test
    fun `the weather line compares their weather with today's`() {
        val theirs = WayWeather(condition = "rain", temperatureC = 9.0)

        assertEquals("they walked this in rain at 9°. Today is clear.", weather(theirs, today = "clear"))
        assertEquals("they walked this in rain at 9°.", weather(theirs, today = null))
        assertEquals(
            "they walked this in light rain. Today is partly cloudy.",
            weather(WayWeather("lightRain", null), today = "partlyCloudy"),
        )
        assertNull(weather(null, today = "clear"))
    }

    // pilgrim-ios #109, matched: rounded Celsius with a bare degree sign,
    // whatever the walker's units; the line has no units input at all.
    @Test
    fun `the temperature is rounded half away from zero, in Celsius`() {
        assertEquals("they walked this in snow at -3°.", weather(WayWeather("snow", -2.5), today = null))
        assertEquals("they walked this in clear at 9°.", weather(WayWeather("clear", 8.5), today = null))
        assertEquals("they walked this in foggy.", weather(WayWeather("fog", Double.NaN), today = null))
    }

    @Test
    fun `the distance to the start reads in the walker's units, and within 60 m on the way`() {
        fun metric(m: Double?) = HonorOverviewModel.statusLine(resources, m, UnitSystem.Metric)
        fun imperial(m: Double?) = HonorOverviewModel.statusLine(resources, m, UnitSystem.Imperial)

        assertEquals("you're on the way", metric(40.0))
        assertEquals("you're on the way", imperial(60.0))
        assertEquals("650 m from the start", metric(650.9))
        assertEquals("2.3 km from the start", metric(2_300.0))
        assertEquals("328 ft from the start", imperial(100.0))
        assertEquals("1.4 mi from the start", imperial(2_300.0))
        assertNull(metric(null))
    }

    @Test
    fun `the departure line is the long date and the short time`() {
        val way = way(voices = 0, photos = 0)
        val line = HonorOverviewModel.departureLine(way, utc, Locale.US).replace('\u202F', ' ')

        assertTrue(line, line.startsWith("November 14, 2023"))
        assertTrue(line, line.endsWith("10:13 PM"))
    }

    // ---- A pilgrimage stage (pilgrimage-stage spec P4 §6, §7.1) -----------
    //
    // The first four are iOS `PilgrimageStageWalkTests.swift@7c200bf`'s,
    // names kept, on its `stageWay()` fixture.

    @Test
    fun testTheStageLineStandsWhereADateWould() {
        val way = stageWay()

        assertEquals(
            "stage 1 of 33 · ${StageFormat.distance(24_200.0, UnitSystem.Metric)} · hard",
            WayStageLine.line(resources, way, UnitSystem.Metric),
        )
        val notAStage = way.copy(stage = null)
        assertNull("a shared walk still shows its date", WayStageLine.line(resources, notAStage, UnitSystem.Metric))
        assertTrue(way.isPilgrimageStage)
        assertFalse(notAStage.isPilgrimageStage)
    }

    @Test
    fun testTheFactsLineReadsInTheWalkersOwnUnit() {
        val stage = requireNotNull(stageWay().stage)

        val line = StageMorningCardModel.factsLine(resources, stage, UnitSystem.Metric)

        assertEquals(
            listOf(
                StageFormat.distance(24_200.0, UnitSystem.Metric),
                "${StageFormat.altitude(1419.0, UnitSystem.Metric)} up",
                "7 to 9 hours",
                "hard",
            ).joinToString(" · "),
            line,
        )
    }

    // iOS pins the walker's unit to kilometres first; here the unit is an argument.
    @Test
    fun testTheWeatherLineIsSilentWithoutASnapshot() {
        assertNull(StageMorningCardModel.weatherLine(resources, null, UnitSystem.Metric, Locale.US))
        val snapshot = WeatherSnapshot(WeatherCondition.CLEAR, temperatureCelsius = 9.0, humidityFraction = 0.4, windSpeedMps = 1.0)

        val line = StageMorningCardModel.weatherLine(resources, snapshot, UnitSystem.Metric, Locale.US)

        assertTrue(line ?: "nil", line?.startsWith("clear, ") == true)
        assertTrue(line ?: "nil", line?.contains("9") == true)
    }

    @Test
    fun testTheOfflineNoteIsSaidOnceAndOnlyForAStage() {
        fun note(isStage: Boolean, isConnected: Boolean, alreadyShown: Boolean) =
            HonorOverviewModel.offlineNote(isStage, isConnected, alreadyShown)?.let(resources::getString)

        assertEquals("map tiles need a connection; the way itself is on your phone.", note(true, false, false))
        assertNull("said once", note(isStage = true, isConnected = false, alreadyShown = true))
        assertNull("the tiles are coming", note(isStage = true, isConnected = true, alreadyShown = false))
        assertNull(
            "a shared walk offline has a different problem: its voices",
            note(isStage = false, isConnected = false, alreadyShown = false),
        )
    }

    // P4 §5.2–§5.3, owner decision 7: the live Francés and Shikoku figures, as StatsHelper prints them.
    @Test
    fun `the stage line reads the dataset's figures as iOS prints them, in the walker's unit`() {
        val frances = requireNotNull(stageWay().stage)
        val shikoku = frances.copy(routeId = "shikoku-88", count = 5, distanceKm = 28.3, difficulty = "")

        assertEquals("stage 1 of 33 · 24.2 km · hard", WayStageLine.line(resources, frances, UnitSystem.Metric))
        assertEquals("stage 1 of 33 · 15.04 mi · hard", WayStageLine.line(resources, frances, UnitSystem.Imperial))
        assertEquals("no difficulty, no part", "stage 1 of 5 · 28.3 km", WayStageLine.line(resources, shikoku, UnitSystem.Metric))
        assertEquals(
            "stage 33 of 33 · 764 km · moderate",
            WayStageLine.line(resources, frances.copy(index = 32, distanceKm = 764.0, difficulty = "moderate"), UnitSystem.Metric),
        )
    }

    @Test
    fun `a stage's overview reads its stage line where a shared walk's date would be`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())

        val way = ready(overview(savedStateHandle = stageArgs())).way

        assertEquals("stage 5 of 33 · 15.6 km · moderate", WayStageLine.line(resources, way, UnitSystem.Metric))
    }

    // pilgrim-ios #122 item 6, matched: the stats row is Stage 21-1's, so a
    // stage shows the build's clock and no counts, beside its line's own length.
    @Test
    fun `a stage's overview shows the dataset's synthesized clock and a quiet way, as iOS ships them`() =
        runTest(dispatcher) {
            store.save(HonorHarness.stage().copy(theirActiveSeconds = 28_800.0))

            val way = ready(overview(savedStateHandle = stageArgs())).way

            assertEquals("8h 0m", HonorOverviewModel.durationText(way.theirActiveSeconds))
            assertEquals("a quiet way", HonorOverviewModel.countsLine(resources, way))
            assertEquals("the geometry's length, not the stage's 15.6", 1_113.0, way.totalDistanceMeters, 0.0)
        }

    // P4 §6.5: the morning card reads the overview's own fetch, whole.
    @Test
    fun `a stage's overview keeps today's whole weather for its morning card`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val today = WeatherSnapshot(WeatherCondition.PARTLY_CLOUDY, temperatureCelsius = 9.4, humidityFraction = 0.4, windSpeedMps = 1.0)
        val vm = overview(
            savedStateHandle = stageArgs(),
            location = FakeLocationSource(lastKnown = LocationPoint(timestamp = 1L, latitude = 0.0, longitude = -0.001)),
            weather = FakeWeatherFetching(today),
        )

        val overview = ready(vm)

        assertEquals(today, overview.todayWeather)
        assertEquals("partlyCloudy", overview.todayCondition)
    }

    // P4 §6.4, A-4: once per install, written as it shows.
    @Test
    fun `a stage opened offline says the offline note once, then never again`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val preferences = FakeHonorPreferencesRepository()

        val first = ready(overview(savedStateHandle = stageArgs(), preferences = preferences, connectivity = { false }))
        val second = ready(overview(savedStateHandle = stageArgs(), preferences = preferences, connectivity = { false }))

        assertEquals(R.string.honor_overview_offline_note, first.offlineNote)
        assertNull("never again on this install", second.offlineNote)
        assertEquals(1, preferences.offlineNoteWrites.get())
    }

    @Test
    fun `a stage opened online says nothing and leaves the note for its first offline opening`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val preferences = FakeHonorPreferencesRepository()

        val online = ready(overview(savedStateHandle = stageArgs(), preferences = preferences, connectivity = { true }))

        assertNull(online.offlineNote)
        assertFalse("an online opening writes nothing", preferences.pilgrimageOfflineNoteShown)
        val offline = ready(overview(savedStateHandle = stageArgs(), preferences = preferences, connectivity = { false }))
        assertEquals(R.string.honor_overview_offline_note, offline.offlineNote)
    }

    // iOS's `guard way.isPilgrimageStage, !alreadyShown`: no probe that couldn't produce the note.
    @Test
    fun `only a stage whose note is still unsaid reads the connection, and only once`() = runTest(dispatcher) {
        store.save(sharedWay())
        store.save(HonorHarness.stage())
        var readings = 0
        val offline = InternetConnectionProbe { readings++; false }
        val unsaid = FakeHonorPreferencesRepository()

        assertNull(ready(overview(savedStateHandle = stored(), preferences = unsaid, connectivity = offline)).offlineNote)
        assertNull(ready(overview(preferences = unsaid, connectivity = offline)).offlineNote)
        assertEquals("a shared walk and an own walk never look", 0, readings)
        assertEquals(0, unsaid.offlineNoteWrites.get())

        val said = FakeHonorPreferencesRepository(pilgrimageOfflineNoteShown = true)
        assertNull(ready(overview(savedStateHandle = stageArgs(), preferences = said, connectivity = offline)).offlineNote)
        assertEquals("a stage whose note is said never looks", 0, readings)

        ready(overview(savedStateHandle = stageArgs(), preferences = unsaid, connectivity = offline))
        assertEquals(1, readings)
    }

    // A full disk or a corrupt preferences file costs the note's flag, never the rest of the overview.
    @Test
    fun `a note whose flag can't be saved still shows, and the distance and today's weather still come`() =
        runTest(dispatcher) {
            store.save(HonorHarness.stage())
            val unwritable = object : HonorPreferencesRepository by FakeHonorPreferencesRepository() {
                override suspend fun setPilgrimageOfflineNoteShown() {
                    throw IOException("No space left on device")
                }
            }
            val today = WeatherSnapshot(WeatherCondition.CLEAR, temperatureCelsius = 9.0, humidityFraction = null, windSpeedMps = null)
            val vm = overview(
                savedStateHandle = stageArgs(),
                preferences = unwritable,
                connectivity = { false },
                location = FakeLocationSource(lastKnown = LocationPoint(timestamp = 1L, latitude = 0.0, longitude = -0.001)),
                weather = FakeWeatherFetching(today),
            )

            val overview = ready(vm)

            assertEquals(R.string.honor_overview_offline_note, overview.offlineNote)
            assertNotNull("the distance to the start", overview.distanceToStartMeters)
            assertEquals(today, overview.todayWeather)
        }

    // ---- A stage's maps flag for its morning card (offline-maps spec D C4 §2.2) --

    /** Stage 5 of the harness, its region saved complete on its own line, and the store answered. */
    private fun withStageSaved() {
        store.save(HonorHarness.stage())
        tilesLoader.seed(HonorHarness.STAGE_ID, PilgrimageTilesCorridor.stage(HonorHarness.stage()).corridorHash)
        tilesLoader.releaseRegions()
    }

    private fun line(saved: Boolean?): String? = saved?.let { StageMorningCardModel.mapsLine(resources, it) }

    @Test
    fun `a saved stage's card reads maps saved for today`() = runTest(dispatcher) {
        withStageSaved()

        val overview = ready(overview(savedStateHandle = stageArgs()))

        assertEquals("maps saved for today", line(overview.mapsSaved))
    }

    @Test
    fun `an unsaved stage's card reads no offline maps for today`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        tilesLoader.releaseRegions()

        val overview = ready(overview(savedStateHandle = stageArgs()))

        assertEquals("no offline maps for today — save on wifi", line(overview.mapsSaved))
    }

    @Test
    fun `an incomplete region is not saved`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        tilesLoader.seed(HonorHarness.STAGE_ID, PilgrimageTilesCorridor.stage(HonorHarness.stage()).corridorHash, complete = false)
        tilesLoader.releaseRegions()

        assertEquals(false, ready(overview(savedStateHandle = stageArgs())).mapsSaved)
    }

    /**
     * AE10 from the card (C4 §6): every stage saved, then an Update redraws
     * stage 12. Its region is still complete, under the old line's hash,
     * and the overview reads the new Way, so the card says it isn't saved.
     */
    @Test
    fun `AE10's redrawn stage 12 reads no offline maps for today`() = runTest(dispatcher) {
        val before = stageWay(index = 12)
        val redrawn = before.copy(route = before.route.map { it.copy(lon = it.lon + 0.01) })
        tilesLoader.seed(before.id, PilgrimageTilesCorridor.stage(before).corridorHash)
        tilesLoader.seedStylePacks()
        tilesLoader.releaseRegions()
        store.save(redrawn)

        val overview = ready(overview(savedStateHandle = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_WAY_ID to redrawn.id))))

        assertEquals("no offline maps for today — save on wifi", line(overview.mapsSaved))
    }

    // C4 correction 13 and A3: absent while the first read waits, as after a UI restart, then the store's answer.
    @Test
    fun `a stage's line waits for the store's first answer, and is absent until then`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        tilesLoader.seed(HonorHarness.STAGE_ID, PilgrimageTilesCorridor.stage(HonorHarness.stage()).corridorHash)
        val vm = overview(savedStateHandle = stageArgs())
        advanceTimeBy(1_000)

        val pending = (vm.state.value as HonorOverviewUiState.Ready).overview
        assertNull("no line while the store hasn't answered", pending.mapsSaved)
        assertEquals(1, tilesLoader.firstAnswerRequests)

        tilesLoader.releaseRegions()

        assertEquals("maps saved for today", line(ready(vm).mapsSaved))
    }

    // Owner decision 3: past the bound, no line, never iOS's cold "no offline maps"; the store's regions answer then corrects it.
    @Test
    fun `past the store's wait the card has no maps line, and a later regions answer gives it one`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val vm = overview(savedStateHandle = stageArgs())
        advanceTimeBy(PilgrimageTilesManager.STORE_WAIT_MILLIS + 1)

        assertNull(ready(vm).mapsSaved)

        tilesLoader.seed(HonorHarness.STAGE_ID, PilgrimageTilesCorridor.stage(HonorHarness.stage()).corridorHash)
        tilesLoader.releaseRegions()

        assertEquals("maps saved for today", line(ready(vm).mapsSaved))
    }

    // iOS's `onReceive(regionsChanged)`: a save landing elsewhere, or a Delete, changes an open card's line.
    @Test
    fun `the line changes live on regions-changed, a save landing and a Delete`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        tilesLoader.releaseRegions()
        val vm = overview(savedStateHandle = stageArgs())
        assertEquals(false, ready(vm).mapsSaved)

        tilesLoader.seed(HonorHarness.STAGE_ID, PilgrimageTilesCorridor.stage(HonorHarness.stage()).corridorHash)
        tilesLoader.releaseRegions()
        assertEquals("a save lands", true, ready(vm).mapsSaved)

        tiles.remove("camino-frances")
        assertEquals("Settings' Delete", false, ready(vm).mapsSaved)
    }

    @Test
    fun `an own walk and a shared walk never ask the store`() = runTest(dispatcher) {
        store.save(sharedWay())
        tilesLoader.releaseRegions()

        val own = ready(overview())
        val shared = ready(overview(savedStateHandle = stored()))

        assertNull(own.mapsSaved)
        assertNull(shared.mapsSaved)
        assertEquals("the tiles manager is never resolved", 0, tilesResolutions)
        assertEquals(0 to 0, tilesLoader.firstAnswerRequests to tilesLoader.regionsReadCount)
    }

    // ---- A stage's service marks on the overview (pilgrimage-stage spec P5 §3) --

    @Test
    fun `a stage's overview draws no marks until its map first reports the camera`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val vm = overview(savedStateHandle = stageArgs())
        ready(vm)
        val beforeAnyReport = vm.markPins.value

        vm.onCameraChanged(WayCoordinate(lat = 0.0, lon = 0.003), zoom = 15.0)

        assertTrue("the overview opens fit to the whole Way, a zoom it never chose", beforeAnyReport.isEmpty())
        assertEquals(listOf("wp-osm-water-node1", "wp-osm-water-node2"), vm.markPins.value.map { it.markId })
    }

    // Where the walk screen's marks follow the walker, the overview's follow the camera.
    @Test
    fun `every report chooses the overview's marks again around the camera, a pan at one zoom included`() =
        runTest(dispatcher) {
            store.save(HonorHarness.stage())
            val vm = overview(savedStateHandle = stageArgs())
            ready(vm)
            vm.onCameraChanged(WayCoordinate(lat = 0.0, lon = 0.003), zoom = 15.0)
            val first = vm.markPins.value.first().markId

            vm.onCameraChanged(WayCoordinate(lat = 0.0, lon = 0.008), zoom = 15.0)

            assertEquals("wp-osm-water-node1" to "wp-osm-water-node2", first to vm.markPins.value.first().markId)
        }

    @Test
    fun `zoomed out below 13 the overview draws no marks`() = runTest(dispatcher) {
        store.save(HonorHarness.stage())
        val vm = overview(savedStateHandle = stageArgs())
        ready(vm)

        vm.onCameraChanged(WayCoordinate(lat = 0.0, lon = 0.003), zoom = 12.99)

        assertTrue(vm.markPins.value.isEmpty())
    }

    private fun stageArgs() = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_WAY_ID to HonorHarness.STAGE_ID))

    /**
     * iOS's `stageWay(index:)`: a 1 km stage east along the equator, one
     * waypoint at 0.3 with words, names, a sitting and a pin, and the
     * Francés stage 1 block.
     */
    private fun stageWay(index: Int = 0) = Way(
        id = WayStore.stageWayId("camino-frances", index),
        source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = index),
        title = "Saint-Jean-Pied-de-Port to Roncesvalles",
        departedAt = Instant.ofEpochSecond(1_000_000),
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.000898, alt = null, t = it * 60.0) },
        totalDistanceMeters = 1000.0,
        theirActiveSeconds = 600.0,
        moments = listOf(
            WayMoment(
                id = "wp-orisson",
                frac = 0.3,
                at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
                kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
                text = "A shepherd carried this Madonna up from Lourdes.",
                names = mapOf("eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson"),
                sitMinutes = 5,
                pin = WayCoordinate(lat = 0.0002, lon = 300.0 / 111_320),
            ),
        ),
        weather = null,
        marks = emptyList(),
        stage = WayStage(
            routeId = "camino-frances", index = index, count = 33,
            name = "Saint-Jean-Pied-de-Port to Roncesvalles", theme = "Initiation",
            narrative = "The Pyrenees are the first question the way asks.",
            closing = "You crossed a border on foot.",
            warnings = listOf("The Napoleon Route closes in winter."),
            distanceKm = 24.2, gainMeters = 1419.0, hours = WayStageHours(min = 7.0, max = 9.0), difficulty = "hard",
            start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 0.0, lon = 0.0)),
            end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 0.0, lon = 0.00898)),
        ),
    )

    private fun stored() = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_WAY_ID to SHARED_ID))

    /** A share as the importer builds one: file media, a voice with its street, a photo, and an estimated sitting. */
    private fun sharedWay() = Way(
        id = SHARED_ID,
        source = WaySource.Share(id = "Qoi4YmPHLN", pageUrl = "https://walk.pilgrimapp.org/Qoi4YmPHLN"),
        title = "Rúa do Franco → Obradoiro",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.parse("2099-01-01T00:00:00Z"),
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 540.0,
        moments = listOf(
            org.walktalkmeditate.pilgrim.domain.honor.WayMoment(
                id = "voice-1", frac = 0.5, at = null,
                kind = WayMomentKind.Voice(
                    endFrac = 0.6, duration = 40.0,
                    kind = org.walktalkmeditate.pilgrim.domain.honor.VoiceKind.SPOKEN,
                    media = org.walktalkmeditate.pilgrim.domain.honor.WayMedia.File("audio/1.m4a"),
                ),
                place = "Rúa do Franco",
            ),
            org.walktalkmeditate.pilgrim.domain.honor.WayMoment(
                id = "photo-1", frac = 0.8, at = null,
                kind = WayMomentKind.Photo(org.walktalkmeditate.pilgrim.domain.honor.WayMedia.File("photos/1.jpg")),
            ),
            org.walktalkmeditate.pilgrim.domain.honor.WayMoment(
                id = "sit-1", frac = 0.75, at = null,
                kind = WayMomentKind.Meditation(minutes = 3, isEstimate = true),
            ),
        ),
        weather = WayWeather("rain", 9.0),
        spans = emptyList(),
    )

    private fun weather(theirs: WayWeather?, today: String?) =
        HonorOverviewModel.weatherLine(resources, theirs, today, Locale.US)

    private fun way(voices: Int, photos: Int) = Way(
        id = "walk:$sourceUuid",
        source = WaySource.OwnWalk(sourceUuid),
        title = "t",
        departedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        tzIdentifier = "UTC",
        expires = null,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = List(voices) { i ->
            org.walktalkmeditate.pilgrim.domain.honor.WayMoment(
                id = "voice-${i + 1}", frac = 0.1, at = null,
                kind = WayMomentKind.Voice(
                    endFrac = 0.2, duration = 5.0,
                    kind = org.walktalkmeditate.pilgrim.domain.honor.VoiceKind.SPOKEN,
                    media = org.walktalkmeditate.pilgrim.domain.honor.WayMedia.Recording("r$i.wav"),
                ),
            )
        } + List(photos) { i ->
            org.walktalkmeditate.pilgrim.domain.honor.WayMoment(
                id = "photo-${i + 1}", frac = 0.5, at = null,
                kind = WayMomentKind.Photo(
                    org.walktalkmeditate.pilgrim.domain.honor.WayMedia.PhotoAsset("content://p$i"),
                ),
            )
        },
        weather = null,
    )

    private companion object {
        const val SHARED_ID = "share:Qoi4YmPHLN"
    }
}
