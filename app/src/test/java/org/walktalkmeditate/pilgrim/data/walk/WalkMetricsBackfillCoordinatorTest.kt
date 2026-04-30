// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.ui.settings.data.WalksSource

class WalkMetricsBackfillCoordinatorTest {

    @Test
    fun observesAndDrainsStaleWalks() = runTest {
        val cacheCalls = mutableListOf<Long>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) {
                cacheCalls += walkId
            }
        }
        val walks = MutableStateFlow(
            listOf(
                Walk(id = 1, startTimestamp = 0, endTimestamp = 1000, distanceMeters = null),
                Walk(
                    id = 2,
                    startTimestamp = 0,
                    endTimestamp = 2000,
                    distanceMeters = 100.0,
                    meditationSeconds = 0,
                ),
            ),
        )
        val source = FakeWalksSource(walks)
        val coord = WalkMetricsBackfillCoordinator(source, cache, backgroundScope)

        coord.start()
        runCurrent()

        assertEquals(listOf(1L), cacheCalls)
    }

    @Test
    fun dedupsRapidEmissionsForSameId() = runTest {
        val callCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) {
                callCount.incrementAndGet()
                gate.await()
            }
        }
        val walks = MutableStateFlow(
            listOf(Walk(id = 1, startTimestamp = 0, endTimestamp = 1000, distanceMeters = null)),
        )
        val source = FakeWalksSource(walks)
        val coord = WalkMetricsBackfillCoordinator(source, cache, backgroundScope)

        coord.start()
        runCurrent()
        walks.emit(walks.value.toList())
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, callCount.get())
    }

    @Test
    fun skipsInProgressWalks() = runTest {
        val cacheCalls = mutableListOf<Long>()
        val cache = object : WalkMetricsCaching {
            override suspend fun computeAndPersist(walkId: Long) {
                cacheCalls += walkId
            }
        }
        val walks = MutableStateFlow(
            listOf(Walk(id = 1, startTimestamp = 0, endTimestamp = null, distanceMeters = null)),
        )
        val source = FakeWalksSource(walks)
        val coord = WalkMetricsBackfillCoordinator(source, cache, backgroundScope)

        coord.start()
        runCurrent()

        assertTrue(cacheCalls.isEmpty())
    }

    private class FakeWalksSource(private val flow: MutableStateFlow<List<Walk>>) : WalksSource {
        override fun observeAllWalks(): Flow<List<Walk>> = flow
    }
}
