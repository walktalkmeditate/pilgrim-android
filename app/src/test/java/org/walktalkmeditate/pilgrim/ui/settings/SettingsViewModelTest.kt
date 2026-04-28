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
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        repo = CollectiveRepository(cacheStore, fakeService, scope)
        vm = SettingsViewModel(
            repo,
            FakeAppearancePreferencesRepository(),
            FakeSoundsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
        )
    }

    @After
    fun tearDown() {
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
}
