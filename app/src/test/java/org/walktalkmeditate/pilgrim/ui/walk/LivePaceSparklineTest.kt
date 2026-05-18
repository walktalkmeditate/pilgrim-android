// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports of iOS `ActiveWalkViewModel.paceHistory` build
 * (`ActiveWalkViewModel.swift:205-210`) and `LivePaceSparklineView`
 * point math (`ActiveWalkSubviews.swift:29-55`).
 */
class LivePaceSparklineTest {

    // --- livePaceHistory: iOS paceMinKm derivation + 60-cap ---

    @Test
    fun `speed above threshold maps to pace min per km`() {
        // 2 m/s → (1000/2)/60 = 8.333… min/km
        val out = livePaceHistory(listOf(2.0f))
        assertEquals(1, out.size)
        assertEquals(8.3333, out[0], 0.0001)
    }

    @Test
    fun `speed at or below 0_3 threshold maps to zero`() {
        assertEquals(listOf(0.0, 0.0), livePaceHistory(listOf(0.3f, 0.1f)))
    }

    @Test
    fun `null speed maps to zero`() {
        assertEquals(listOf(0.0), livePaceHistory(listOf<Float?>(null)))
    }

    @Test
    fun `negative speed clamps to zero pace`() {
        assertEquals(listOf(0.0), livePaceHistory(listOf(-5.0f)))
    }

    @Test
    fun `history caps at the most recent 60 entries`() {
        val speeds = (1..100).map { 2.0f }
        val out = livePaceHistory(speeds)
        assertEquals(60, out.size)
    }

    @Test
    fun `empty input yields empty history`() {
        assertTrue(livePaceHistory(emptyList()).isEmpty())
    }

    // --- livePaceSparklineOffsets: iOS LivePaceSparklineView path ---

    @Test
    fun `fewer than two positive values yields no offsets`() {
        assertTrue(livePaceSparklineOffsets(listOf(0.0, 5.0), 100f, 24f).isEmpty())
    }

    @Test
    fun `offsets span full width with first at x0 and last at xWidth`() {
        val pts = livePaceSparklineOffsets(listOf(4.0, 8.0, 6.0), 100f, 24f)
        assertEquals(3, pts.size)
        assertEquals(0f, pts.first().x, 0.001f)
        assertEquals(100f, pts.last().x, 0.001f)
    }

    @Test
    fun `min value maps to bottom and max value maps to top of height`() {
        // values 4..8: min=4 → y=h(1-0)=h ; max=8 → y=h(1-1)=0
        val pts = livePaceSparklineOffsets(listOf(4.0, 8.0), 100f, 24f)
        // only 2 positive → still rendered (count > 1)
        assertEquals(24f, pts.first().y, 0.001f) // min at bottom
        assertEquals(0f, pts[1].y, 0.001f)       // max at top
    }

    @Test
    fun `flat values use the 0_5 range floor and do not divide by zero`() {
        val pts = livePaceSparklineOffsets(listOf(5.0, 5.0, 5.0), 90f, 30f)
        assertEquals(3, pts.size)
        // (v-min)/range = 0/0.5 = 0 → y = h*(1-0) = h for all
        pts.forEach { assertEquals(30f, it.y, 0.001f) }
    }
}
