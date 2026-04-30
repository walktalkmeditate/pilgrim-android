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
import java.io.IOException
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
import org.walktalkmeditate.pilgrim.data.collective.MilestoneChecking
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Unit-tests the soundsEnabled passthrough on [SettingsViewModel]:
 *  - `soundsEnabled: StateFlow<Boolean> = repo.soundsEnabled`
 *  - `fun setSoundsEnabled(value)` → `viewModelScope.launch { repo.setSoundsEnabled(value) }`
 *
 * Mirrors `SettingsViewModelAppearanceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelSoundsTest {

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
        val unique = "test_settings_sounds_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        fakeService = FakeCounterService(context, json)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectiveRepo = CollectiveRepository(cacheStore, fakeService, scope, NoopMilestoneChecker)
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
    fun `soundsEnabled flow reflects repo value`() = runBlocking {
        val soundsRepo = FakeSoundsPreferencesRepository(initialSoundsEnabled = false)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = soundsRepo,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = FakeVoicePreferencesRepository(),
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
        )
        assertEquals(false, vm.soundsEnabled.first())
    }

    @Test
    fun `setSoundsEnabled delegates to repo`() = runBlocking {
        val soundsRepo = FakeSoundsPreferencesRepository(initialSoundsEnabled = true)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = soundsRepo,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = FakeVoicePreferencesRepository(),
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
        )
        assertEquals(true, vm.soundsEnabled.first())
        vm.setSoundsEnabled(false)
        assertEquals(false, vm.soundsEnabled.first { it == false })
        // Confirm the write reached the repo, not just the VM's StateFlow.
        assertEquals(false, soundsRepo.soundsEnabled.first())
    }

    @Test
    fun `setSoundsEnabled swallows DataStore errors via runCatching`() = runBlocking {
        val throwingRepo = ThrowingSoundsPreferencesRepository(initial = true)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = throwingRepo,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            voicePreferences = FakeVoicePreferencesRepository(),
            walkRepository = walkRepository,
            voiceRecordingFileSystem = voiceFs,
        )
        // Calling the setter must NOT throw — runCatching inside the
        // VM swallows the IOException and logs it.
        vm.setSoundsEnabled(false)
        // Repo's setSoundsEnabled threw, so its underlying flow stayed
        // at the initial value. The VM is still functional — its
        // soundsEnabled flow continues to reflect the repo's source of
        // truth and is queryable.
        assertEquals(true, vm.soundsEnabled.first())
        assertEquals(1, throwingRepo.attemptCount)
    }

    private class ThrowingSoundsPreferencesRepository(
        initial: Boolean = true,
    ) : SoundsPreferencesRepository {
        private val _soundsEnabled = MutableStateFlow(initial)
        override val soundsEnabled: StateFlow<Boolean> = _soundsEnabled.asStateFlow()
        @Volatile var attemptCount: Int = 0
        override suspend fun setSoundsEnabled(value: Boolean) {
            attemptCount += 1
            throw IOException("disk full")
        }

        // Sibling prefs are not exercised in these tests — return
        // inert default-backed StateFlows; setters are no-ops.
        override val bellHapticEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override suspend fun setBellHapticEnabled(value: Boolean) = Unit
        override val bellVolume: StateFlow<Float> = MutableStateFlow(0.7f).asStateFlow()
        override suspend fun setBellVolume(value: Float) = Unit
        override val soundscapeVolume: StateFlow<Float> = MutableStateFlow(0.4f).asStateFlow()
        override suspend fun setSoundscapeVolume(value: Float) = Unit
        override val walkStartBellId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun setWalkStartBellId(value: String?) = Unit
        override val walkEndBellId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun setWalkEndBellId(value: String?) = Unit
        override val meditationStartBellId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun setMeditationStartBellId(value: String?) = Unit
        override val meditationEndBellId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun setMeditationEndBellId(value: String?) = Unit
        override val breathRhythm: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
        override suspend fun setBreathRhythm(value: Int) = Unit
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

    private object NoopMilestoneChecker : MilestoneChecking {
        override suspend fun check(totalWalks: Int) = Unit
    }
}
