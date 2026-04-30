// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.appearance.FakeAppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.data.collective.CollectiveMilestone
import org.walktalkmeditate.pilgrim.data.collective.MilestoneChecking
import org.walktalkmeditate.pilgrim.data.collective.MilestoneSurface
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * SettingsViewModel is a thin passthrough to [CollectiveRepository]:
 *  - `stats: StateFlow<CollectiveStats?> = repo.stats`
 *  - `optIn: StateFlow<Boolean> = repo.optIn`
 *  - `fun setOptIn(value)` → `viewModelScope.launch { repo.setOptIn(value) }`
 *  - `fun fetchOnAppear()` → `viewModelScope.launch { repo.fetchIfStale() }`
 *
 * The two StateFlow passthroughs are exercised here. The two
 * `viewModelScope.launch`-based delegations are NOT tested via the VM
 * here — they hit a brittle Robolectric main-Looper / runBlocking /
 * viewModelScope-launch interaction where any test that suspends
 * across the launch boundary while sharing the same simulated main
 * thread deadlocks under multi-class run ordering. Their underlying
 * behavior is fully covered in CollectiveRepositoryTest (setOptIn
 * round-trip + fetchIfStale TTL gate). The VM bodies themselves are
 * one-line `viewModelScope.launch { repo.foo(arg) }`s — code reading
 * verifies the wiring more reliably than a flaky integration test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var fakeService: FakeCounterService
    private lateinit var scope: CoroutineScope
    private lateinit var repo: CollectiveRepository
    private lateinit var db: PilgrimDatabase
    private lateinit var walkRepository: CountingWalkRepository
    private lateinit var voicePreferences: FakeVoicePreferencesRepository
    private lateinit var voiceFs: VoiceRecordingFileSystem
    private lateinit var milestoneSurface: FakeMilestoneSurface
    private lateinit var bellPlayer: RecordingBellPlayer
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val unique = "test_settings_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        fakeService = FakeCounterService(context, json)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repo = CollectiveRepository(cacheStore, fakeService, scope, NoopMilestoneChecker)
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walkRepository = CountingWalkRepository(
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
        voicePreferences = FakeVoicePreferencesRepository()
        voiceFs = VoiceRecordingFileSystem(context)
        milestoneSurface = FakeMilestoneSurface()
        bellPlayer = RecordingBellPlayer()
        vm = SettingsViewModel(
            collectiveRepository = repo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = FakeSoundsPreferencesRepository(),
            practicePreferences = FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = voicePreferences,
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
            milestoneSurface = milestoneSurface,
            bellPlayer = bellPlayer,
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `stats StateFlow proxies the repo's stats`() = runBlocking {
        cacheStore.writeStats(
            CollectiveStats(totalWalks = 17, totalDistanceKm = 42.0, totalMeditationMin = 3, totalTalkMin = 1),
            fetchedAtMs = 1_000L,
        )
        val seen = vm.stats.first { it != null }
        assertEquals(17, seen!!.totalWalks)
        assertEquals(42.0, seen.totalDistanceKm, 0.001)
    }

    @Test
    fun `optIn StateFlow proxies the repo's optIn`() = runBlocking {
        assertFalse(vm.optIn.first())
        cacheStore.setOptIn(true)
        assertTrue(vm.optIn.first { it })
    }

    @Test
    fun `voiceCardState reflects voiceGuideEnabled changes`() = runBlocking {
        // Initial state: voice guide disabled (FakeVoicePreferencesRepository default).
        assertEquals(false, vm.voiceCardState.first().voiceGuideEnabled)
        voicePreferences.setVoiceGuideEnabled(true)
        val updated = withTimeout(2_000) {
            vm.voiceCardState.first { it.voiceGuideEnabled }
        }
        assertTrue(updated.voiceGuideEnabled)
    }

    @Test
    fun `voiceCardState reflects autoTranscribe changes`() = runBlocking {
        assertEquals(false, vm.voiceCardState.first().autoTranscribe)
        voicePreferences.setAutoTranscribe(true)
        val updated = withTimeout(2_000) {
            vm.voiceCardState.first { it.autoTranscribe }
        }
        assertTrue(updated.autoTranscribe)
    }

    @Test
    fun `voiceCardState recordings count and size match aggregator output`() = runBlocking {
        val walkId = walkRepository.startWalk(1_000L).id
        val rec1Path = "recordings/${UUID.randomUUID()}/r1.wav"
        val rec2Path = "recordings/${UUID.randomUUID()}/r2.wav"
        seedWavFile(rec1Path, sizeBytes = 256L)
        seedWavFile(rec2Path, sizeBytes = 512L)
        walkRepository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_000L,
                endTimestamp = 2_000L,
                durationMillis = 1_000L,
                fileRelativePath = rec1Path,
            ),
        )
        walkRepository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 2_000L,
                endTimestamp = 3_500L,
                durationMillis = 1_500L,
                fileRelativePath = rec2Path,
            ),
        )
        val populated = withTimeout(2_000) {
            vm.voiceCardState.first { it.recordingsCount == 2 }
        }
        assertEquals(2, populated.recordingsCount)
        assertEquals(768L, populated.recordingsSizeBytes)
    }

    @Test
    fun `setVoiceGuideEnabled writes through to repo`() = runBlocking {
        assertFalse(voicePreferences.voiceGuideEnabled.first())
        vm.setVoiceGuideEnabled(true)
        assertTrue(voicePreferences.voiceGuideEnabled.first { it })
    }

    @Test
    fun `setAutoTranscribe writes through to repo`() = runBlocking {
        assertFalse(voicePreferences.autoTranscribe.first())
        vm.setAutoTranscribe(true)
        assertTrue(voicePreferences.autoTranscribe.first { it })
    }

    @Test
    fun `practiceSummary reads cache cols directly without per-walk interval scan`() = runBlocking {
        // Stage 11-A: VM must aggregate from the Walk row's cache cols
        // (`distance_meters`, `meditation_seconds`) and never call the
        // per-walk activity-interval or route-sample DAO methods. The
        // counting subclass increments on each call; we assert it stays
        // at zero after the practiceSummary populates.
        val first = walkRepository.startWalk(0L)
        walkRepository.updateWalk(
            first.copy(endTimestamp = 100_000L, distanceMeters = 1500.0, meditationSeconds = 300L),
        )
        val second = walkRepository.startWalk(200_000L)
        walkRepository.updateWalk(
            second.copy(endTimestamp = 400_000L, distanceMeters = 2200.0, meditationSeconds = 600L),
        )
        walkRepository.locationSamplesCallCount.set(0)
        walkRepository.activityIntervalsCallCount.set(0)

        val populated = withTimeout(2_000) {
            vm.practiceSummary.first { it.walkCount == 2 }
        }
        assertEquals(2, populated.walkCount)
        assertEquals(3700.0, populated.totalDistanceMeters, 0.001)
        assertEquals(900L, populated.totalMeditationSeconds)
        assertEquals(
            "practiceSummary must not call locationSamplesFor per walk",
            0,
            walkRepository.locationSamplesCallCount.get(),
        )
        assertEquals(
            "practiceSummary must not call activityIntervalsFor per walk",
            0,
            walkRepository.activityIntervalsCallCount.get(),
        )
    }

    @Test
    fun `milestone passes through from detector`() = runBlocking {
        // Initial: detector emits null → VM exposes null.
        assertEquals(null, vm.milestone.first())

        // Detector publishes a milestone → VM's StateFlow mirrors it.
        val expected = CollectiveMilestone.forNumber(108)
        milestoneSurface.set(expected)
        assertEquals(expected, vm.milestone.first { it != null })
    }

    @Test
    fun `onMilestoneShown invokes bell player with scale 0_4`() {
        val milestone = CollectiveMilestone.forNumber(108)
        vm.onMilestoneShown(milestone)
        assertEquals(1, bellPlayer.scaleCalls.size)
        assertEquals(0.4f, bellPlayer.scaleCalls.single(), 0.0001f)
    }

    @Test
    fun `onMilestoneShown stays silent when soundsEnabled is false`() {
        // iOS PracticeSummaryHeader.swift's playMilestoneBell() guards
        // on `UserPreferences.soundsEnabled.value` — Android must
        // mirror to keep master-mute users muted regardless of bellVolume.
        val mutedSoundsPrefs = FakeSoundsPreferencesRepository(initialSoundsEnabled = false)
        val mutedBell = RecordingBellPlayer()
        val mutedVm = SettingsViewModel(
            collectiveRepository = repo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = mutedSoundsPrefs,
            practicePreferences = FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = voicePreferences,
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
            milestoneSurface = FakeMilestoneSurface(),
            bellPlayer = mutedBell,
        )
        mutedVm.onMilestoneShown(CollectiveMilestone.forNumber(108))
        assertEquals(emptyList<Float>(), mutedBell.scaleCalls)
    }

    @Test
    fun `dismissMilestone clears detector`() {
        // Seed a milestone so we can prove `clear()` was the side effect.
        milestoneSurface.set(CollectiveMilestone.forNumber(108))
        assertEquals(0, milestoneSurface.clearCount)
        vm.dismissMilestone()
        assertEquals(1, milestoneSurface.clearCount)
        assertEquals(null, milestoneSurface.milestone.value)
    }

    private fun seedWavFile(relativePath: String, sizeBytes: Long) {
        val target = voiceFs.absolutePath(relativePath)
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            // Write `sizeBytes` zero bytes — `File.length()` only sees the byte
            // count, not the contents, so this is sufficient for the aggregator
            // sumOf assertion. The path matches what `voiceFs.absolutePath`
            // resolves so `fileSizeBytes(relativePath)` returns the same value.
            repeat(sizeBytes.toInt()) { out.write(0) }
        }
    }

    /**
     * Subclasses [WalkRepository] so the practiceSummary test can prove
     * the aggregator does NOT call per-walk DAO reads. Counts are reset
     * after seed inserts run; assertions then verify the steady-state
     * read path stayed off these methods entirely.
     */
    private class CountingWalkRepository(
        database: PilgrimDatabase,
        walkDao: org.walktalkmeditate.pilgrim.data.dao.WalkDao,
        routeDao: org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao,
        altitudeDao: org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao,
        walkEventDao: org.walktalkmeditate.pilgrim.data.dao.WalkEventDao,
        activityIntervalDao: org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao,
        waypointDao: org.walktalkmeditate.pilgrim.data.dao.WaypointDao,
        voiceRecordingDao: org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao,
        walkPhotoDao: org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao,
    ) : WalkRepository(
        database = database,
        walkDao = walkDao,
        routeDao = routeDao,
        altitudeDao = altitudeDao,
        walkEventDao = walkEventDao,
        activityIntervalDao = activityIntervalDao,
        waypointDao = waypointDao,
        voiceRecordingDao = voiceRecordingDao,
        walkPhotoDao = walkPhotoDao,
    ) {
        val locationSamplesCallCount = AtomicInteger(0)
        val activityIntervalsCallCount = AtomicInteger(0)

        override suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> {
            locationSamplesCallCount.incrementAndGet()
            return super.locationSamplesFor(walkId)
        }

        override suspend fun activityIntervalsFor(walkId: Long): List<ActivityInterval> {
            activityIntervalsCallCount.incrementAndGet()
            return super.activityIntervalsFor(walkId)
        }
    }

    private class FakeCounterService(context: Context, json: Json) :
        CollectiveCounterService(
            client = OkHttpClient(),
            json = json,
            deviceTokenStore = DeviceTokenStore(context),
            baseUrl = "http://localhost",
        ) {
        var fetchResult: CollectiveStats = CollectiveStats(0, 0.0, 0, 0)
        var postResult: PostResult = PostResult.Success
        val fetchCount = AtomicInteger(0)

        override suspend fun fetch(): CollectiveStats {
            fetchCount.incrementAndGet()
            return fetchResult
        }

        override suspend fun post(delta: CollectiveCounterDelta): PostResult = postResult
    }

    private object NoopMilestoneChecker : MilestoneChecking {
        override suspend fun check(totalWalks: Int) = Unit
    }

    /**
     * Lightweight fake of the [MilestoneSurface] read-side seam so
     * VM tests can drive the StateFlow directly + count `clear()` calls
     * without subclassing the @Singleton concrete detector.
     */
    private class FakeMilestoneSurface : MilestoneSurface {
        private val _milestone = MutableStateFlow<CollectiveMilestone?>(null)
        override val milestone: StateFlow<CollectiveMilestone?> = _milestone.asStateFlow()
        var clearCount: Int = 0
            private set

        fun set(value: CollectiveMilestone?) {
            _milestone.value = value
        }

        override fun clear() {
            clearCount++
            _milestone.value = null
        }
    }

    /**
     * Records every `play(scale)` invocation. The default no-arg
     * `play()` is unused on the milestone path but kept implementable
     * via the interface default body.
     */
    private class RecordingBellPlayer : BellPlaying {
        val scaleCalls = mutableListOf<Float>()
        override fun play() {
            scaleCalls += 1.0f
        }

        override fun play(scale: Float) {
            scaleCalls += scale
        }
    }
}
