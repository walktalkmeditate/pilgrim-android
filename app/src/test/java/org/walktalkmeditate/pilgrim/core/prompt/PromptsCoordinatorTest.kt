// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.photo.BitmapLoader
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.ActivityType

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptsCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var customStyleFile: File
    private lateinit var customStyleDataStore: DataStore<Preferences>
    private lateinit var photoCacheFile: File
    private lateinit var photoCacheDataStore: DataStore<Preferences>
    private lateinit var customStyleScope: CoroutineScope
    private lateinit var customStyleStore: CustomPromptStyleStore

    private val dispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val nyZone: ZoneId = ZoneId.of("America/New_York")
    private val testStartTimestamp: Long = LocalDateTime.of(2026, 5, 4, 9, 41)
        .atZone(nyZone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
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
        customStyleFile = File(context.cacheDir, "prompts-coord-styles-${System.nanoTime()}.preferences_pb")
        photoCacheFile = File(context.cacheDir, "prompts-coord-photo-cache-${System.nanoTime()}.preferences_pb")
        customStyleScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        customStyleDataStore = PreferenceDataStoreFactory.create(
            scope = customStyleScope,
            produceFile = { customStyleFile },
        )
        photoCacheDataStore = PreferenceDataStoreFactory.create(
            scope = customStyleScope,
            produceFile = { photoCacheFile },
        )
        customStyleStore = CustomPromptStyleStore(customStyleDataStore, json, customStyleScope)
    }

    @After
    fun tearDown() {
        customStyleScope.cancel()
        customStyleFile.delete()
        photoCacheFile.delete()
        db.close()
    }

    private fun newCoordinator(
        photoAnalyzer: PhotoContextAnalyzer = newRealAnalyzer(),
        geocoder: PromptGeocoder = StubGeocoder(),
        practicePrefs: FakePracticePreferencesRepository = FakePracticePreferencesRepository(),
        unitsPrefs: FakeUnitsPreferencesRepository = FakeUnitsPreferencesRepository(),
    ): PromptsCoordinator = PromptsCoordinator(
        repository = repository,
        customStyleStore = customStyleStore,
        photoContextAnalyzer = photoAnalyzer,
        geocoder = geocoder,
        promptGenerator = PromptGenerator(context),
        practicePreferences = practicePrefs,
        unitsPreferences = unitsPrefs,
        appContext = context,
    )

    /**
     * Returns a real [PhotoContextAnalyzer] wired with empty fakes so
     * `analyze` always returns the sentinel-empty `PhotoContext`. Tests
     * that need controlled per-URI results substitute [StubPhotoAnalyzer].
     */
    private fun newRealAnalyzer(): PhotoContextAnalyzer = PhotoContextAnalyzer(
        dataStore = photoCacheDataStore,
        json = json,
        bitmapLoader = object : BitmapLoader {
            override suspend fun load(uri: Uri) = null
        },
        imageLabeler = object : ImageLabelerClient {
            override suspend fun label(bitmap: android.graphics.Bitmap) = emptyList<LabeledTag>()
        },
        textRecognizer = object : TextRecognizerClient {
            override suspend fun recognize(bitmap: android.graphics.Bitmap) = emptyList<String>()
        },
        faceDetector = object : FaceDetectorClient {
            override suspend fun detect(bitmap: android.graphics.Bitmap) = 0
        },
    )

    private suspend fun insertWalkRow(
        startTimestamp: Long = testStartTimestamp,
        endTimestamp: Long? = testStartTimestamp + 1_800_000L,
        intention: String? = null,
        weatherCondition: String? = null,
        weatherTemperature: Double? = null,
    ): Walk {
        val draft = Walk(
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            intention = intention,
            distanceMeters = 2_000.0,
            weatherCondition = weatherCondition,
            weatherTemperature = weatherTemperature,
        )
        val id = db.walkDao().insert(draft)
        return draft.copy(id = id)
    }

    @Test
    fun `buildContext returns null when walk is missing`() = runTest(dispatcher) {
        val coordinator = newCoordinator()
        assertNull(coordinator.buildContext(walkId = 999L, zone = nyZone))
    }

    @Test
    fun `buildContext minimal walk returns context with empty collections`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        val coordinator = newCoordinator()
        val ctx = coordinator.buildContext(walkId = walk.id, zone = nyZone)
        assertNotNull(ctx)
        assertEquals(emptyList<RecordingContext>(), ctx!!.recordings)
        assertEquals(emptyList<MeditationContext>(), ctx.meditations)
        assertEquals(emptyList<WaypointContext>(), ctx.waypoints)
        assertEquals(emptyList<PhotoContextEntry>(), ctx.photoContexts)
        assertEquals(emptyList<PlaceContext>(), ctx.placeNames)
        assertEquals(emptyList<WalkSnippet>(), ctx.recentWalkSnippets)
        assertEquals(emptyList<Double>(), ctx.routeSpeeds)
        assertNull(ctx.weather)
        assertNull(ctx.celestial)
        assertNull(ctx.intention)
        assertNotNull("lunarPhase always non-null", ctx.lunarPhase)
        assertNull("narrativeArc null when no photos", ctx.narrativeArc)
    }

    @Test
    fun `buildContext maps voice recordings to RecordingContexts with closest coords`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 1_000L, latitude = 40.0, longitude = -73.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 60_000L, latitude = 40.001, longitude = -73.001),
        )
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id,
                startTimestamp = testStartTimestamp + 1_500L,
                endTimestamp = testStartTimestamp + 5_500L,
                durationMillis = 4_000L,
                fileRelativePath = "voice/r1.opus",
                transcription = "Hello world",
                wordsPerMinute = 120.0,
            ),
        )
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id,
                startTimestamp = testStartTimestamp + 50_000L,
                endTimestamp = testStartTimestamp + 60_000L,
                durationMillis = 10_000L,
                fileRelativePath = "voice/r2.opus",
                transcription = "Second line",
            ),
        )

        val coordinator = newCoordinator()
        val ctx = coordinator.buildContext(walkId = walk.id, zone = nyZone)!!

        assertEquals(2, ctx.recordings.size)
        assertEquals("Hello world", ctx.recordings[0].text)
        assertEquals(LatLng(40.0, -73.0), ctx.recordings[0].startCoordinate)
        assertEquals(120.0, ctx.recordings[0].wordsPerMinute!!, 1e-9)
        assertEquals(LatLng(40.001, -73.001), ctx.recordings[1].endCoordinate)
    }

    @Test
    fun `buildContext keeps only MEDITATING intervals`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        listOf(
            ActivityInterval(
                walkId = walk.id,
                startTimestamp = testStartTimestamp + 1_000L,
                endTimestamp = testStartTimestamp + 91_000L,
                activityType = ActivityType.MEDITATING,
            ),
            ActivityInterval(
                walkId = walk.id,
                startTimestamp = testStartTimestamp + 100_000L,
                endTimestamp = testStartTimestamp + 200_000L,
                activityType = ActivityType.WALKING,
            ),
            ActivityInterval(
                walkId = walk.id,
                startTimestamp = testStartTimestamp + 300_000L,
                endTimestamp = testStartTimestamp + 360_000L,
                activityType = ActivityType.MEDITATING,
            ),
        ).forEach { repository.recordActivityInterval(it) }

        val ctx = newCoordinator().buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(2, ctx.meditations.size)
        assertEquals(90L, ctx.meditations[0].durationSeconds)
        assertEquals(60L, ctx.meditations[1].durationSeconds)
    }

    @Test
    fun `buildContext maps waypoints with labels and coordinates`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        repository.addWaypoint(
            Waypoint(
                walkId = walk.id,
                timestamp = testStartTimestamp + 60_000L,
                latitude = 40.5, longitude = -73.5,
                label = "Bench under maple",
                icon = "tree",
            ),
        )
        repository.addWaypoint(
            Waypoint(
                walkId = walk.id,
                timestamp = testStartTimestamp + 120_000L,
                latitude = 40.6, longitude = -73.6,
                label = null,
            ),
        )

        val ctx = newCoordinator().buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(1, ctx.waypoints.size)
        assertEquals("Bench under maple", ctx.waypoints[0].label)
        assertEquals(LatLng(40.5, -73.5), ctx.waypoints[0].coordinate)
    }

    @Test
    fun `buildContext analyzes each photo and computes coordinate via samples`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 1_000L, latitude = 40.0, longitude = -73.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 30_000L, latitude = 40.01, longitude = -73.01),
        )
        repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://photos/1",
            takenAt = testStartTimestamp + 5_000L,
            pinnedAt = testStartTimestamp + 5_000L,
        )
        repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://photos/2",
            takenAt = null,
            pinnedAt = testStartTimestamp + 28_000L,
        )

        val analyzer = StubPhotoAnalyzer(
            photoCacheDataStore, json,
            results = mapOf(
                "content://photos/1" to PhotoContext(
                    tags = listOf("plant"),
                    detectedText = emptyList(),
                    people = 0,
                    outdoor = true,
                    dominantColor = "#112233",
                ),
                "content://photos/2" to PhotoContext(
                    tags = listOf("building"),
                    detectedText = emptyList(),
                    people = 1,
                    outdoor = false,
                    dominantColor = "#445566",
                ),
            ),
        )
        val ctx = newCoordinator(photoAnalyzer = analyzer).buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(2, ctx.photoContexts.size)
        assertEquals(0, ctx.photoContexts[0].index)
        assertEquals(LatLng(40.0, -73.0), ctx.photoContexts[0].coordinate)
        assertEquals("#112233", ctx.photoContexts[0].context.dominantColor)
        assertEquals(LatLng(40.01, -73.01), ctx.photoContexts[1].coordinate)
        assertEquals(setOf("content://photos/1", "content://photos/2"), analyzer.calledUris)
        assertNotNull("narrativeArc populated when photos present", ctx.narrativeArc)
    }

    @Test
    fun `buildContext recentWalks filters to those with non-blank transcription and caps at 3`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        val recents = (1..5).map { i ->
            insertWalkRow(
                startTimestamp = testStartTimestamp - i * 100_000L,
                endTimestamp = testStartTimestamp - i * 100_000L + 60_000L,
            )
        }
        recordingForWalk(recents[0], "first thoughts a")
        recordingForWalk(recents[1], "second thoughts b")
        recordingForWalk(recents[2], null) // tombstone-only — filtered out
        // recents[3] has zero recordings — filtered out
        recordingForWalk(recents[4], "fourth thoughts d")

        val ctx = newCoordinator().buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(3, ctx.recentWalkSnippets.size)
        assertTrue(ctx.recentWalkSnippets[0].transcriptionPreview.contains("first thoughts a"))
        assertTrue(ctx.recentWalkSnippets[1].transcriptionPreview.contains("second thoughts b"))
        assertTrue(ctx.recentWalkSnippets[2].transcriptionPreview.contains("fourth thoughts d"))
    }

    @Test
    fun `buildContext when celestial pref disabled returns null celestial and null snippet summary`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        val recent = insertWalkRow(
            startTimestamp = testStartTimestamp - 100_000L,
            endTimestamp = testStartTimestamp - 30_000L,
        )
        recordingForWalk(recent, "earlier voice")
        val coordinator = newCoordinator(
            practicePrefs = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = false),
        )

        val ctx = coordinator.buildContext(walkId = walk.id, zone = nyZone)!!
        assertNull(ctx.celestial)
        assertEquals(1, ctx.recentWalkSnippets.size)
        assertNull(ctx.recentWalkSnippets[0].celestialSummary)
    }

    @Test
    fun `buildContext when celestial pref enabled populates snapshot and snippet summary`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        val recent = insertWalkRow(
            startTimestamp = testStartTimestamp - 100_000L,
            endTimestamp = testStartTimestamp - 30_000L,
        )
        recordingForWalk(recent, "earlier voice")
        val coordinator = newCoordinator(
            practicePrefs = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true),
        )

        val ctx = coordinator.buildContext(walkId = walk.id, zone = nyZone)!!
        assertNotNull(ctx.celestial)
        assertEquals(1, ctx.recentWalkSnippets.size)
        val summary = ctx.recentWalkSnippets[0].celestialSummary
        assertNotNull(summary)
        assertTrue(
            "expected 'Sun in <sign>, Moon in <sign>'; got '$summary'",
            summary!!.startsWith("Sun in ") && summary.contains(", Moon in "),
        )
    }

    @Test
    fun `buildContext when geocoder returns null produces empty placeNames`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 1_000L, latitude = 40.0, longitude = -73.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 30_000L, latitude = 40.5, longitude = -73.5),
        )

        val ctx = newCoordinator(geocoder = StubGeocoder(startResult = null, endResult = null))
            .buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(emptyList<PlaceContext>(), ctx.placeNames)
    }

    @Test
    fun `buildContext pre-formats weather when walk has weather fields`() = runTest(dispatcher) {
        val walk = insertWalkRow(weatherCondition = "clear", weatherTemperature = 20.0)

        val ctx = newCoordinator().buildContext(walkId = walk.id, zone = nyZone)!!
        assertNotNull(ctx.weather)
        assertTrue(
            "expected 'Weather: ' prefix; got '${ctx.weather}'",
            ctx.weather!!.startsWith("Weather: "),
        )
    }

    @Test
    fun `buildContext computes route speeds from consecutive sample pairs`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp, latitude = 40.0, longitude = -73.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 10_000L, latitude = 40.0001, longitude = -73.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = testStartTimestamp + 20_000L, latitude = 40.0002, longitude = -73.0),
        )

        val ctx = newCoordinator().buildContext(walkId = walk.id, zone = nyZone)!!
        assertEquals(2, ctx.routeSpeeds.size)
        ctx.routeSpeeds.forEach { speed -> assertTrue("speed should be positive: $speed", speed > 0.0) }
    }

    @Test
    fun `generateAll returns six built-in plus N custom prompts`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        val coordinator = newCoordinator()
        coordinator.saveCustomStyle(CustomPromptStyle(title = "First", icon = "flame", instruction = "First inst."))
        coordinator.saveCustomStyle(CustomPromptStyle(title = "Second", icon = "flame", instruction = "Second inst."))
        awaitStylesSize(2)

        val prompts = coordinator.generateAll(walkId = walk.id, zone = nyZone)
        assertEquals(8, prompts.size)
        assertEquals("First", prompts[6].title)
        assertEquals("Second", prompts[7].title)
    }

    @Test
    fun `generateAll returns empty when walk is missing`() = runTest(dispatcher) {
        val coordinator = newCoordinator()
        assertEquals(emptyList<GeneratedPrompt>(), coordinator.generateAll(walkId = 999L, zone = nyZone))
    }

    @Test
    fun `saveCustomStyle delegates to the underlying store`() = runTest(dispatcher) {
        val coordinator = newCoordinator()
        val style = CustomPromptStyle(title = "Saved", icon = "leaf", instruction = "Be present.")

        coordinator.saveCustomStyle(style)
        awaitStylesSize(1)

        val stored = coordinator.customStyles.value
        assertEquals(1, stored.size)
        assertEquals(style, stored.first())
    }

    @Test
    fun `deleteCustomStyle removes the style from the store`() = runTest(dispatcher) {
        val coordinator = newCoordinator()
        val style = CustomPromptStyle(title = "Tmp", icon = "leaf", instruction = "Listen.")

        coordinator.saveCustomStyle(style)
        awaitStylesSize(1)

        coordinator.deleteCustomStyle(style)
        awaitStylesSize(0)

        assertFalse(coordinator.customStyles.value.any { it.id == style.id })
    }

    @Test
    fun `generateAll uses imperial when units pref is imperial`() = runTest(dispatcher) {
        val walk = insertWalkRow()
        (0..20).forEach { i ->
            repository.recordLocation(
                RouteDataSample(
                    walkId = walk.id,
                    timestamp = testStartTimestamp + i * 1_000L,
                    latitude = 40.0 + i * 0.0001,
                    longitude = -73.0,
                ),
            )
        }
        val coordinator = newCoordinator(unitsPrefs = FakeUnitsPreferencesRepository(initial = UnitSystem.Imperial))

        val prompts = coordinator.generateAll(walkId = walk.id, zone = nyZone)
        assertNotNull(prompts.firstOrNull { it.text.contains("min/mi") })
    }

    /**
     * Wait for the custom-style hot StateFlow to publish a list of size
     * [target]. `withTimeout` under runTest uses VIRTUAL time and never
     * advances when the producer is on the same UnconfinedTestDispatcher
     * (Stage 3-D bridging lesson), so this hop poll-reads `.value` on
     * `Dispatchers.Default` to bridge to wall-clock time. ~50ms typical
     * latency; 5s ceiling.
     */
    private suspend fun awaitStylesSize(target: Int) {
        kotlinx.coroutines.withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) {
                while (customStyleStore.styles.value.size != target) {
                    kotlinx.coroutines.delay(10L)
                }
            }
        }
    }

    private suspend fun recordingForWalk(walk: Walk, text: String?) {
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id,
                startTimestamp = walk.startTimestamp + 1_000L,
                endTimestamp = walk.startTimestamp + 2_000L,
                durationMillis = 1_000L,
                fileRelativePath = "voice/r-${walk.id}.opus",
                transcription = text,
            ),
        )
    }
}

