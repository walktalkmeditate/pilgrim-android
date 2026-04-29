// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

class AltitudeCalculatorTest {

    @Test
    fun `empty samples returns zero ascent and descent`() {
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(emptyList())
        assertEquals(0.0, ascent, 0.0)
        assertEquals(0.0, descent, 0.0)
    }

    @Test
    fun `monotonic climb sums all positive deltas`() {
        val samples = listOf(altitude(0, 100.0), altitude(1000, 110.0), altitude(2000, 125.0))
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(25.0, ascent, 0.001)
        assertEquals(0.0, descent, 0.001)
    }

    @Test
    fun `monotonic descent sums all negative deltas as positive descent`() {
        val samples = listOf(altitude(0, 200.0), altitude(1000, 180.0), altitude(2000, 150.0))
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(0.0, ascent, 0.001)
        assertEquals(50.0, descent, 0.001)
    }

    @Test
    fun `mixed climb and descent partition correctly`() {
        val samples = listOf(
            altitude(0, 100.0),
            altitude(1000, 120.0),
            altitude(2000, 105.0),
            altitude(3000, 130.0),
        )
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(45.0, ascent, 0.001)
        assertEquals(15.0, descent, 0.001)
    }

    @Test
    fun `single sample returns zero`() {
        val samples = listOf(altitude(0, 100.0))
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(0.0, ascent, 0.001)
        assertEquals(0.0, descent, 0.001)
    }

    private fun altitude(timestamp: Long, meters: Double) = AltitudeSample(
        walkId = 1L, timestamp = timestamp, altitudeMeters = meters,
    )
}
