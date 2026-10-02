// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint

/**
 * [WayReplayer] against a fake at the mock-location seam, on virtual time,
 * plus the one real platform object it builds ([MockFix.toLocation]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayReplayerTimelineTest {

    @Test
    fun `fixes play at the recorded gaps, shifted to now`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        val startedAt = testScheduler.currentTime

        replayer.start(WALK_ID, listOf(0.0, 3.0, 3.5, 603.5).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(listOf(0L, 3_000L, 3_500L, 603_500L), client.fixes().map { it.playedAtMillis - startedAt })
    }

    @Test
    fun `each fix is stamped with the clocks at the moment it plays`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(WALK_ID, listOf(0.0, 3.0, 603.5).map { point(t = it) })
        advanceUntilIdle()

        val stamps = client.fixes().map { it.fix.timeMillis to it.fix.elapsedRealtimeNanos }
        val expected = client.fixes().map { WALL_START + it.playedAtMillis to it.playedAtMillis * 1_000_000 }
        assertEquals(expected, stamps)
    }

    @Test
    fun `a first point whose time is not zero still plays at once`() {
        val steps = WayReplayTimeline.steps(listOf(point(t = 12.0), point(t = 15.0)))

        assertEquals(listOf(0L, 3_000L), steps.map { it.offsetMillis })
    }

    @Test
    fun `a time that runs backwards never plays a fix early`() {
        val steps = WayReplayTimeline.steps(listOf(point(t = 0.0), point(t = 5.0), point(t = 4.0)))

        assertEquals(listOf(0L, 5_000L, 5_000L), steps.map { it.offsetMillis })
    }

    @Test
    fun `every fix passes the walk's 20 m accuracy gate`() {
        val steps = WayReplayTimeline.steps(listOf(0.0, 10.0, 20.0).map { point(t = it) })

        assertTrue(steps.all { it.accuracyMeters in 0f..20f })
    }

    @Test
    fun `a fix carries the speed and bearing of the leg it ends`() {
        val a = WayPoint(lat = 42.0, lon = -8.0, alt = null, t = 0.0)
        val b = WayPoint(lat = 42.0009, lon = -8.0, alt = null, t = 100.0)
        val c = WayPoint(lat = 42.0009, lon = -7.9988, alt = null, t = 200.0)

        val third = WayReplayTimeline.steps(listOf(a, b, c))[2]

        assertEquals(WayGeometry.distanceMeters(b, c) / 100, third.speedMetersPerSecond.toDouble(), 1e-4)
        assertEquals(WayGeometry.bearing(b.coordinate(), c.coordinate()), third.bearingDegrees.toDouble(), 1e-3)
    }

    @Test
    fun `the first fix takes the speed and bearing of the leg it starts`() {
        val a = WayPoint(lat = 42.0, lon = -8.0, alt = null, t = 0.0)
        val b = WayPoint(lat = 42.0009, lon = -8.0, alt = null, t = 100.0)

        val first = WayReplayTimeline.steps(listOf(a, b))[0]

        assertEquals(WayGeometry.distanceMeters(a, b) / 100, first.speedMetersPerSecond.toDouble(), 1e-4)
        assertEquals(WayGeometry.bearing(a.coordinate(), b.coordinate()), first.bearingDegrees.toDouble(), 1e-3)
    }

    @Test
    fun `a standstill reads as stationary and keeps the heading it arrived with`() {
        val a = WayPoint(lat = 42.0, lon = -8.0, alt = null, t = 0.0)
        val b = WayPoint(lat = 42.0, lon = -7.9988, alt = null, t = 100.0)
        val stillAtB = b.copy(t = 700.0)

        val steps = WayReplayTimeline.steps(listOf(a, b, stillAtB))

        assertEquals(0f, steps[2].speedMetersPerSecond)
        assertEquals(steps[1].bearingDegrees, steps[2].bearingDegrees)
    }

    @Test
    fun `each fix carries the point's place, altitude, and frac`() {
        val a = WayPoint(lat = 42.0, lon = -8.0, alt = 310.4, t = 0.0)
        val b = WayPoint(lat = 42.0009, lon = -8.0, alt = null, t = 100.0)

        val steps = WayReplayTimeline.steps(listOf(a, b))

        assertEquals(
            listOf(Triple(42.0, -8.0, 310.4), Triple(42.0009, -8.0, null)),
            steps.map { Triple(it.latitude, it.longitude, it.altitudeMeters) },
        )
        assertEquals(listOf(0.0, 1.0), steps.map { it.frac })
    }

    @Test
    fun `mock mode goes on before the first fix`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(WALK_ID, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(Call.Mode(enabled = true), client.calls.first())
    }

    @Test
    fun `a replay that plays out leaves mock mode off`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(WALK_ID, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(Call.Mode(enabled = false), client.calls.last())
    }

    @Test
    fun `stopping a replay leaves mock mode off and plays nothing more`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        replayer.start(WALK_ID, listOf(0.0, 3.0, 600.0).map { point(t = it) })
        advanceTimeBy(10_000)

        replayer.stop()
        advanceUntilIdle()

        assertEquals(2, client.fixes().size)
        assertEquals(Call.Mode(enabled = false), client.calls.last())
    }

    @Test
    fun `stopping with nothing replaying takes mock mode, then lets it go`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }

        replayer(client).stop()

        assertEquals(listOf<Call>(Call.Mode(enabled = true), Call.Mode(enabled = false)), client.calls)
    }

    @Test
    fun `a new replay replaces the one in progress`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        replayer.start(WALK_ID, listOf(0.0, 600.0).map { point(lat = 10.0, t = it) })
        advanceTimeBy(1_000)

        replayer.start(WALK_ID, listOf(0.0, 600.0).map { point(lat = 20.0, t = it) })
        advanceUntilIdle()

        assertEquals(listOf(10.0, 20.0, 20.0), client.fixes().map { it.fix.step.latitude })
    }

    @Test
    fun `the next tracker start takes mock mode, then lets it go, when no replay runs`() = runTest {
        // Play services keeps a killed process's mock mode on, and ignores
        // an "off" from a client that never turned it on (OnePlus 13).
        val client = FakeMockLocationClient { testScheduler.currentTime }

        replayer(client).onTrackerStart()
        runCurrent()

        assertEquals(listOf<Call>(Call.Mode(enabled = true), Call.Mode(enabled = false)), client.calls)
    }

    @Test
    fun `a release still turns mock mode off when taking it is refused`() = runTest {
        val client = FakeMockLocationClient(refuseMockMode = true) { testScheduler.currentTime }

        replayer(client).onTrackerStart()
        runCurrent()

        assertEquals(listOf<Call>(Call.Mode(enabled = true), Call.Mode(enabled = false)), client.calls)
    }

    @Test
    fun `a tracker start mid-replay keeps the replay playing`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        replayer.start(WALK_ID, listOf(0.0, 3.0, 6.0).map { point(t = it) })
        advanceTimeBy(1_000)

        replayer.onTrackerStart()
        advanceUntilIdle()

        assertEquals(3, client.fixes().size)
        assertEquals(1, client.calls.count { it == Call.Mode(enabled = false) })
    }

    @Test
    fun `a replay the platform refuses plays nothing and still turns mock mode off`() = runTest {
        val client = FakeMockLocationClient(refuseMockMode = true) { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(WALK_ID, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertTrue(client.fixes().isEmpty())
        assertEquals(Call.Mode(enabled = false), client.calls.last())
    }

    @Test
    fun `a fix builds a platform Location with every field it carries`() {
        val step = ReplayStep(
            offsetMillis = 3_000,
            latitude = 42.0009,
            longitude = -8.0,
            altitudeMeters = 310.4,
            accuracyMeters = 5f,
            speedMetersPerSecond = 1.2f,
            bearingDegrees = 87.5f,
            frac = 0.5,
        )

        val location = MockFix(step, timeMillis = WALL_START, elapsedRealtimeNanos = 9_000_000_000).toLocation()

        assertEquals(
            listOf<Any>("fused", 42.0009, -8.0, 310.4, 5f, 1.2f, 87.5f, WALL_START, 9_000_000_000L),
            listOf<Any>(
                location.provider!!, location.latitude, location.longitude, location.altitude,
                location.accuracy, location.speed, location.bearing, location.time, location.elapsedRealtimeNanos,
            ),
        )
        assertTrue(location.hasAccuracy() && location.hasSpeed() && location.hasBearing() && location.hasAltitude())
    }

    @Test
    fun `a fix without a recorded altitude builds a Location without one`() {
        val step = ReplayStep(0, 42.0, -8.0, null, 5f, 0f, 0f, 0.0)

        val location = MockFix(step, timeMillis = WALL_START, elapsedRealtimeNanos = 1).toLocation()

        assertFalse(location.hasAltitude())
    }

    @Test
    fun `an empty route has nothing to play`() {
        assertNull(WayReplayTimeline.steps(emptyList()).firstOrNull())
    }

    /** On the test's own scope, so `advanceUntilIdle` plays replays out and `runTest` awaits them. */
    private fun TestScope.replayer(client: MockLocationClient) = WayReplayer(
        client = client,
        scope = this,
        wallClockMillis = { WALL_START + testScheduler.currentTime },
        elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000 },
    )

    private fun point(lat: Double = 42.0, t: Double) = WayPoint(lat = lat, lon = -8.0, alt = null, t = t)

    private fun WayPoint.coordinate() = WayCoordinate(lat = lat, lon = lon)

    private sealed interface Call {
        data class Mode(val enabled: Boolean) : Call
        data class Fix(val fix: MockFix, val playedAtMillis: Long) : Call
    }

    private class FakeMockLocationClient(
        private val refuseMockMode: Boolean = false,
        private val now: () -> Long,
    ) : MockLocationClient {
        val calls = mutableListOf<Call>()

        fun fixes(): List<Call.Fix> = calls.filterIsInstance<Call.Fix>()

        override suspend fun setMockMode(enabled: Boolean) {
            calls += Call.Mode(enabled)
            if (enabled && refuseMockMode) throw SecurityException("not the mock location app")
        }

        override suspend fun setMockLocation(fix: MockFix) {
            calls += Call.Fix(fix, now())
        }
    }

    private companion object {
        const val WALK_ID = 7L
        const val WALL_START = 1_700_000_000_000L
    }
}
