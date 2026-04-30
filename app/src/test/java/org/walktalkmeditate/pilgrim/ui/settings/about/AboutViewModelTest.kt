// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import app.cash.turbine.test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

class AboutViewModelTest {

    @Test
    fun `no walks yields hasWalks=false`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.hasWalks) current = awaitItem()
            assertFalse(current.hasWalks)
            assertEquals(0, current.walkCount)
            assertEquals(0.0, current.totalDistanceMeters, 0.0)
            assertNull(current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple walks aggregate correctly`() = runTest {
        val walks = listOf(walk(id = 1, start = 1_000), walk(id = 2, start = 5_000))
        val perWalkSamples = mapOf(
            1L to listOf(sample(1_000, 0.0, 0.0), sample(2_000, 1.0, 0.0)),
            2L to listOf(sample(5_000, 0.0, 0.0), sample(6_000, 0.5, 0.0)),
        )
        val source = FakeWalkSource(flowOf(walks), perWalkSamples)
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertTrue(
                "expected ~166500m, got ${current.totalDistanceMeters}",
                kotlin.math.abs(current.totalDistanceMeters - 166_500.0) < 1_000.0,
            )
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            assertTrue(current.hasWalks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unfinished walks are excluded from stats`() = runTest {
        val walks = listOf(
            Walk(id = 1, startTimestamp = 1_000, endTimestamp = 2_000),
            Walk(id = 2, startTimestamp = 5_000, endTimestamp = null),
        )
        val source = FakeWalkSource(flowOf(walks), mapOf(1L to emptyList()))
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 1) current = awaitItem()
            assertEquals(1, current.walkCount)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun walk(id: Long, start: Long) = Walk(
        id = id, startTimestamp = start, endTimestamp = start + 60_000,
    )

    private fun sample(timestamp: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = timestamp, latitude = lat, longitude = lng,
    )
}

private class FakeWalkSource(
    private val flow: Flow<List<Walk>>,
    private val samplesByWalkId: Map<Long, List<RouteDataSample>> = emptyMap(),
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = flow
    override suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> =
        samplesByWalkId[walkId] ?: emptyList()
}

private class FakeUnits : UnitsPreferencesRepository {
    private val _distanceUnits = MutableStateFlow(UnitSystem.Metric)
    override val distanceUnits = _distanceUnits
    override suspend fun setDistanceUnits(value: UnitSystem) {
        _distanceUnits.value = value
    }
}
