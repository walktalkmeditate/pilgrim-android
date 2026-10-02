// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.FakeHonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.data.weather.FakeWeatherFetching
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformCache
import org.walktalkmeditate.pilgrim.ui.walk.map.CameraFitDecision
import org.walktalkmeditate.pilgrim.ui.walk.map.CameraFitPaddingDp
import org.walktalkmeditate.pilgrim.ui.walk.map.decideCameraFit
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/** The overview's Way, lines, framing inputs, toggle, and preview player (parity spec F §8–§14). */
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
    private val imports = HonorImportCoordinator(
        importShare = { suspendCoroutine { heldImport = it } },
        honorEnabled = true,
        scope = importScope,
    )
    private val viewModels = mutableListOf<HonorOverviewViewModel>()
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
        db.close()
        File(context.filesDir, "recordings").deleteRecursively()
        storeDirectory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun overview(
        walkId: Long = sourceId,
        location: FakeLocationSource = FakeLocationSource(),
        weather: FakeWeatherFetching = FakeWeatherFetching(),
        preferences: FakeHonorPreferencesRepository = FakeHonorPreferencesRepository(),
        playback: FakeVoicePlaybackController = FakeVoicePlaybackController(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf(HonorOverviewViewModel.ARG_SOURCE_WALK_ID to walkId)),
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
        assertTrue("a shared voice has no recording row to play yet", overview.playableVoices.isEmpty())
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
        assertEquals("the overview that replaced it holds the state", HonorImportState.Ready, imports.state.value)
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
