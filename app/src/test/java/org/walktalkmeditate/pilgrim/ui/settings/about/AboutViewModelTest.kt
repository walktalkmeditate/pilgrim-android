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
    fun `multiple walks aggregate from cache cols`() = runTest {
        val walks = listOf(
            walk(id = 1, start = 1_000, distanceMeters = 1500.0),
            walk(id = 2, start = 5_000, distanceMeters = 2200.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertEquals(3700.0, current.totalDistanceMeters, 0.001)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            assertTrue(current.hasWalks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unfinished walks are excluded from stats`() = runTest {
        val walks = listOf(
            Walk(id = 1, startTimestamp = 1_000, endTimestamp = 2_000, distanceMeters = 500.0),
            Walk(id = 2, startTimestamp = 5_000, endTimestamp = null, distanceMeters = 999.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 1) current = awaitItem()
            assertEquals(1, current.walkCount)
            assertEquals(500.0, current.totalDistanceMeters, 0.001)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `null cache cols sum to zero (no per-walk scan)`() = runTest {
        // The AboutWalkSource seam no longer exposes per-walk readers,
        // so a regression that re-introduces the N+1 scan would fail to
        // compile. This test guards the value semantics: a `null`
        // distance cache col contributes 0 to the running sum without
        // any fallback recomputation.
        val walks = listOf(
            Walk(id = 1, startTimestamp = 1_000, endTimestamp = 2_000, distanceMeters = null),
            Walk(id = 2, startTimestamp = 5_000, endTimestamp = 6_000, distanceMeters = 1234.0),
        )
        val source = FakeWalkSource(flowOf(walks))
        val vm = AboutViewModel(source, FakeUnits())

        vm.stats.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertEquals(1234.0, current.totalDistanceMeters, 0.001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun walk(id: Long, start: Long, distanceMeters: Double) = Walk(
        id = id,
        startTimestamp = start,
        endTimestamp = start + 60_000,
        distanceMeters = distanceMeters,
    )
}

private class FakeWalkSource(
    private val flow: Flow<List<Walk>>,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = flow
}

private class FakeUnits : UnitsPreferencesRepository {
    private val _distanceUnits = MutableStateFlow(UnitSystem.Metric)
    override val distanceUnits = _distanceUnits
    override suspend fun setDistanceUnits(value: UnitSystem) {
        _distanceUnits.value = value
    }
}
