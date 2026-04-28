// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

class WalkFormatTest {

    private val metric = UnitSystem.Metric
    private val imperial = UnitSystem.Imperial

    @Test
    fun `duration formats short walks as M_SS`() {
        assertEquals("0:00", WalkFormat.duration(0))
        assertEquals("0:30", WalkFormat.duration(30_000))
        assertEquals("5:00", WalkFormat.duration(5 * 60 * 1_000L))
        assertEquals("59:59", WalkFormat.duration(59 * 60 * 1_000L + 59_000))
    }

    @Test
    fun `duration formats long walks as H_MM_SS`() {
        assertEquals("1:00:00", WalkFormat.duration(60 * 60 * 1_000L))
        assertEquals("1:23:45", WalkFormat.duration((1 * 3600 + 23 * 60 + 45) * 1_000L))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0:00", WalkFormat.duration(-1_000L))
    }

    // --- Metric distance --------------------------------------------------

    @Test
    fun `metric distance below 100m uses meters`() {
        assertEquals("0 m", WalkFormat.distance(0.0, metric))
        assertEquals("25 m", WalkFormat.distance(25.3, metric))
        assertEquals("99 m", WalkFormat.distance(99.4, metric))
    }

    @Test
    fun `metric distance at or above 100m uses kilometers`() {
        assertEquals("0.10 km", WalkFormat.distance(100.0, metric))
        assertEquals("1.23 km", WalkFormat.distance(1_234.0, metric))
        assertEquals("12.34 km", WalkFormat.distance(12_340.0, metric))
    }

    @Test
    fun `metric distance very large value still renders as km`() {
        // 1000 km — sanity check; nothing weird happens at large magnitudes.
        assertEquals("1000.00 km", WalkFormat.distance(1_000_000.0, metric))
    }

    // --- Imperial distance ------------------------------------------------

    @Test
    fun `imperial distance at or above 0_1 mi uses miles`() {
        // 1 km ≈ 0.621 mi → "0.62 mi"
        assertEquals("0.62 mi", WalkFormat.distance(1_000.0, imperial))
        // 1 mi ≈ 1609 m → "1.00 mi"
        assertEquals("1.00 mi", WalkFormat.distance(1_609.34, imperial))
        // 5 mi ≈ 8047 m → "5.00 mi"
        assertEquals("5.00 mi", WalkFormat.distance(8_046.72, imperial))
    }

    @Test
    fun `imperial distance below 0_1 mi falls back to feet`() {
        // 0 m → 0 ft
        assertEquals("0 ft", WalkFormat.distance(0.0, imperial))
        // 50 m ≈ 164 ft → "164 ft"
        assertEquals("164 ft", WalkFormat.distance(50.0, imperial))
        // 100 m ≈ 0.062 mi (< 0.1) → 328 ft
        assertEquals("328 ft", WalkFormat.distance(100.0, imperial))
    }

    @Test
    fun `imperial distance boundary at 0_1 mi crosses to miles`() {
        // 0.1 mi = 160.93 m. At exactly that threshold, mi branch.
        // Use a value slightly above to avoid rounding-direction games.
        val justAbove = 161.0 // ~0.1000 mi
        val label = WalkFormat.distance(justAbove, imperial)
        assertEquals("0.10 mi", label)
    }

    // --- Metric pace -------------------------------------------------------

    @Test
    fun `metric pace formats seconds-per-km as M_SS per km`() {
        assertEquals("10:00 /km", WalkFormat.pace(600.0, metric))
        assertEquals("6:30 /km", WalkFormat.pace(6 * 60.0 + 30.0, metric))
    }

    @Test
    fun `metric pace returns em-dash for null`() {
        assertEquals("—", WalkFormat.pace(null, metric))
    }

    // --- Imperial pace -----------------------------------------------------