/**
 * Open `PhotoContextAnalyzer` substitute that returns predetermined
 * [PhotoContext]s by URI. Constructed against the same dependencies the
 * production coordinator wires so the type satisfies Hilt — but `analyze`
 * is fully overridden and never reaches the real ML Kit pipelines.
 */
private class StubPhotoAnalyzer(
    dataStore: DataStore<Preferences>,
    json: Json,
    private val results: Map<String, PhotoContext> = emptyMap(),
) : PhotoContextAnalyzer(
    dataStore = dataStore,
    json = json,
    bitmapLoader = object : BitmapLoader {
        override suspend fun load(uri: Uri) = null
    },
    imageLabeler = object : ImageLabelerClient {
        override suspend fun label(bitmap: android.graphics.Bitmap) = emptyList<LabeledTag>()
    },
    textRecognizer = object : TextRecognizerClient {
        override suspend fun recognize(bitmap: android.graphics.Bitmap) = emptyList<String>()
    },
    faceDetector = object : FaceDetectorClient {
        override suspend fun detect(bitmap: android.graphics.Bitmap) = 0
    },
) {
    val calledUris: MutableSet<String> = mutableSetOf()
    override suspend fun analyze(uri: Uri): PhotoContext {
        val key = uri.toString()
        calledUris += key
        return results[key] ?: PhotoContext(
            tags = emptyList(),
            detectedText = emptyList(),
            people = 0,
            outdoor = false,
            dominantColor = "#000000",
        )
    }
}

private class StubGeocoder(
    private val startResult: PlaceContext? = PlaceContext(
        name = "Stub Park",
        coordinate = LatLng(0.0, 0.0),
        role = PlaceRole.Start,
    ),
    private val endResult: PlaceContext? = null,
) : PromptGeocoder(
    context = ApplicationProvider.getApplicationContext<Application>(),
) {
    override suspend fun geocodeStart(coord: LatLng): PlaceContext? =
        startResult?.copy(coordinate = coord)
    override suspend fun geocodeEnd(coord: LatLng, distanceFromStartMeters: Double): PlaceContext? =
        endResult?.copy(coordinate = coord)
}
