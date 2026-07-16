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

    // iOS WalkDotView @ c1745e8: live dot frame is
    // `.frame(width: max(44, size * 3.5))`, archived is a fixed 44×44.

    @Test
    fun `dotBoxDp floors small live dots at the 44 dp tap target`() {
        assertEquals(44f, WalkDotMath.dotBoxDp(8f, isArchived = false), 1e-4f)
        assertEquals(44f, WalkDotMath.dotBoxDp(12f, isArchived = false), 1e-4f)
    }

    @Test
    fun `dotBoxDp scales large live dots at 3_5x`() {
        assertEquals(77f, WalkDotMath.dotBoxDp(22f, isArchived = false), 1e-4f)
        assertEquals(52.5f, WalkDotMath.dotBoxDp(15f, isArchived = false), 1e-4f)
    }

    @Test
    fun `dotBoxDp is a fixed 44 dp for archived rings`() {
        assertEquals(44f, WalkDotMath.dotBoxDp(8f, isArchived = true), 1e-4f)
        assertEquals(44f, WalkDotMath.dotBoxDp(22f, isArchived = true), 1e-4f)
    }

    @Test
    fun `geometry constants pin the iOS spec values`() {
        assertEquals(3.5f, WalkDotMath.HALO_SCALE, 1e-6f)
        assertEquals(44f, WalkDotMath.MIN_TOUCH_DP, 1e-6f)
        assertEquals(0.6f, WalkDotMath.ARCHIVED_RING_SCALE, 1e-6f)
    }
}
