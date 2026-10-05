// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import java.time.Instant
import kotlin.math.cos
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.debug.honor.WayReplayTimeline.Timing
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngine
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The fastest pace [HonorDebugReceiver]'s KDoc names, against the real
 * engine: a paced replay of a stage-like line, its points hundreds of
 * metres apart as the dataset's are, fed fix by fix as the walk feeds them.
 */
class WayReplayPaceLimitTest {

    @Test
    fun `at the documented 7 m per s every fix lands on the Way and the arrival counts`() {
        val walked = walk(paceMetersPerSecond = 7.0)

        assertTrue(walked.everyFixOnWay)
        assertTrue(walked.arrived)
    }

    @Test
    fun `at 8 m per s two fixes fall inside the arrival radius, one short of the three it needs`() {
        val walked = walk(paceMetersPerSecond = 8.0)

        assertTrue(walked.everyFixOnWay)
        assertFalse(walked.arrived)
    }

    @Test
    fun `joining the last part of the line still arrives at the documented pace`() {
        val walked = walk(paceMetersPerSecond = 7.0, fromFrac = 0.95)

        assertTrue(walked.everyFixOnWay)
        assertTrue(walked.arrived)
    }

    private class Walked(val everyFixOnWay: Boolean, val arrived: Boolean)

    private fun walk(paceMetersPerSecond: Double, fromFrac: Double = 0.0): Walked {
        val way = stageLike()
        var now = START_MILLIS
        val engine = HonorEngine(way, softTapEnabled = false, voicesEnabled = false, clock = Clock { now })
        var everyFixOnWay = true
        var arrived = false
        WayReplayTimeline.steps(way.route, Timing(paceMetersPerSecond = paceMetersPerSecond, fromFrac = fromFrac))
            .forEach { step ->
                now = START_MILLIS + step.offsetMillis
                val events = engine.processLocation(
                    LocationPoint(
                        timestamp = now,
                        latitude = step.latitude,
                        longitude = step.longitude,
                        horizontalAccuracyMeters = step.accuracyMeters,
                        speedMetersPerSecond = step.speedMetersPerSecond,
                    ),
                )
                everyFixOnWay = everyFixOnWay && engine.isOnWay
                arrived = arrived || events.any { it is HonorEngineEvent.Arrived }
            }
        return Walked(everyFixOnWay, arrived)
    }

    /** About 2.3 km at the Camino's latitude in legs of 250 to 440 m, turning each time, ending eastward. */
    private fun stageLike(): Way {
        val legs = listOf(437.0 to 0.0, 0.0 to 413.0, 300.0 to 120.0, -90.0 to 380.0, 250.0 to 0.0, 0.0 to 440.0)
        val route = legs.runningFold(WayPoint(lat = 42.9, lon = -8.0, alt = 500.0, t = 0.0)) { at, (north, east) ->
            WayPoint(
                lat = at.lat + north / METERS_PER_DEGREE,
                lon = at.lon + east / (METERS_PER_DEGREE * cos(Math.toRadians(at.lat))),
                alt = at.alt,
                t = at.t + 600.0,
            )
        }
        return Way(
            id = "pilgrimage:test-route:0",
            source = WaySource.Pilgrimage(routeId = "test-route", stageIndex = 0),
            title = "x",
            departedAt = Instant.ofEpochSecond(1_700_000_000),
            tzIdentifier = null,
            expires = null,
            route = route,
            totalDistanceMeters = 0.0,
            theirActiveSeconds = route.last().t,
            moments = emptyList(),
            weather = null,
        )
    }

    private companion object {
        const val START_MILLIS = 1_700_000_000_000L
        const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180
    }
}
