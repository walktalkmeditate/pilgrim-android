// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
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
import org.walktalkmeditate.pilgrim.data.appearance.FakeAppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

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
        collectiveRepo = CollectiveRepository(cacheStore, fakeService, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `soundsEnabled flow reflects repo value`() = runBlocking {
        val soundsRepo = FakeSoundsPreferencesRepository(initial = false)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = soundsRepo,
        )
        assertEquals(false, vm.soundsEnabled.first())
    }

    @Test
    fun `setSoundsEnabled delegates to repo`() = runBlocking {
        val soundsRepo = FakeSoundsPreferencesRepository(initial = true)
        val vm = SettingsViewModel(
            collectiveRepository = collectiveRepo,
            appearancePreferences = FakeAppearancePreferencesRepository(),
            soundsPreferences = soundsRepo,
        )
        assertEquals(true, vm.soundsEnabled.first())
        vm.setSoundsEnabled(false)
        assertEquals(false, vm.soundsEnabled.first { it == false })
        // Confirm the write reached the repo, not just the VM's StateFlow.
        assertEquals(false, soundsRepo.soundsEnabled.first())
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
