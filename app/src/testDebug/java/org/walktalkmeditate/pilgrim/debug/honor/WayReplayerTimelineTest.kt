// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.debug.honor.WayReplayTimeline.Timing
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

        replayer.start(SOURCE, listOf(0.0, 3.0, 3.5, 603.5).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(listOf(0L, 3_000L, 3_500L, 603_500L), client.fixes().map { it.playedAtMillis - startedAt })
    }

    @Test
    fun `each fix is stamped with the clocks at the moment it plays`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(SOURCE, listOf(0.0, 3.0, 603.5).map { point(t = it) })
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

        replayer.start(SOURCE, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(Call.Mode(enabled = true), client.calls.first())
    }

    @Test
    fun `a replay that plays out leaves mock mode off`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)

        replayer.start(SOURCE, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(Call.Mode(enabled = false), client.calls.last())
    }

    @Test
    fun `stopping a replay leaves mock mode off and plays nothing more`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        replayer.start(SOURCE, listOf(0.0, 3.0, 600.0).map { point(t = it) })
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
        replayer.start(SOURCE, listOf(0.0, 600.0).map { point(lat = 10.0, t = it) })
        advanceTimeBy(1_000)

        replayer.start(SOURCE, listOf(0.0, 600.0).map { point(lat = 20.0, t = it) })
        advanceUntilIdle()

        assertEquals(listOf(10.0, 20.0, 20.0), client.fixes().map { it.fix.step.latitude })
    }

    @Test
    fun `a replay in progress leaves its mark, for a process that outlives it to find`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val marker = FakeMockModeMarker()
        replayer(client, marker).start(SOURCE, listOf(0.0, 600.0).map { point(t = it) })

        advanceTimeBy(1_000)

        assertTrue(marker.taken)
    }

    @Test
    fun `a replay marks mock mode taken before it takes it`() = runTest {
        val marker = FakeMockModeMarker()
        val client = FakeMockLocationClient(marker = marker) { testScheduler.currentTime }

        replayer(client, marker).start(SOURCE, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertEquals(true, client.markedWhenTaken)
    }

    @Test
    fun `a replay that plays out clears its mark`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val marker = FakeMockModeMarker()

        replayer(client, marker).start(SOURCE, listOf(0.0, 3.0).map { point(t = it) })
        advanceUntilIdle()

        assertFalse(marker.taken)
    }

    @Test
    fun `a tracker start with no replay left on never touches mock mode`() = runTest {
        // Taking and releasing mock mode empties the device's cached fix,
        // which the Honor overview reads (OnePlus 13, 2026-10-02).
        val client = FakeMockLocationClient { testScheduler.currentTime }

        replayer(client, FakeMockModeMarker(taken = false)).onTrackerStart()
        runCurrent()

        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `a tracker start after a replay was killed takes mock mode, then lets it go`() = runTest {
        // Play services keeps a killed process's mock mode on, and ignores
        // an "off" from a client that never turned it on (OnePlus 13).
        val client = FakeMockLocationClient { testScheduler.currentTime }

        replayer(client, FakeMockModeMarker(taken = true)).onTrackerStart()
        runCurrent()

        assertEquals(listOf<Call>(Call.Mode(enabled = true), Call.Mode(enabled = false)), client.calls)
    }

    @Test
    fun `releasing a killed replay's mock mode clears its mark`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val marker = FakeMockModeMarker(taken = true)

        replayer(client, marker).onTrackerStart()
        runCurrent()

        assertFalse(marker.taken)
    }

    @Test
    fun `a release still turns mock mode off when taking it is refused`() = runTest {
        val client = FakeMockLocationClient(refuseMockMode = true) { testScheduler.currentTime }

        replayer(client, FakeMockModeMarker(taken = true)).onTrackerStart()
        runCurrent()

        assertEquals(listOf<Call>(Call.Mode(enabled = true), Call.Mode(enabled = false)), client.calls)
    }

    @Test
    fun `a release that fails keeps the mark, so the next tracker start tries again`() = runTest {
        val client = FakeMockLocationClient(refuseMockModeOff = true) { testScheduler.currentTime }
        val marker = FakeMockModeMarker(taken = true)

        replayer(client, marker).onTrackerStart()
        runCurrent()

        assertTrue(marker.taken)
    }

    @Test
    fun `the mark persists across instances, as a new process reads it`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferencesMockModeMarker(context).taken = true

        assertTrue(PreferencesMockModeMarker(context).taken)
    }

    @Test
    fun `a tracker start mid-replay keeps the replay playing`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        replayer.start(SOURCE, listOf(0.0, 3.0, 6.0).map { point(t = it) })
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

        replayer.start(SOURCE, listOf(0.0, 3.0).map { point(t = it) })
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

    @Test
    fun `a pace times each fix at its distance along the line over the pace`() {
        val route = east(legs = 10, t = { it * it * 7.0 })

        val steps = WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = 4.0))

        val first = steps.first().place()
        steps.forEach { step ->
            val along = WayGeometry.distanceMeters(first, step.place())
            assertEquals(along / 4.0 * 1_000, step.offsetMillis.toDouble(), 1.0)
        }
        assertEquals(1_000.0 / 4.0 * 1_000, steps.last().offsetMillis.toDouble(), 1.0)
    }

    @Test
    fun `paced fixes come evenly, at most two seconds apart`() {
        val steps = WayReplayTimeline.steps(east(legs = 10), Timing(paceMetersPerSecond = 3.0))

        val gaps = steps.zipWithNext { a, b -> b.offsetMillis - a.offsetMillis }

        assertTrue(gaps.all { it in 1_001L..2_000L })
        assertTrue(gaps.max() - gaps.min() <= 1)
    }

    @Test
    fun `a paced fix carries the pace as its speed on every leg`() {
        val route = east(legs = 10, t = { it * it * 7.0 })

        val steps = WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = 4.0))

        assertEquals(listOf(4f), steps.map { it.speedMetersPerSecond }.distinct())
    }

    @Test
    fun `a paced fix carries the heading of the route leg it lies on`() {
        val corner = east(legs = 1).last()
        val route = listOf(east(legs = 1).first(), corner, corner.copy(lat = corner.lat + LEG_DEGREES, t = 200.0))

        val steps = WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = 5.0))

        val eastward = steps.filter { it.frac < 0.49 }.map { it.bearingDegrees }
        val northward = steps.filter { it.frac > 0.51 }.map { it.bearingDegrees }
        assertTrue(eastward.isNotEmpty() && eastward.all { abs(it - 90f) < 0.01f })
        assertTrue(northward.isNotEmpty() && northward.all { abs(it) < 0.01f })
    }

    @Test
    fun `a paced fix on a leg of no length keeps the heading before it`() {
        val end = east(legs = 1).last()
        val route = east(legs = 1) + end.copy(t = 700.0)

        val steps = WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = 5.0))

        assertEquals(steps[steps.size - 2].bearingDegrees, steps.last().bearingDegrees)
        assertEquals(90f, steps.last().bearingDegrees, 0.01f)
    }

    @Test
    fun `a pace that isn't a finite number above zero is refused`() {
        val route = east(legs = 2)

        listOf(0.0, -1.2, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { pace ->
            assertNotNull("pace $pace", WayReplayTimeline.refusal(route, Timing(paceMetersPerSecond = pace)))
            assertThrows(IllegalArgumentException::class.java) {
                WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = pace))
            }
        }
    }

    @Test
    fun `a pace too slow for the fix limit is refused`() {
        assertNotNull(WayReplayTimeline.refusal(east(legs = 10), Timing(paceMetersPerSecond = 0.001)))
    }

    @Test
    fun `a window starts with a fix exactly at from and ends with one exactly at to`() {
        val route = bends()
        val geometry = WayGeometry(route)

        val steps = WayReplayTimeline.steps(route, Timing(fromFrac = 0.37, toFrac = 0.81))

        assertEquals(geometry.coordinate(atFrac = 0.37), steps.first().coordinate())
        assertEquals(geometry.coordinate(atFrac = 0.81), steps.last().coordinate())
        assertEquals(0.37, steps.first().frac, 1e-12)
        assertEquals(0.81, steps.last().frac, 1e-12)
    }

    @Test
    fun `a window at the recorded pace plays the points inside it at their recorded gaps`() {
        val steps = WayReplayTimeline.steps(east(legs = 10), Timing(fromFrac = 0.25, toFrac = 0.75))

        assertEquals(
            listOf(0L, 30_000L, 90_000L, 150_000L, 210_000L, 270_000L, 300_000L),
            steps.map { it.offsetMillis },
        )
    }

    @Test
    fun `the whole route at the recorded pace plays every point, standstills at either end included`() {
        val a = WayPoint(lat = 42.0, lon = -8.0, alt = 300.0, t = 0.0)
        val b = WayPoint(lat = 42.0009, lon = -8.0, alt = 310.0, t = 100.0)
        val route = listOf(a, a.copy(t = 5.0), b, b.copy(t = 700.0))

        val steps = WayReplayTimeline.steps(route)

        assertEquals(route.map { Triple(it.lat, it.lon, it.alt) }, steps.map { Triple(it.latitude, it.longitude, it.altitudeMeters) })
        assertEquals(listOf(0L, 5_000L, 100_000L, 700_000L), steps.map { it.offsetMillis })
    }

    @Test
    fun `fracs out of range or out of order are refused`() {
        val route = east(legs = 2)
        val windows = listOf(-0.1 to 1.0, 0.0 to 1.1, 0.5 to 0.5, 0.6 to 0.4, Double.NaN to 1.0, 0.0 to Double.NaN)

        windows.forEach { (from, to) ->
            assertNotNull("$from..$to", WayReplayTimeline.refusal(route, Timing(fromFrac = from, toFrac = to)))
            assertThrows(IllegalArgumentException::class.java) {
                WayReplayTimeline.steps(route, Timing(fromFrac = from, toFrac = to))
            }
        }
    }

    @Test
    fun `a window and a pace together play only the window, at the pace`() {
        val route = bends()
        val geometry = WayGeometry(route)

        val steps = WayReplayTimeline.steps(route, Timing(paceMetersPerSecond = 5.0, fromFrac = 0.4, toFrac = 0.9))

        assertEquals(geometry.coordinate(atFrac = 0.4), steps.first().coordinate())
        assertEquals(geometry.coordinate(atFrac = 0.9), steps.last().coordinate())
        assertEquals(0L, steps.first().offsetMillis)
        assertEquals(0.5 * geometry.totalMeters / 5.0 * 1_000, steps.last().offsetMillis.toDouble(), 1.0)
        assertTrue(steps.all { it.speedMetersPerSecond == 5f && it.frac in 0.4 - 1e-12..0.9 + 1e-12 })
    }

    @Test
    fun `a replay at a pace plays its fixes at the pace's gaps`() = runTest {
        val client = FakeMockLocationClient { testScheduler.currentTime }
        val replayer = replayer(client)
        val startedAt = testScheduler.currentTime

        replayer.start(SOURCE, east(legs = 1), Timing(paceMetersPerSecond = 40.0))
        advanceUntilIdle()

        assertEquals(listOf(0L, 1_250L, 2_500L), client.fixes().map { it.playedAtMillis - startedAt })
    }

    /** On the test's own scope, so `advanceUntilIdle` plays replays out and `runTest` awaits them. */
    private fun TestScope.replayer(
        client: MockLocationClient,
        marker: MockModeMarker = FakeMockModeMarker(),
    ) = WayReplayer(
        client = client,
        marker = marker,
        scope = this,
        wallClockMillis = { WALL_START + testScheduler.currentTime },
        elapsedRealtimeNanos = { testScheduler.currentTime * 1_000_000 },
    )

    private fun point(lat: Double = 42.0, t: Double) = WayPoint(lat = lat, lon = -8.0, alt = null, t = t)

    private fun WayPoint.coordinate() = WayCoordinate(lat = lat, lon = lon)

    private fun ReplayStep.coordinate() = WayCoordinate(lat = latitude, lon = longitude)

    private fun ReplayStep.place() = WayPoint(lat = latitude, lon = longitude, alt = null, t = 0.0)

    /** East along the equator in 100 m legs, a minute apart unless [t] says otherwise. */
    private fun east(legs: Int, t: (Int) -> Double = { it * 60.0 }) =
        (0..legs).map { WayPoint(lat = 0.0, lon = it * LEG_DEGREES, alt = null, t = t(it)) }

    /** A line that turns at every point, with legs of different lengths. */
    private fun bends() = listOf(
        WayPoint(lat = 42.0, lon = -8.0, alt = 300.0, t = 0.0),
        WayPoint(lat = 42.0011, lon = -8.0004, alt = 310.0, t = 140.0),
        WayPoint(lat = 42.0013, lon = -7.9981, alt = 320.0, t = 300.0),
        WayPoint(lat = 42.0042, lon = -7.9979, alt = 290.0, t = 610.0),
        WayPoint(lat = 42.0049, lon = -7.9952, alt = 280.0, t = 800.0),
    )

    private sealed interface Call {
        data class Mode(val enabled: Boolean) : Call
        data class Fix(val fix: MockFix, val playedAtMillis: Long) : Call
    }

    private class FakeMockModeMarker(override var taken: Boolean = false) : MockModeMarker

    private class FakeMockLocationClient(
        private val refuseMockMode: Boolean = false,
        private val refuseMockModeOff: Boolean = false,
        private val marker: MockModeMarker? = null,
        private val now: () -> Long,
    ) : MockLocationClient {
        val calls = mutableListOf<Call>()

        /** The marker as it stood when mock mode was first taken. */
        var markedWhenTaken: Boolean? = null
            private set

        fun fixes(): List<Call.Fix> = calls.filterIsInstance<Call.Fix>()

        override suspend fun setMockMode(enabled: Boolean) {
            calls += Call.Mode(enabled)
            if (enabled && markedWhenTaken == null) markedWhenTaken = marker?.taken
            if (enabled && refuseMockMode) throw SecurityException("not the mock location app")
            if (!enabled && refuseMockModeOff) throw SecurityException("not the mock location app")
        }

        override suspend fun setMockLocation(fix: MockFix) {
            calls += Call.Fix(fix, now())
        }
    }

    private companion object {
        const val SOURCE = "walk 7"
        const val WALL_START = 1_700_000_000_000L

        /** 100 m of longitude on the equator, by [WayGeometry.distanceMeters]'s 6,371 km sphere. */
        const val LEG_DEGREES = 100.0 / (6_371_000.0 * Math.PI / 180)
    }
}
