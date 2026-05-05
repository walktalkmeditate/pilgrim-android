// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkDotMathTest {

    @Test
    fun `dotSize at lower bound clamps to 8 dp`() {
        assertEquals(8f, WalkDotMath.dotSize(durationSec = 100.0), 0.01f)
        assertEquals(8f, WalkDotMath.dotSize(durationSec = 300.0), 0.01f)
    }

    @Test
    fun `dotSize at upper bound clamps to 22 dp`() {
        assertEquals(22f, WalkDotMath.dotSize(durationSec = 7200.0), 0.01f)
        assertEquals(22f, WalkDotMath.dotSize(durationSec = 9999.0), 0.01f)
    }

    @Test
    fun `dotSize linear in middle range`() {
        // 1-hour walk = midpoint, expect ~14.85 dp
        val mid = WalkDotMath.dotSize(durationSec = 3600.0)
        assertEquals(14.85f, mid, 0.5f)
    }

    @Test
    fun `dotOpacity newest is 1 oldest fades to 0_5`() {
        assertEquals(1.0f, WalkDotMath.dotOpacity(0, 5), 1e-4f)
        assertEquals(0.5f, WalkDotMath.dotOpacity(4, 5), 1e-4f)
    }

    @Test
    fun `dotOpacity single walk returns 1`() {
        assertEquals(1.0f, WalkDotMath.dotOpacity(0, 1), 1e-4f)
    }

    @Test
    fun `labelOpacity is dotOpacity times 0_7`() {
        assertEquals(0.7f, WalkDotMath.labelOpacity(0, 5), 1e-4f)
        assertEquals(0.35f, WalkDotMath.labelOpacity(4, 5), 1e-4f)
    }
}
