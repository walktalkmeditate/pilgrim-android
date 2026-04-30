// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveMilestoneDetectorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var cacheStore: CollectiveCacheStore
    private lateinit var detector: CollectiveMilestoneDetector

    @Before
    fun setUp() {
        val uniqueName = "milestone_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { java.io.File(context.filesDir, "datastore/$uniqueName.preferences_pb") },
        )
        cacheStore = CollectiveCacheStore(dataStore, json)
        detector = CollectiveMilestoneDetector(cacheStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun firstSacredNumberCrossingEmits() = runTest {
        detector.milestone.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            detector.check(108)
            assertEquals(108, awaitItem()?.number)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun alreadySeenNumberDoesNotEmit() = runTest {
        cacheStore.setLastSeenCollectiveWalks(108)
        detector.check(200)
        assertNull(detector.milestone.value)
    }

    @Test
    fun multipleNumbersCrossedEmitsLowestFirst() = runTest {
        detector.check(2500)
        assertEquals(108, detector.milestone.value?.number)
        assertEquals(108, cacheStore.firstReadyLastSeenCollectiveWalks())
        detector.clear()
        detector.check(2500)
        assertEquals(1080, detector.milestone.value?.number)
    }

    @Test
    fun checkUsesSuspendingFirstReadyAvoidsColdStartRace() = runTest {
        cacheStore.setLastSeenCollectiveWalks(108)
        val freshDetector = CollectiveMilestoneDetector(cacheStore)
        freshDetector.check(108)
        assertNull(freshDetector.milestone.value)
    }

    @Test
    fun setLastSeenThrowingDoesNotPropagateToCheck() = runTest {
        val throwingStore = object : MilestoneStorage {
            override suspend fun firstReadyLastSeenCollectiveWalks(): Int = 0
            override suspend fun setLastSeenCollectiveWalks(value: Int) {
                error("boom")
            }
        }
        val throwingDetector = CollectiveMilestoneDetector(throwingStore)
        // Must not throw — log + swallow expected.
        throwingDetector.check(108)
        // Order is publish-in-memory-then-persist (mirrors iOS, prevents
        // process-kill silent miss). So when the persist throws, the
        // in-memory milestone IS published — the user still sees the
        // celebration. The next launch will re-detect (lastSeen unchanged
        // on disk), surfacing it again — at-least-once is the correct
        // bias here, vs at-most-once = silent miss.
        assertNotNull(throwingDetector.milestone.value)
        assertEquals(108, throwingDetector.milestone.value?.number)
    }

    @Test
    fun clearResetsMilestoneToNull() = runTest {
        detector.check(108)
        assertNotNull(detector.milestone.value)
        detector.clear()
        assertNull(detector.milestone.value)
    }

    @Test
    fun checkWithZeroTotalWalksDoesNotEmit() = runTest {
        // `totalWalks >= number` for any sacred number > 0 is false
        // when totalWalks == 0, so the loop never matches. Guards
        // against a future predicate flip from `>=` to `>` (or
        // `lastSeen < number` to `<=`) accidentally emitting on a
        // pre-launch backend response.
        detector.check(0)
        assertNull(detector.milestone.value)
    }

    @Test
    fun checkWithNegativeTotalWalksDoesNotEmit() = runTest {
        // Defensive: backend should never return a negative count,
        // but if a future protocol change does, the detector must
        // not emit a milestone for it.
        detector.check(-1)
        assertNull(detector.milestone.value)
    }
}
