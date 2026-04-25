// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveCacheStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val store = CollectiveCacheStore(context, json)

    @After
    fun cleanup() {
        File(context.filesDir, "datastore/collective_counter.preferences_pb").delete()
    }

    private fun sampleStats() = CollectiveStats(
        totalWalks = 42,
        totalDistanceKm = 12.5,
        totalMeditationMin = 30,
        totalTalkMin = 15,
        lastWalkAt = "2026-04-25T01:00:00Z",
        streakDays = 3,
        streakDate = "2026-04-25",
    )

    @Test
    fun `statsFlow is null before any write`() = runBlocking {
        assertNull(store.statsFlow.first())
        assertNull(store.lastFetchedAtFlow.first())
    }

    @Test
    fun `writeStats round-trips stats and lastFetchedAt`() = runBlocking {
        store.writeStats(sampleStats(), fetchedAtMs = 1_700_000_000_000L)
        assertEquals(sampleStats(), store.statsFlow.first())
        assertEquals(1_700_000_000_000L, store.lastFetchedAtFlow.first())
    }

    @Test
    fun `invalidateLastFetched clears the timestamp but preserves cached stats`() = runBlocking {
        store.writeStats(sampleStats(), fetchedAtMs = 1_700_000_000_000L)
        store.invalidateLastFetched()
        assertNull(store.lastFetchedAtFlow.first())
        assertEquals(sampleStats(), store.statsFlow.first())
    }

    @Test
    fun `optInFlow defaults to false`() = runBlocking {
        assertFalse(store.optInFlow.first())
    }

    @Test
    fun `setOptIn toggles the flag`() = runBlocking {
        store.setOptIn(true)
        assertTrue(store.optInFlow.first())
        store.setOptIn(false)
        assertFalse(store.optInFlow.first())
    }

    @Test
    fun `pendingFlow returns empty by default`() = runBlocking {
        assertTrue(store.pendingFlow.first().isEmpty())
    }

    @Test
    fun `mutatePending merges concurrent contributions`() = runBlocking {
        val first = store.mutatePending {
            it + CollectiveCounterDelta(walks = 1, distanceKm = 2.5, meditationMin = 5, talkMin = 1)
        }
        assertEquals(1, first.walks)
        val second = store.mutatePending {
            it + CollectiveCounterDelta(walks = 1, distanceKm = 1.0, meditationMin = 3, talkMin = 0)
        }
        assertEquals(2, second.walks)
        assertEquals(3.5, second.distanceKm, 0.001)
        assertEquals(8, second.meditationMin)
        assertEquals(1, second.talkMin)
        // pendingFlow reflects accumulated total
        assertEquals(second, store.pendingFlow.first())
    }

    @Test
    fun `mutatePending clears the key when result is empty`() = runBlocking {
        // Seed a non-empty pending value first.
        store.mutatePending { CollectiveCounterDelta(walks = 1, distanceKm = 1.0) }
        assertNotNull(store.pendingFlow.first().takeIf { !it.isEmpty() })
        // Subtract back to empty.
        val cleared = store.mutatePending { CollectiveCounterDelta() }
        assertTrue(cleared.isEmpty())
        assertTrue(store.pendingFlow.first().isEmpty())
    }
}
