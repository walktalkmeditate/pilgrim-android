// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import org.robolectric.shadows.ShadowLog
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Validates the iOS-faithful horizontal-accuracy gate added in Stage 12-B.
 *
 * The gate runs at the [FusedLocationSource] callback boundary, before
 * any downstream consumer (`WalkController` reducer / distance summer) sees
 * a `LocationPoint`. The `hasEmitted` anchor lives inside the per-collection
 * `callbackFlow` body — this proves a singleton-scoped source resets the
 * anchor between walks (see [newCollectionResetsAnchor]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FusedLocationSourceTest {

    private lateinit var context: Application
    private lateinit var binder: FakeLocationCallbackBinder
    private lateinit var source: FusedLocationSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        binder = FakeLocationCallbackBinder()
        source = FusedLocationSource(context, binder)
    }

    @After
    fun tearDown() {
        binder.reset()
    }

    @Test
    fun firstSampleAcceptedRegardlessOfAccuracy() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 200f))
        assertEquals(1, results.size)
        assertEquals(200f, results[0].horizontalAccuracyMeters)
        job.cancel()
    }

    @Test
    fun rejectedAccuracyDroppedSilently() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 5f))   // anchor
        binder.fire(point(accuracy = 120f)) // > hard ceiling → reject
        assertEquals(1, results.size)
        assertEquals(5f, results[0].horizontalAccuracyMeters)
        job.cancel()
    }

    @Test
    fun acceptableAccuracyPasses() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 5f))   // anchor
        binder.fire(point(accuracy = 15f))  // within 20m bound
        assertEquals(2, results.size)
        assertEquals(15f, results[1].horizontalAccuracyMeters)
        job.cancel()
    }

    @Test
    fun borderlineEqualsDesiredAccuracyPasses() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 5f))    // anchor
        binder.fire(point(accuracy = 20f))   // == DESIRED_ACCURACY_METERS → passes (`<=`)
        assertEquals(2, results.size)
        job.cancel()
    }

    @Test
    fun borderlineEqualsHardCeilingFails() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 5f))     // anchor
        binder.fire(point(accuracy = 100f))   // == HARD_CEILING_METERS → rejected (`<`)
        assertEquals(1, results.size)
        job.cancel()
    }

    @Test
    fun nullAccuracyRejectedExceptForFirstSample() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = null))  // first sample → anchored even with null
        binder.fire(point(accuracy = null))  // subsequent → defensive REJECT
        assertEquals(1, results.size)
        assertTrue(results[0].horizontalAccuracyMeters == null)
        job.cancel()
    }

    @Test
    fun firstSampleAnchorsEvenWhenAccuracyNull() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = null))
        assertEquals(1, results.size)
        assertTrue(results[0].horizontalAccuracyMeters == null)
        job.cancel()
    }

    @Test
    fun bearingCarriedWhenPresentAndNullWhenAbsent() = runTest(UnconfinedTestDispatcher()) {
        val (results, job) = collectInBackground()
        binder.fire(point(accuracy = 5f))
        binder.fire(point(accuracy = 5f, bearing = 42.5f))
        assertEquals(2, results.size)
        assertTrue(results[0].bearingDegrees == null)
        assertEquals(42.5f, results[1].bearingDegrees)
        job.cancel()
    }

    @Test
    fun newCollectionResetsAnchor() = runTest(UnconfinedTestDispatcher()) {
        val first = mutableListOf<LocationPoint>()
        val firstJob = launch { source.locationFlow().toList(first) }
        binder.fire(point(accuracy = 200f)) // anchor for collection #1
        assertEquals(1, first.size)
        firstJob.cancel()
        // After cancellation, FLP unregisters the previous callback.
        assertEquals(0, binder.activeCallbackCount)

        // Singleton source — but the AtomicBoolean lives inside the
        // per-collection `callbackFlow` block, so the second walk must
        // anchor again rather than reject the bad-accuracy first sample.
        val second = mutableListOf<LocationPoint>()
        val secondJob = launch { source.locationFlow().toList(second) }
        binder.fire(point(accuracy = 200f)) // anchor for collection #2
        assertEquals(1, second.size)
        secondJob.cancel()
    }

    // ─── Teardown race (U8 audit C, iOS CombineExt DemandBuffer patch) ──

    @Test
    fun lateCallbackAfterCancellationNeitherThrowsNorEmits() = runTest(UnconfinedTestDispatcher()) {
        assertLateCallbackDropped { source.locationFlow() }
    }

    @Test
    fun rawLateCallbackAfterCancellationNeitherThrowsNorEmits() = runTest(UnconfinedTestDispatcher()) {
        assertLateCallbackDropped { source.rawLocationFlow() }
    }

    /**
     * The seek topology: a single-threaded Default collector, while FLP
     * delivers from another thread. Each round cancels the collector
     * partway through a flood of callbacks; the worker keeps delivering
     * until the cancel has completed, then sends a fixed batch of late
     * callbacks into the closed flow.
     */
    @Test
    fun cancellingMidFloodFromAnotherThreadNeverThrowsOrEmitsAfterCancel() = runBlocking {
        val collectorDispatcher = Dispatchers.Default.limitedParallelism(1)
        val cancelDelays = Random(seed = 21)
        var lateDeliveries = 0
        repeat(FLOOD_ROUNDS) { round ->
            val received = AtomicInteger(0)
            val previousCallback = binder.lastRegistered
            val job = launch(collectorDispatcher) {
                val flow = if (round % 2 == 0) source.rawLocationFlow() else source.locationFlow()
                flow.collect { received.incrementAndGet() }
            }
            val callback = awaitNewRegistration(previousCallback)

            val workerFailure = AtomicReference<Throwable?>(null)
            val floodStarted = CountDownLatch(1)
            val collectorCancelled = AtomicBoolean(false)
            val lateInRound = AtomicInteger(0)
            val sample = LocationResult.create(listOf(point(accuracy = 5f)))
            val worker = thread(name = "flp-flood-$round") {
                floodStarted.countDown()
                try {
                    var delivered = 0
                    while (
                        lateInRound.get() < LATE_CALLBACKS_PER_ROUND &&
                        delivered < MAX_CALLBACKS_PER_ROUND
                    ) {
                        val afterCancel = collectorCancelled.get()
                        callback.onLocationResult(sample)
                        delivered++
                        if (afterCancel) lateInRound.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    workerFailure.set(t)
                }
            }
            assertTrue(floodStarted.await(FLOOD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            LockSupport.parkNanos(cancelDelays.nextLong(50_000L, 500_001L))

            job.cancelAndJoin()
            val receivedAtCancel = received.get()
            collectorCancelled.set(true)
            worker.join(TimeUnit.SECONDS.toMillis(FLOOD_TIMEOUT_SECONDS))

            assertFalse("round $round: flood worker never finished", worker.isAlive)
            assertNull("round $round: a delivered callback threw", workerFailure.get())
            assertEquals("round $round: emitted after cancel", receivedAtCancel, received.get())
            assertEquals("round $round: callback still registered", 0, binder.activeCallbackCount)
            lateDeliveries += lateInRound.get()
            ShadowLog.clear()
        }
        assertTrue("no callback was ever delivered after cancellation", lateDeliveries > 0)
    }

    private fun TestScope.assertLateCallbackDropped(flow: () -> Flow<LocationPoint>) {
        val results = mutableListOf<LocationPoint>()
        val job = launch { flow().toList(results) }
        binder.fire(point(accuracy = 5f))
        assertEquals(1, results.size)
        job.cancel()
        assertEquals(0, binder.activeCallbackCount)

        val late = checkNotNull(binder.lastRegistered)
        late.onLocationResult(LocationResult.create(listOf(point(accuracy = 5f))))

        assertEquals("a sample delivered after cancellation is dropped", 1, results.size)
    }

    private fun awaitNewRegistration(previous: LocationCallback?): LocationCallback {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FLOOD_TIMEOUT_SECONDS)
        while (true) {
            val current = binder.lastRegistered
            if (current != null && current !== previous) return current
            check(System.nanoTime() < deadline) { "the collector never registered a callback" }
            Thread.sleep(1L)
        }
    }

    private fun TestScope.collectInBackground(): Pair<MutableList<LocationPoint>, Job> {
        val results = mutableListOf<LocationPoint>()
        val job = launch { source.locationFlow().toList(results) }
        return results to job
    }

    private fun point(accuracy: Float?, bearing: Float? = null): Location {
        val location = Location("test").apply {
            latitude = 35.0
            longitude = 139.0
            time = 1_700_000_000_000L
        }
        if (accuracy != null) location.accuracy = accuracy
        if (bearing != null) location.bearing = bearing
        return location
    }

    private companion object {
        // Modest on purpose: every callback logs, and ShadowLog keeps each
        // line in memory (it is also cleared after every round).
        const val FLOOD_ROUNDS = 20
        const val MAX_CALLBACKS_PER_ROUND = 2_000
        const val LATE_CALLBACKS_PER_ROUND = 200
        const val FLOOD_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Test fake that captures registered callbacks so tests can synchronously
 * deliver `LocationResult`s without touching Google Play Services. Mirrors
 * the [LocationCallbackBinder] contract: `register` returns a removal
 * handle, `unregister` decrements the active count.
 */
private class FakeLocationCallbackBinder : LocationCallbackBinder {
    private val callbacks = mutableListOf<LocationCallback>()

    val activeCallbackCount: Int get() = callbacks.size

    /**
     * The most recently registered callback, kept after unregister: FLP's
     * `removeLocationUpdates` is asynchronous, so a callback already queued
     * can still run after the flow is torn down.
     */
    @Volatile var lastRegistered: LocationCallback? = null
        private set

    override fun register(callback: LocationCallback) {
        callbacks += callback
        lastRegistered = callback
    }

    override fun unregister(callback: LocationCallback) {
        callbacks -= callback
    }

    fun fire(location: Location) {
        val result = LocationResult.create(listOf(location))
        // Snapshot to defend against unregister-during-iteration.
        callbacks.toList().forEach { it.onLocationResult(result) }
    }

    fun reset() {
        callbacks.clear()
        lastRegistered = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultLocationCallbackBinderTest {

    /**
     * Regression: the seek engine collects the raw location flow on a
     * single-threaded Default dispatcher with no Looper. A null looper
     * in requestLocationUpdates means "calling thread's looper" and
     * threw `invalid null looper` on-device the first time the
     * pre-departure boot registered from that scope.
     */
    @Test
    fun registerFromLooperlessThreadDoesNotThrow() {
        val binder = DefaultLocationCallbackBinder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        val callback = object : com.google.android.gms.location.LocationCallback() {}
        var thrown: Throwable? = null
        val worker = Thread {
            try {
                binder.register(callback)
            } catch (t: Throwable) {
                thrown = t
            } finally {
                binder.unregister(callback)
            }
        }
        worker.start()
        worker.join(10_000)
        org.junit.Assert.assertNull("register must not require a Looper on the calling thread", thrown)
    }
}
