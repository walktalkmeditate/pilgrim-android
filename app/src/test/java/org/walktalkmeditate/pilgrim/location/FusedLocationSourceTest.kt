// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import android.app.Application
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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

    private fun TestScope.collectInBackground(): Pair<MutableList<LocationPoint>, Job> {
        val results = mutableListOf<LocationPoint>()
        val job = launch { source.locationFlow().toList(results) }
        return results to job
    }

    private fun point(accuracy: Float?): Location {
        val location = Location("test").apply {
            latitude = 35.0
            longitude = 139.0
            time = 1_700_000_000_000L
        }
        if (accuracy != null) location.accuracy = accuracy
        return location
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

    override fun register(callback: LocationCallback) {
        callbacks += callback
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
    }
}
