// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class CairnSceneryTest {

    private val size = Size(100f, 100f)

    @Test
    fun `stone count clamps to two through five`() {
        assertEquals(2, cairnStoneRects(size, 0).size)
        assertEquals(2, cairnStoneRects(size, 2).size)
        assertEquals(3, cairnStoneRects(size, 3).size)
        assertEquals(5, cairnStoneRects(size, 5).size)
        assertEquals(5, cairnStoneRects(size, 9).size)
    }

    @Test
    fun `stone widths ascend from narrow top to wide base`() {
        for (stones in 2..5) {
            val widths = cairnStoneRects(size, stones).map { it.width }
            assertEquals(widths.sorted(), widths)
            assertEquals(38f, widths.first(), 1e-3f)
            assertEquals(82f, widths.last(), 1e-3f)
        }
    }

    @Test
    fun `stone heights overlap the row by five percent`() {
        val rects = cairnStoneRects(size, 4)
        for (rect in rects) {
            assertEquals(100f / 4f * 1.05f, rect.height, 1e-3f)
        }
    }

    @Test
    fun `stones stack top-down one row apart`() {
        val rects = cairnStoneRects(size, 4)
        rects.forEachIndexed { index, rect ->
            assertEquals(index * 25f, rect.top, 1e-3f)
        }
    }

    @Test
    fun `non-base stones lean alternately and the base stands straight`() {
        val leans = cairnStoneRects(size, 5).map { it.center.x - 50f }
        assertEquals(5f, leans[0], 1e-3f)
        assertEquals(-6f, leans[1], 1e-3f)
        assertEquals(5f, leans[2], 1e-3f)
        assertEquals(-6f, leans[3], 1e-3f)
        assertEquals(0f, leans[4], 1e-3f)
    }

    @Test
    fun `two-stone stack still keeps its base straight`() {
        val leans = cairnStoneRects(size, 2).map { it.center.x - 50f }
        assertEquals(5f, leans[0], 1e-3f)
        assertEquals(0f, leans[1], 1e-3f)
    }

    @Test
    fun `winter cap appears only in december january february`() {
        val winterMonths = setOf(12, 1, 2)
        for (month in 1..12) {
            assertEquals("month $month", month in winterMonths, cairnHasWinterCap(month))
        }
    }
}
