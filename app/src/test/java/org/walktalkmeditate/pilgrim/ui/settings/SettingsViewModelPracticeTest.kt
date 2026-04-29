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
import java.io.IOException
import java.util.UUID
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.appearance.FakeAppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Unit-tests the Stage 10-C Chunk D practice + units passthroughs on
 * [SettingsViewModel]:
 *  - StateFlow proxies for `beginWithIntention`,
 *    `celestialAwarenessEnabled`, `zodiacSystem`,
 *    `walkReliquaryEnabled`, `distanceUnits`.
 *  - Setters delegate to the corresponding repository write.
 *  - Setters swallow disk-IO failures via runCatching, matching the
 *    Stage 9.5-E `setAppearanceMode` precedent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelPracticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var fakeService: FakeCounterService
    private lateinit var scope: CoroutineScope
    private lateinit var collectiveRepo: CollectiveRepository
    private lateinit var db: PilgrimDatabase
    private lateinit var walkRepository: WalkRepository
    private lateinit var voiceFs: VoiceRecordingFileSystem

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val unique = "test_settings_practice_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        fakeService = FakeCounterService(context, json)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectiveRepo = CollectiveRepository(cacheStore, fakeService, scope)
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walkRepository = WalkRepository(
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
        voiceFs = VoiceRecordingFileSystem(context)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    private fun buildVm(
        practiceRepo: PracticePreferencesRepository = FakePracticePreferencesRepository(),
        unitsRepo: UnitsPreferencesRepository = FakeUnitsPreferencesRepository(),
    ) = SettingsViewModel(
        collectiveRepository = collectiveRepo,
        appearancePreferences = FakeAppearancePreferencesRepository(),
        soundsPreferences = FakeSoundsPreferencesRepository(),
        practicePreferences = practiceRepo,
        unitsPreferences = unitsRepo,
        voicePreferences = FakeVoicePreferencesRepository(),
        walkRepository = walkRepository,
        voiceRecordingFileSystem = voiceFs,
    )

    @Test
    fun `beginWithIntention reflects repo value`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialBeginWithIntention = true)
        val vm = buildVm(practiceRepo = practiceRepo)
        assertEquals(true, vm.beginWithIntention.first())
    }

    @Test
    fun `setBeginWithIntention delegates to repo`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialBeginWithIntention = false)
        val vm = buildVm(practiceRepo = practiceRepo)
        vm.setBeginWithIntention(true)
        assertEquals(true, vm.beginWithIntention.first { it })
        assertEquals(true, practiceRepo.beginWithIntention.first())
    }

    @Test
    fun `celestialAwarenessEnabled reflects repo value`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true)
        val vm = buildVm(practiceRepo = practiceRepo)
        assertEquals(true, vm.celestialAwarenessEnabled.first())
    }

    @Test
    fun `setCelestialAwarenessEnabled delegates to repo`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = false)
        val vm = buildVm(practiceRepo = practiceRepo)
        vm.setCelestialAwarenessEnabled(true)
        assertEquals(true, vm.celestialAwarenessEnabled.first { it })
        assertEquals(true, practiceRepo.celestialAwarenessEnabled.first())
    }

    @Test
    fun `zodiacSystem reflects repo value`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialZodiacSystem = ZodiacSystem.Sidereal)
        val vm = buildVm(practiceRepo = practiceRepo)
        assertEquals(ZodiacSystem.Sidereal, vm.zodiacSystem.first())
    }

    @Test
    fun `setZodiacSystem delegates to repo`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository()
        val vm = buildVm(practiceRepo = practiceRepo)
        vm.setZodiacSystem(ZodiacSystem.Sidereal)
        assertEquals(ZodiacSystem.Sidereal, vm.zodiacSystem.first { it == ZodiacSystem.Sidereal })
        assertEquals(ZodiacSystem.Sidereal, practiceRepo.zodiacSystem.first())
    }

    @Test
    fun `walkReliquaryEnabled reflects repo value`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialWalkReliquaryEnabled = true)
        val vm = buildVm(practiceRepo = practiceRepo)
        assertEquals(true, vm.walkReliquaryEnabled.first())
    }

    @Test
    fun `setWalkReliquaryEnabled delegates to repo`() = runBlocking {
        val practiceRepo = FakePracticePreferencesRepository(initialWalkReliquaryEnabled = false)
        val vm = buildVm(practiceRepo = practiceRepo)
        vm.setWalkReliquaryEnabled(true)
        assertEquals(true, vm.walkReliquaryEnabled.first { it })
        assertEquals(true, practiceRepo.walkReliquaryEnabled.first())
    }

    @Test
    fun `distanceUnits reflects units repo value`() = runBlocking {
        val unitsRepo = FakeUnitsPreferencesRepository(initial = UnitSystem.Imperial)
        val vm = buildVm(unitsRepo = unitsRepo)
        assertEquals(UnitSystem.Imperial, vm.distanceUnits.first())
    }

    @Test
    fun `setDistanceUnits delegates to units repo`() = runBlocking {
        val unitsRepo = FakeUnitsPreferencesRepository(initial = UnitSystem.Metric)
        val vm = buildVm(unitsRepo = unitsRepo)
        vm.setDistanceUnits(UnitSystem.Imperial)
        assertEquals(UnitSystem.Imperial, vm.distanceUnits.first { it == UnitSystem.Imperial })
        assertEquals(UnitSystem.Imperial, unitsRepo.distanceUnits.first())
    }

    @Test
    fun `setBeginWithIntention swallows DataStore errors`() = runBlocking {
        val throwingRepo = ThrowingPracticeRepository()
        val vm = buildVm(practiceRepo = throwingRepo)
        vm.setBeginWithIntention(true)
        // Did not crash. Repo's StateFlow stays at the initial value
        // because the write threw before the in-memory mirror updated.
        assertEquals(false, vm.beginWithIntention.first())
        assertEquals(1, throwingRepo.beginWithIntentionAttempts)
    }

    @Test
    fun `setCelestialAwarenessEnabled swallows DataStore errors`() = runBlocking {
        val throwingRepo = ThrowingPracticeRepository()
        val vm = buildVm(practiceRepo = throwingRepo)
        vm.setCelestialAwarenessEnabled(true)
        assertEquals(false, vm.celestialAwarenessEnabled.first())
        assertEquals(1, throwingRepo.celestialAttempts)
    }

    @Test
    fun `setZodiacSystem swallows DataStore errors`() = runBlocking {
        val throwingRepo = ThrowingPracticeRepository()
        val vm = buildVm(practiceRepo = throwingRepo)
        vm.setZodiacSystem(ZodiacSystem.Sidereal)
        assertEquals(ZodiacSystem.Tropical, vm.zodiacSystem.first())
        assertEquals(1, throwingRepo.zodiacAttempts)
    }

    @Test
    fun `setWalkReliquaryEnabled swallows DataStore errors`() = runBlocking {
        val throwingRepo = ThrowingPracticeRepository()
        val vm = buildVm(practiceRepo = throwingRepo)
        vm.setWalkReliquaryEnabled(true)
        assertEquals(false, vm.walkReliquaryEnabled.first())
        assertEquals(1, throwingRepo.reliquaryAttempts)
    }

    @Test
    fun `setDistanceUnits swallows DataStore errors`() = runBlocking {
        val throwingRepo = ThrowingUnitsRepository()
        val vm = buildVm(unitsRepo = throwingRepo)
        vm.setDistanceUnits(UnitSystem.Imperial)
        assertEquals(UnitSystem.Metric, vm.distanceUnits.first())
        assertEquals(1, throwingRepo.attempts)
    }

    private class ThrowingPracticeRepository : PracticePreferencesRepository {
        @Volatile var beginWithIntentionAttempts: Int = 0
        @Volatile var celestialAttempts: Int = 0
        @Volatile var zodiacAttempts: Int = 0
        @Volatile var reliquaryAttempts: Int = 0

        override val beginWithIntention: StateFlow<Boolean> =
            MutableStateFlow(false).asStateFlow()
        override suspend fun setBeginWithIntention(value: Boolean) {
            beginWithIntentionAttempts += 1
            throw IOException("disk full")
        }

        override val celestialAwarenessEnabled: StateFlow<Boolean> =
            MutableStateFlow(false).asStateFlow()
        override suspend fun setCelestialAwarenessEnabled(value: Boolean) {
            celestialAttempts += 1
            throw IOException("disk full")
        }

        override val zodiacSystem: StateFlow<ZodiacSystem> =
            MutableStateFlow(ZodiacSystem.Tropical).asStateFlow()
        override suspend fun setZodiacSystem(value: ZodiacSystem) {
            zodiacAttempts += 1
            throw IOException("disk full")
        }

        override val walkReliquaryEnabled: StateFlow<Boolean> =
            MutableStateFlow(false).asStateFlow()
        override suspend fun setWalkReliquaryEnabled(value: Boolean) {
            reliquaryAttempts += 1
            throw IOException("disk full")
        }
    }

    private class ThrowingUnitsRepository : UnitsPreferencesRepository {
        @Volatile var attempts: Int = 0
        override val distanceUnits: StateFlow<UnitSystem> =
            MutableStateFlow(UnitSystem.Metric).asStateFlow()
        override suspend fun setDistanceUnits(value: UnitSystem) {
            attempts += 1
            throw IOException("disk full")
        }
    }

    private class FakeCounterService(context: Context, json: Json) :
        CollectiveCounterService(
            client = OkHttpClient(),
            json = json,
            deviceTokenStore = DeviceTokenStore(context),
            baseUrl = "http://localhost",
        ) {
        override suspend fun fetch(): CollectiveStats = CollectiveStats(0, 0.0, 0, 0)
        override suspend fun post(delta: CollectiveCounterDelta): PostResult = PostResult.Success
    }
}
