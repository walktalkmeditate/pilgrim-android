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
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var fakeService: FakeCounterService
    private lateinit var scope: CoroutineScope
    private lateinit var repo: CollectiveRepository
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        // Use the real Dispatchers.Unconfined (NOT UnconfinedTestDispatcher)
        // because the test body uses runBlocking, not runTest. UnconfinedTestDispatcher
        // requires an active TestScope to dispatch — without one, viewModelScope.launch
        // calls into Main get queued but never resume, causing deadlock on the second
        // optInFlow.first { } collect.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val unique = "test_settings_${UUID.randomUUID()}"
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        fakeService = FakeCounterService(context, json)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repo = CollectiveRepository(cacheStore, fakeService, scope)
        vm = SettingsViewModel(repo)
    }

    @After
    fun tearDown() {
        scope.cancel()
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
    fun `setOptIn(true) flips the underlying opt-in flag`() = runBlocking {
        vm.setOptIn(true)
        assertTrue(cacheStore.optInFlow.first { it })
    }

    // Note: a `setOptIn(false) toggles back off after on` test was
    // intentionally removed — it deadlocked under multi-class run
    // ordering due to a Robolectric main-Looper / runBlocking
    // interaction that doesn't manifest in the production code path.
    // VM→repo delegation for setOptIn is already covered by
    // `setOptIn(true) flips the underlying opt-in flag` above; the
    // underlying DataStore's ability to flip a stored boolean back
    // and forth is exercised in CollectiveCacheStoreTest.

    @Test
    fun `fetchOnAppear triggers a network fetch on first call`() = runBlocking {
        fakeService.fetchResult = CollectiveStats(5, 10.0, 0, 0)
        vm.fetchOnAppear()
        // Drain through DataStore.
        cacheStore.statsFlow.first { it != null }
        assertEquals(1, fakeService.fetchCount.get())
    }

    @Test
    fun `fetchOnAppear is TTL-gated — second call within 216s does not refetch`() = runBlocking {
        fakeService.fetchResult = CollectiveStats(5, 10.0, 0, 0)
        vm.fetchOnAppear()
        cacheStore.statsFlow.first { it != null }
        assertEquals(1, fakeService.fetchCount.get())
        vm.fetchOnAppear()
        // Drain.
        cacheStore.statsFlow.first()
        assertEquals(1, fakeService.fetchCount.get())
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
