// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * [WayGpxExporter] against iOS's `WayGPXExporterTests.swift@7c200bf` and the
 * exact text parity spec A §24 records from it.
 */
class WayGpxExporterTest {

    @Test
    fun `emits timed waypoints and no track`() {
        val xml = WayGpxExporter.gpx(iosFixture())

        assertFalse(xml.contains("<trk>"))
        assertEquals(2, xml.split("<wpt ").size - 1)
        assertTrue(xml.contains("<time>2023-11-14T22:13:20Z</time>"))
        assertTrue(xml.contains("<time>2023-11-14T22:14:50Z</time>"))
        assertTrue(xml.contains("<ele>300</ele>"))
        assertTrue("moment kinds ride on the nearest route waypoint", xml.contains("<name>sit-1</name>"))
    }

    @Test
    fun `writes iOS's exact text, newline-joined with no trailing newline`() {
        val expected = listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<gpx version=\"1.1\" creator=\"Pilgrim\" xmlns=\"http://www.topografix.com/GPX/1/1\">",
            "  <wpt lat=\"42.1\" lon=\"-8.2\">",
            "    <ele>300</ele>",
            "    <time>2023-11-14T22:13:20Z</time>",
            "  </wpt>",
            "  <wpt lat=\"42.2\" lon=\"-8.3\">",
            "    <time>2023-11-14T22:14:50Z</time>",
            "    <name>sit-1</name>",
            "  </wpt>",
            "</gpx>",
        ).joinToString("\n")

        assertEquals(expected, WayGpxExporter.gpx(iosFixture()))
    }

    @Test
    fun `one waypoint per route point, in route order`() {
        val route = listOf(0.0, 2.0, 2.5, 60.0, 61.2).mapIndexed { i, t -> point(lat = 42.0 + i / 10.0, t = t) }

        val lats = waypoints(WayGpxExporter.gpx(way(route))).map { it.substringAfter("lat=\"").substringBefore('"') }

        assertEquals(listOf("42.0", "42.1", "42.2", "42.3", "42.4"), lats)
    }

    @Test
    fun `every waypoint carries exactly one time, and the times never go back`() {
        val route = listOf(0.0, 2.0, 2.5, 60.0, 61.2, 61.9).map { point(t = it) }

        val times = waypoints(WayGpxExporter.gpx(way(route))).map { block ->
            assertEquals(1, block.split("<time>").size - 1)
            Instant.parse(block.substringAfter("<time>").substringBefore("</time>"))
        }

        assertEquals(times.sorted(), times)
    }

    @Test
    fun `the recorded pace survives in the times`() {
        val route = listOf(0.0, 3.0, 600.0).map { point(t = it) }

        val times = waypoints(WayGpxExporter.gpx(way(route))).map {
            Instant.parse(it.substringAfter("<time>").substringBefore("</time>")).epochSecond - DEPARTED_SECONDS
        }

        assertEquals(listOf(0L, 3L, 600L), times)
    }

    @Test
    fun `fractional seconds are dropped, not rounded`() {
        val departed = Instant.ofEpochSecond(DEPARTED_SECONDS, 900_000_000)
        val route = listOf(point(t = 0.0), point(t = 0.6))

        val xml = WayGpxExporter.gpx(way(route, departedAt = departed))

        assertTrue(xml.contains("<time>2023-11-14T22:13:20Z</time>\n"))
        assertTrue(xml.contains("<time>2023-11-14T22:13:21Z</time>\n"))
    }

    @Test
    fun `a moment's point rounds half away from zero, as Swift's rounded() does`() {
        val route = listOf(point(t = 0.0), point(t = 1.0), point(t = 2.0))
        val quarter = moment("voice-1", frac = 0.25)

        val blocks = waypoints(WayGpxExporter.gpx(way(route, moments = listOf(quarter))))

        assertTrue(blocks[1].contains("<name>voice-1</name>"))
    }

    @Test
    fun `moments sharing a point are space-joined in the Way's order`() {
        val route = listOf(point(t = 0.0), point(t = 1.0))
        val moments = listOf(moment("photo-2", frac = 0.9), moment("voice-1", frac = 1.0))

        val xml = WayGpxExporter.gpx(way(route, moments = moments))

        assertTrue(xml.contains("<name>photo-2 voice-1</name>"))
    }

    @Test
    fun `points without a moment carry no name`() {
        val route = listOf(point(t = 0.0), point(t = 1.0), point(t = 2.0))

        val xml = WayGpxExporter.gpx(way(route, moments = listOf(moment("sit-1", frac = 1.0))))

        assertEquals(1, xml.split("<name>").size - 1)
    }

    @Test
    fun `elevation rounds half away from zero, and a missing one is omitted`() {
        val route = listOf(point(t = 0.0, alt = 2.5), point(t = 1.0, alt = -2.5), point(t = 2.0, alt = null))

        val elevations = waypoints(WayGpxExporter.gpx(way(route))).map {
            if (it.contains("<ele>")) it.substringAfter("<ele>").substringBefore("</ele>") else null
        }

        assertEquals(listOf("3", "-3", null), elevations)
    }

    @Test
    fun `coordinates print as Swift's Double description does`() {
        val route = listOf(
            WayPoint(lat = 42.0, lon = 0.0005, alt = null, t = 0.0),
            WayPoint(lat = 0.00001, lon = -0.000015, alt = null, t = 1.0),
        )

        val attributes = waypoints(WayGpxExporter.gpx(way(route))).map { it.substringBefore(">").trim() }

        assertEquals(listOf("<wpt lat=\"42.0\" lon=\"0.0005\"", "<wpt lat=\"1e-05\" lon=\"-1.5e-05\""), attributes)
    }

    @Test
    fun `an empty route writes an empty gpx`() {
        val xml = WayGpxExporter.gpx(way(emptyList(), moments = listOf(moment("sit-1", frac = 1.0))))

        assertEquals(0, waypoints(xml).size)
    }

    private fun iosFixture(): Way = way(
        route = listOf(
            WayPoint(lat = 42.1, lon = -8.2, alt = 300.0, t = 0.0),
            WayPoint(lat = 42.2, lon = -8.3, alt = null, t = 90.0),
        ),
        moments = listOf(
            WayMoment(id = "sit-1", frac = 1.0, at = null, kind = WayMomentKind.Meditation(minutes = 5, isEstimate = false)),
        ),
    )

    private fun way(
        route: List<WayPoint>,
        moments: List<WayMoment> = emptyList(),
        departedAt: Instant = Instant.ofEpochSecond(DEPARTED_SECONDS),
    ) = Way(
        id = "walk:x",
        source = WaySource.OwnWalk("00000000-0000-0000-0000-000000000001"),
        title = "x",
        departedAt = departedAt,
        tzIdentifier = null,
        expires = null,
        route = route,
        totalDistanceMeters = 13_000.0,
        theirActiveSeconds = 90.0,
        moments = moments,
        weather = null,
    )

    private fun point(lat: Double = 42.1, t: Double, alt: Double? = null) = WayPoint(lat = lat, lon = -8.2, alt = alt, t = t)

    private fun moment(id: String, frac: Double) =
        WayMoment(id = id, frac = frac, at = null, kind = WayMomentKind.Rest(minutes = 3))

    private fun waypoints(xml: String): List<String> =
        xml.split("<wpt ").drop(1).map { "<wpt " + it.substringBefore("</wpt>") }

    private companion object {
        const val DEPARTED_SECONDS = 1_700_000_000L
    }
}