    @Test
    fun `imperial pace converts seconds-per-km to per-mile`() {
        // 600 s/km → 600 / 0.621371 ≈ 965.6 s/mi → 16:06 /mi.
        assertEquals("16:06 /mi", WalkFormat.pace(600.0, imperial))
        // 6:30 /km (390 s/km) → 390 / 0.621371 ≈ 627.7 s/mi → 10:28 /mi.
        assertEquals("10:28 /mi", WalkFormat.pace(390.0, imperial))
    }

    @Test
    fun `imperial pace returns em-dash for null`() {
        assertEquals("—", WalkFormat.pace(null, imperial))
    }

    // --- Stage 4-B: typed distance label (goshuin seal center text) -------

    @Test
    fun `metric distanceLabel below 100m returns meter value`() {
        assertEquals(DistanceLabel("0", "m"), WalkFormat.distanceLabel(0.0, metric))
        assertEquals(DistanceLabel("25", "m"), WalkFormat.distanceLabel(25.3, metric))
        assertEquals(DistanceLabel("99", "m"), WalkFormat.distanceLabel(99.4, metric))
    }

    @Test
    fun `metric distanceLabel at 100m boundary crosses to km`() {
        assertEquals(DistanceLabel("0.10", "km"), WalkFormat.distanceLabel(100.0, metric))
    }

    @Test
    fun `metric distanceLabel at or above 100m uses two-decimal km`() {
        assertEquals(DistanceLabel("1.23", "km"), WalkFormat.distanceLabel(1_234.0, metric))
        assertEquals(DistanceLabel("12.34", "km"), WalkFormat.distanceLabel(12_340.0, metric))
    }

    @Test
    fun `metric distanceLabel 99_9 rounds up to 100m meter unit`() {
        // Rounding edge: 99.9 rounds to 100, but 99.9 < 100.0 so the
        // branch stays in meters. Result: "100 m" (unit stays m, value
        // is the round).
        assertEquals(DistanceLabel("100", "m"), WalkFormat.distanceLabel(99.9, metric))
    }

    @Test
    fun `imperial distanceLabel splits value and mi unit`() {
        assertEquals(DistanceLabel("0.62", "mi"), WalkFormat.distanceLabel(1_000.0, imperial))
    }

    @Test
    fun `imperial distanceLabel returns ft unit below 0_1 mi`() {
        assertEquals(DistanceLabel("164", "ft"), WalkFormat.distanceLabel(50.0, imperial))
    }

    // --- Altitude ----------------------------------------------------------

    @Test
    fun `metric altitude rounds meters`() {
        assertEquals("0 m", WalkFormat.altitude(0.0, metric))
        assertEquals("123 m", WalkFormat.altitude(123.4, metric))
        assertEquals("124 m", WalkFormat.altitude(123.6, metric))
    }

    @Test
    fun `imperial altitude converts meters to feet`() {
        // 0 m → 0 ft.
        assertEquals("0 ft", WalkFormat.altitude(0.0, imperial))
        // 100 m ≈ 328 ft.
        assertEquals("328 ft", WalkFormat.altitude(100.0, imperial))
        // 1000 m ≈ 3281 ft.
        assertEquals("3281 ft", WalkFormat.altitude(1_000.0, imperial))
    }

    // --- Temperature -------------------------------------------------------

    @Test
    fun `metric temperature renders celsius`() {
        assertEquals("0°C", WalkFormat.temperature(0.0, metric))
        assertEquals("21°C", WalkFormat.temperature(21.4, metric))
        assertEquals("-5°C", WalkFormat.temperature(-5.0, metric))
    }

    @Test
    fun `imperial temperature converts celsius to fahrenheit`() {
        // 0°C = 32°F.
        assertEquals("32°F", WalkFormat.temperature(0.0, imperial))
        // 100°C = 212°F.
        assertEquals("212°F", WalkFormat.temperature(100.0, imperial))
        // 21°C ≈ 69.8°F → 70°F.
        assertEquals("70°F", WalkFormat.temperature(21.0, imperial))
        // -40°C = -40°F (rare exact crossover).
        assertEquals("-40°F", WalkFormat.temperature(-40.0, imperial))
    }
}
