// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCurvesTest {
    @Test fun `fast pace gives base width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 300.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w, 0f)
    }

    @Test fun `slow pace gives max width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 900.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(30f, w, 0f)
    }

    @Test fun `mid pace gives halfway width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 600.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertTrue("got $w", abs(w - 20f) < 0.001f)
    }

    @Test fun `very fast pace clamps to base`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 100.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w, 0f)
    }

    @Test fun `very slow pace clamps to max`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 2000.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(30f, w, 0f)
    }

    @Test fun `zero pace falls back to base`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 0.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w, 0f)
    }

    @Test fun `taper newest is 1`() {
        assertEquals(1f, taperFactor(index = 0, total = 10), 0f)
    }

    @Test fun `taper oldest is 0_6`() {
        val t = taperFactor(index = 9, total = 10)
        assertTrue("got $t", abs(t - 0.6f) < 0.001f)
    }

    @Test fun `taper single stroke is 1`() {
        assertEquals(1f, taperFactor(index = 0, total = 1), 0f)
    }

    @Test fun `opacity newest is 0_35`() {
        assertEquals(0.35f, segmentOpacity(index = 0, total = 10), 0f)
    }

    @Test fun `opacity oldest is 0_15`() {
        val o = segmentOpacity(index = 9, total = 10)
        assertTrue("got $o", abs(o - 0.15f) < 0.001f)
    }

    @Test fun `opacity single stroke is 0_35`() {
        assertEquals(0.35f, segmentOpacity(index = 0, total = 1), 0f)
    }
}
