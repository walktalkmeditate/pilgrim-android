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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.data.appearance.FakeAppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Unit-tests the appearance-mode passthrough on [SettingsViewModel]:
 *  - `appearanceMode: StateFlow<AppearanceMode> = repo.appearanceMode`
 *  - `fun setAppearanceMode(mode)` → `viewModelScope.launch { repo.setAppearanceMode(mode) }`
 *
 * Mirrors the existing `SettingsViewModelTest` setup (real
 * [CollectiveRepository] + a stub service) since the project does not
 * use `mockk`. The appearance side uses an in-memory
 * [FakeAppearancePreferencesRepository] so we can flip the mode and
 * observe it surfacing through the VM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelAppearanceTest {

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
        val unique = "test_settings_appearance_${UUID.randomUUID()}"
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

    @Test
    fun `appearanceMode reflects repo value`() = runBlocking {
        val appearanceRepo = FakeAppearancePreferencesRepository(initial = AppearanceMode.Dark)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = appearanceRepo,
            soundsPreferences = FakeSoundsPreferencesRepository(),
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = FakeVoicePreferencesRepository(),
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
        )
        assertEquals(AppearanceMode.Dark, vm.appearanceMode.first())
    }

    @Test
    fun `setAppearanceMode delegates to repo`() = runBlocking {
        val appearanceRepo = FakeAppearancePreferencesRepository()
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = appearanceRepo,
            soundsPreferences = FakeSoundsPreferencesRepository(),
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = FakeVoicePreferencesRepository(),
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
        )
        assertEquals(AppearanceMode.System, vm.appearanceMode.first())
        vm.setAppearanceMode(AppearanceMode.Light)
        assertEquals(AppearanceMode.Light, vm.appearanceMode.first { it == AppearanceMode.Light })
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
