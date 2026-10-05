// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMark
import org.walktalkmeditate.pilgrim.domain.honor.WayMarkKind

/**
 * A stage's service marks on the map (pilgrimage-stage spec P5 §1, §4,
 * §5): iOS `WayMarkPinsTests.swift@7c200bf`, names kept; then the camera
 * report's rules in iOS's order, the walk anchor's 200 m, and the one layer
 * the marks share with the moment pins (owner decision 9). Robolectric only
 * for the rasters a layer carries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayMarkPinsTest {

    /** `count` water marks strung east along the equator, 100 m apart. */
    private fun marks(count: Int): List<WayMark> = (0 until count).map { index ->
        WayMark(
            id = "m$index", kind = WayMarkKind.WATER, name = "f$index",
            at = WayCoordinate(lat = 0.0, lon = index * 0.000898),
            frac = index.toDouble() / maxOf(count - 1, 1), offLineMeters = 10.0,
        )
    }

    // ---- iOS WayMarkPinsTests ---------------------------------------------

    @Test
    fun testEveryKindHasItsOwnGlyph() {
        assertEquals("drop.fill", WayMarkPins.symbol(WayMarkKind.WATER))
        assertEquals("fork.knife", WayMarkPins.symbol(WayMarkKind.FOOD))
        assertEquals("bed.double.fill", WayMarkPins.symbol(WayMarkKind.BED))
        assertEquals("bus.fill", WayMarkPins.symbol(WayMarkKind.TRANSPORT))
        assertEquals("bag.fill", WayMarkPins.symbol(WayMarkKind.SUPPLY))
        assertEquals("cross.case.fill", WayMarkPins.symbol(WayMarkKind.MEDICAL))
    }

    @Test
    fun testNothingIsDrawnBelowZoomThirteen() {
        assertTrue(WayMarkPins.pins(marks(5), zoom = 12.9, near = null).isEmpty())
        assertEquals(5, WayMarkPins.pins(marks(5), zoom = 13.0, near = null).size)
    }

    @Test
    fun testTheScreenNeverCarriesMoreThanFortyNearestFirst() {
        val all = marks(200)
        // Standing at the 150th mark: the forty nearest run 130 to 169.
        val here = WayCoordinate(lat = 0.0, lon = 150 * 0.000898)
        val pins = WayMarkPins.pins(all, zoom = 15.0, near = here)
        assertEquals(WayMarkPins.MAX_PER_SCREEN, pins.size)
        val ids = pins.map { it.markId }.toSet()
        assertTrue(ids.contains("m150"))
        assertTrue(ids.contains("m131"))
        assertTrue(ids.contains("m169"))
        assertFalse(ids.contains("m0"))
        assertFalse(ids.contains("m199"))
    }

    @Test
    fun testWithoutAFixTheFirstFortyAlongTheStageAreDrawn() {
        val pins = WayMarkPins.pins(marks(100), zoom = 15.0, near = null)
        assertEquals(40, pins.size)
        assertEquals("m0", pins[0].markId)
    }

    /** Android's mark pin names no moment, and the map tap's targets leave it out. */
    @Test
    fun testAMarkIsNeverAMomentAndNeverTappable() {
        val fountain = WayCoordinate(lat = 0.0, lon = 0.0)
        val layer = WayPinLayer(
            marks = listOf(WayMarkMapPin("m1", fountain.lat, fountain.lon, raster())),
            moments = listOf(WayMapPin("wp-1", latitude = 0.0, longitude = 0.01, image = raster())),
        )

        assertNull("a mark has no card to open", nearestTapTarget(fountain, layer.tapTargets(emptyList())))
    }

    @Test
    fun testAPinLandsOnItsMarksOwnCoordinate() {
        // Distinct, differently-signed lat and lon: a swap between them draws
        // every fountain on the Camino into the Atlantic, and passes every
        // count-and-order assertion above.
        val fountain = WayMark(
            id = "m1", kind = WayMarkKind.WATER, name = "fuente",
            at = WayCoordinate(lat = 42.881, lon = -8.545), frac = 0.5, offLineMeters = 10.0,
        )
        val pin = WayMarkPins.pins(listOf(fountain), zoom = 15.0, near = null).firstOrNull()
            ?: error("a mark above the draw zoom must produce a pin")
        assertEquals(42.881, pin.at.lat, 1e-9)
        assertEquals(-8.545, pin.at.lon, 1e-9)
    }

    // ---- The selection's edges --------------------------------------------

    @Test
    fun `a NaN zoom draws nothing, as Swift's comparison reads it`() {
        assertTrue(WayMarkPins.pins(marks(5), zoom = Double.NaN, near = null).isEmpty())
    }

    @Test
    fun `two marks the same distance away go by id`() {
        val west = WayMark("b-west", WayMarkKind.FOOD, "w", WayCoordinate(0.0, -0.000898), 0.1, 5.0)
        val east = WayMark("a-east", WayMarkKind.FOOD, "e", WayCoordinate(0.0, 0.000898), 0.2, 5.0)

        val pins = WayMarkPins.pins(listOf(west, east), zoom = 15.0, near = WayCoordinate(0.0, 0.0))

        assertEquals(listOf("a-east", "b-west"), pins.map { it.markId })
    }

    @Test
    fun `every kind is drawn with its own stand-in`() {
        assertEquals(6, WayMarkKind.entries.map(::markGlyphVector).toSet().size)
    }

    // ---- The camera report (P5 §4), its rules in iOS's order -----------------

    private val origin = WayCoordinate(lat = 0.0, lon = 0.0)

    /** [meters] east of the origin along the equator, where a degree of longitude is the WGS84 semi-major axis's. */
    private fun east(meters: Double) = WayCoordinate(lat = 0.0, lon = meters / (6_378_137.0 * Math.PI / 180))

    /** A ruler that puts every two places exactly 200 m apart, which no pair of equatorial coordinates measures. */
    private val exactly200Meters: (WayCoordinate, WayCoordinate) -> Double = { _, _ -> 200.0 }

    @Test
    fun `the first camera event always reports`() {
        assertTrue(CameraReportThrottle().report(origin, zoom = 15.3, throttled = true, nowUptimeMillis = 10_000L))
    }

    /** The walk map's listeners, installed after its seed, report the camera as it stands, as idle does. */
    @Test
    fun `the report taken as the listeners install gets through, and the seed's own change after it repeats nothing`() {
        val throttle = CameraReportThrottle()

        val installed = throttle.report(origin, zoom = 14.0, throttled = false, nowUptimeMillis = 10_000L)
        val seedsOwnChange = throttle.report(origin, zoom = 14.0, throttled = true, nowUptimeMillis = 10_400L)

        assertEquals(true to false, installed to seedsOwnChange)
    }

    @Test
    fun `a camera change inside 250 ms of the last report is dropped, even across a level`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        val inside = throttle.report(origin, zoom = 12.0, throttled = true, nowUptimeMillis = 10_249L)
        val after = throttle.report(origin, zoom = 12.0, throttled = true, nowUptimeMillis = 10_250L)

        assertEquals(false to true, inside to after)
    }

    @Test
    fun `an idle skips the 250 ms window`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        assertTrue(throttle.report(origin, zoom = 12.0, throttled = false, nowUptimeMillis = 10_010L))
    }

    @Test
    fun `a NaN or infinite zoom is dropped and doesn't move the clock`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        val nan = throttle.report(origin, zoom = Double.NaN, throttled = false, nowUptimeMillis = 10_300L)
        val infinite = throttle.report(origin, zoom = Double.POSITIVE_INFINITY, throttled = false, nowUptimeMillis = 10_300L)
        val next = throttle.report(origin, zoom = 12.0, throttled = true, nowUptimeMillis = 10_301L)

        assertEquals(Triple(false, false, true), Triple(nan, infinite, next))
    }

    @Test
    fun `idle with no level change and less than 200 m sends nothing`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        assertFalse(throttle.report(east(150.0), zoom = 15.8, throttled = false, nowUptimeMillis = 20_000L))
    }

    @Test
    fun `a centre must be more than 200 m from the last report`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        val short = throttle.report(east(199.999), zoom = 15.0, throttled = false, nowUptimeMillis = 20_000L)
        val past = throttle.report(east(200.001), zoom = 15.0, throttled = false, nowUptimeMillis = 20_000L)

        assertEquals(false to true, short to past)
    }

    /** iOS's strict `>`: the walk's own rule is `>=` (below). */
    @Test
    fun `a centre exactly 200 m from the last report sends nothing`() {
        val throttle = CameraReportThrottle(metersBetween = exactly200Meters)
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        assertFalse(throttle.report(east(200.0), zoom = 15.0, throttled = false, nowUptimeMillis = 20_000L))
    }

    // ---- The walk's anchor (P5 §2) --------------------------------------------

    /** iOS's `>=`, where the camera report's is a strict `>`. */
    @Test
    fun `a fix exactly 200 m from the anchor moves it`() {
        val selection = WalkMarkSelection(metersBetween = exactly200Meters)
        selection.onFix(origin)

        assertTrue(selection.onFix(east(200.0)))
    }

    @Test
    fun `the 200 m is counted from the last report, not the last event`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 15.0, throttled = true, nowUptimeMillis = 10_000L)

        val first = throttle.report(east(150.0), zoom = 15.0, throttled = true, nowUptimeMillis = 11_000L)
        val second = throttle.report(east(250.0), zoom = 15.0, throttled = true, nowUptimeMillis = 12_000L)

        assertEquals(false to true, first to second)
    }

    @Test
    fun `a pinch within one level sends nothing, one across a level does`() {
        val throttle = CameraReportThrottle()
        throttle.report(origin, zoom = 13.0, throttled = true, nowUptimeMillis = 10_000L)

        val within = throttle.report(origin, zoom = 13.9, throttled = false, nowUptimeMillis = 11_000L)
        val across = throttle.report(origin, zoom = 12.95, throttled = false, nowUptimeMillis = 12_000L)

        assertEquals(false to true, within to across)
    }

    // ---- The one layer (owner decision 9, P5 §5.3) ----------------------------

    @Test
    fun `the Way pin layer carries the marks first, then the moments`() {
        val mark = WayMarkMapPin("m1", latitude = 0.0, longitude = 0.001, image = raster())
        val moment = WayMapPin("wp-1", latitude = 0.0, longitude = 0.002, image = raster())

        val points = WayPinLayer(marks = listOf(mark), moments = listOf(moment)).points

        assertEquals(listOf(0.001, 0.002), points.map { it.longitude })
    }

    @Test
    fun `a tap beside a mark goes through to the moment pin it overlaps`() {
        val layer = WayPinLayer(
            marks = listOf(WayMarkMapPin("m1", latitude = 0.0, longitude = 0.0, image = raster())),
            moments = listOf(WayMapPin("wp-1", latitude = 0.0, longitude = 0.0001, image = raster())),
        )

        assertEquals("wp-1", nearestTapTarget(WayCoordinate(0.0, 0.0), layer.tapTargets(emptyList()))?.wayMomentId)
    }

    private fun raster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}
