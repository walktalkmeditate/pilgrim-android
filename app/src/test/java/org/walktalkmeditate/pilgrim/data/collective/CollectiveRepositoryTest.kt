// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var fakeService: FakeCounterService
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val unique = "test_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(context.filesDir, "datastore/$unique.preferences_pb") },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        fakeService = FakeCounterService(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        dataStoreScope.cancel()
        File(context.filesDir, "datastore/share_device_token.preferences_pb").delete()
    }

    private fun newRepo() = CollectiveRepository(cacheStore, fakeService, scope)

    private fun sampleStats(walks: Int = 5) = CollectiveStats(
        totalWalks = walks,
        totalDistanceKm = 1.0,
        totalMeditationMin = 0,
        totalTalkMin = 0,
    )

    @Test
    fun `fetchIfStale honors TTL — second call within 216s does not refetch`() = runBlocking {
        fakeService.fetchResult = sampleStats(1)
        val repo = newRepo()
        var nowMs = 1_000_000L
        repo.fetchIfStale { nowMs }
        assertEquals(1, fakeService.fetchCount.get())
        nowMs += 100_000L
        repo.fetchIfStale { nowMs }
        assertEquals(1, fakeService.fetchCount.get())
    }

    @Test
    fun `fetchIfStale refetches after TTL expiry`() = runBlocking {
        fakeService.fetchResult = sampleStats(1)
        val repo = newRepo()
        var nowMs = 1_000_000L
        repo.fetchIfStale { nowMs }
        nowMs += CollectiveConfig.FETCH_TTL.inWholeMilliseconds + 1
        repo.fetchIfStale { nowMs }
        assertEquals(2, fakeService.fetchCount.get())
    }

    @Test
    fun `fetchIfStale persists stats and lastFetchedAt on success`() = runBlocking {
        fakeService.fetchResult = sampleStats(7)
        newRepo().fetchIfStale { 1_700_000_000_000L }
        assertEquals(sampleStats(7), cacheStore.statsFlow.first())
        assertEquals(1_700_000_000_000L, cacheStore.lastFetchedAtFlow.first())
    }

    @Test
    fun `fetchIfStale swallows fetch errors`() = runBlocking {
        fakeService.fetchError = IOException("offline")
        newRepo().fetchIfStale { 0L }
        assertEquals(null, cacheStore.statsFlow.first())
    }

    @Test
    fun `forceFetch invalidates TTL and refetches even within window`() = runBlocking {
        fakeService.fetchResult = sampleStats(1)
        val repo = newRepo()
        var nowMs = 1_000_000L
        repo.fetchIfStale { nowMs }
        assertEquals(1, fakeService.fetchCount.get())
        nowMs += 10_000L
        repo.forceFetch { nowMs }
        assertEquals(2, fakeService.fetchCount.get())
    }

    @Test
    fun `recordWalk no-op when opt-in OFF`() = runBlocking {
        cacheStore.setOptIn(false)
        val repo = newRepo()
        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 1.0, meditationMin = 0, talkMin = 0))
        // Drain any in-flight launched body via a DataStore read.
        cacheStore.pendingFlow.first()
        assertEquals(0, fakeService.postCount.get())
        assertTrue(cacheStore.pendingFlow.first().isEmpty())
    }

    @Test
    fun `recordWalk opt-in ON accumulates pending and POSTs merged total`() = runBlocking {
        cacheStore.setOptIn(true)
        fakeService.postResult = PostResult.Success
        fakeService.fetchResult = sampleStats()
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 2.0, meditationMin = 5, talkMin = 1))
        awaitPostCount(1)

        val posted = fakeService.lastPosted!!
        assertEquals(1, posted.walks)
        assertEquals(2.0, posted.distanceKm, 0.001)
        assertEquals(5, posted.meditationMin)
        assertEquals(1, posted.talkMin)
    }

    @Test
    fun `recordWalk on Success clears pending and forces fetch`() = runBlocking {
        cacheStore.setOptIn(true)
        fakeService.postResult = PostResult.Success
        fakeService.fetchResult = sampleStats(99)
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 1.0, meditationMin = 1, talkMin = 0))
        awaitPostCount(1)
        // Bounded poll instead of `flow.first { predicate }` — the latter
        // hangs in multi-class run ordering due to a Robolectric main-Looper
        // / runBlocking interaction with DataStore actor resumes that
        // doesn't manifest standalone. Poll covers both the success
        // path AND surfaces a useful diagnostic if it ever times out.
        val deadline = System.currentTimeMillis() + 2_000L
        while ((!cacheStore.pendingFlow.first().isEmpty() ||
                cacheStore.statsFlow.first() != sampleStats(99)) &&
            System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.yield()
        }
        assertTrue(
            "pending should be empty after Success",
            cacheStore.pendingFlow.first().isEmpty(),
        )
        assertEquals(sampleStats(99), cacheStore.statsFlow.first())
    }

    @Test
    fun `recordWalk on RateLimited PRESERVES pending`() = runBlocking {
        cacheStore.setOptIn(true)
        fakeService.postResult = PostResult.RateLimited
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 1.5, meditationMin = 2, talkMin = 0))
        awaitPostCount(1)

        val pending = cacheStore.pendingFlow.first()
        assertEquals(1, pending.walks)
        assertEquals(1.5, pending.distanceKm, 0.001)
        assertEquals(2, pending.meditationMin)
    }

    @Test
    fun `recordWalk on Failed PRESERVES pending`() = runBlocking {
        cacheStore.setOptIn(true)
        fakeService.postResult = PostResult.Failed(IOException("boom"))
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 0.5, meditationMin = 0, talkMin = 1))
        awaitPostCount(1)

        val pending = cacheStore.pendingFlow.first()
        assertEquals(1, pending.walks)
        assertEquals(0.5, pending.distanceKm, 0.001)
        assertEquals(1, pending.talkMin)
    }

    @Test
    fun `recordWalk drains accumulated pending across failure then success`() = runBlocking {
        cacheStore.setOptIn(true)
        fakeService.postResult = PostResult.Failed(IOException("first call boom"))
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 1.0, meditationMin = 1, talkMin = 0))
        awaitPostCount(1)
        assertEquals(1, cacheStore.pendingFlow.first().walks)

        fakeService.postResult = PostResult.Success
        fakeService.fetchResult = sampleStats(2)
        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 2.0, meditationMin = 3, talkMin = 1))
        awaitPostCount(2)

        val posted = fakeService.lastPosted!!
        assertEquals(2, posted.walks)
        assertEquals(3.0, posted.distanceKm, 0.001)
        assertEquals(4, posted.meditationMin)
        assertEquals(1, posted.talkMin)
        awaitPending { it.isEmpty() }
        Unit
    }

    @Test
    fun `recordWalk preserves residual distance after walks-clamp drains walks to zero`() = runBlocking {
        // Closing-review bug: iOS predicate `subtracted.walks <= 0`
        // silently dropped distance/meditation/talk residuals when
        // walks clamp drained all 9 walks but distance overflow
        // (270km → clamp 200km → leaves 70km) was non-zero.
        cacheStore.setOptIn(true)
        cacheStore.mutatePending {
            CollectiveCounterDelta(
                walks = 9,
                distanceKm = 270.0,
                meditationMin = 0,
                talkMin = 0,
            )
        }
        fakeService.postResult = PostResult.Success
        fakeService.fetchResult = sampleStats(99)
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 0.0, meditationMin = 0, talkMin = 0))
        awaitPostCount(1)

        // POST clamped to walks=10 (9 + 1 new = 10, at cap), distance=200 (270 capped).
        assertEquals(10, fakeService.lastPosted!!.walks)
        assertEquals(200.0, fakeService.lastPosted!!.distanceKm, 0.001)
        // After Success: walks fully drained, but 70km residual must stay in pending.
        // Filter for post-subtract specifically (walks==0) so we don't
        // race the pre-subtract merge emission (walks=10, dist=270).
        val residual = awaitPending { it.walks == 0 }
        assertEquals(70.0, residual.distanceKm, 0.001)
    }

    @Test
    fun `recordWalk clamps oversize pending to backend caps before POST`() = runBlocking {
        // Reviewer Bug #3: backend silently clamps walks > 10 + returns OK.
        // If we subtract the unclamped value from pending, the residual
        // disappears. Seed pending past the cap and verify (a) POST
        // payload is clamped, (b) overflow stays in pending after Success.
        cacheStore.setOptIn(true)
        // Pre-load pending past the cap (12 walks, well over MAX_WALKS_PER_POST=10).
        cacheStore.mutatePending {
            CollectiveCounterDelta(
                walks = 11,
                distanceKm = 5.0,
                meditationMin = 0,
                talkMin = 0,
            )
        }
        fakeService.postResult = PostResult.Success
        fakeService.fetchResult = sampleStats(99)
        val repo = newRepo()
        repo.optIn.first { it }

        repo.recordWalk(CollectiveWalkSnapshot(distanceKm = 1.0, meditationMin = 0, talkMin = 0))
        awaitPostCount(1)

        // POST payload was clamped to MAX_WALKS_PER_POST (10).
        assertEquals(10, fakeService.lastPosted!!.walks)
        // After Success: pending = (12 - 10) = 2 walks remaining for the next POST.
        awaitPending { it.walks == 2 }
        Unit
    }

    /**
     * Bounded poll on pendingFlow until the predicate matches. Used
     * instead of `flow.first { predicate }` because the latter
     * deadlocks under multi-class run ordering — Robolectric main
     * Looper x runBlocking x DataStore-actor-resume issue. Returns
     * the matching value; throws AssertionError on timeout for
     * useful diagnostics.
     */
    private suspend fun awaitPending(predicate: (CollectiveCounterDelta) -> Boolean): CollectiveCounterDelta {
        val deadline = System.currentTimeMillis() + 2_000L
        var last: CollectiveCounterDelta = cacheStore.pendingFlow.first()
        while (!predicate(last) && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.yield()
            last = cacheStore.pendingFlow.first()
        }
        if (!predicate(last)) {
            throw AssertionError("awaitPending predicate didn't match within 2s; last=$last")
        }
        return last
    }

    private suspend fun awaitPostCount(expected: Int) {
        // recordWalk launches on Unconfined; the body suspends through
        // DataStore's IO actor before reaching service.post(). Poll
        // briefly with yields so we don't race the assertion.
        val deadline = System.currentTimeMillis() + 2_000L
        while (fakeService.postCount.get() < expected &&
            System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.yield()
            // Also drain DataStore via a flow read so any pending edit
            // settles before the next yield.
            cacheStore.pendingFlow.first()
        }
        assertEquals(expected, fakeService.postCount.get())
    }

    private class FakeCounterService(context: android.content.Context) :
        CollectiveCounterService(
            client = OkHttpClient(),
            json = Json,
            deviceTokenStore = DeviceTokenStore(context),
            baseUrl = "http://localhost",
        ) {
        var fetchResult: CollectiveStats? = null
        var fetchError: Throwable? = null
        var postResult: PostResult = PostResult.Success
        val fetchCount = AtomicInteger(0)
        val postCount = AtomicInteger(0)
        var lastPosted: CollectiveCounterDelta? = null

        override suspend fun fetch(): CollectiveStats {
            fetchCount.incrementAndGet()
            fetchError?.let { throw it }
            return fetchResult ?: error("fetchResult not set")
        }

        override suspend fun post(delta: CollectiveCounterDelta): PostResult {
            postCount.incrementAndGet()
            lastPosted = delta
            return postResult
        }
    }
}
