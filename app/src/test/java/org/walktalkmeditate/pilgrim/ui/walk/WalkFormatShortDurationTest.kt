// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkFormatShortDurationTest {

    @Test
    fun `zero or negative renders em-dash`() {
        assertEquals("—", WalkFormat.shortDuration(0L))
        assertEquals("—", WalkFormat.shortDuration(-1_000L))
    }

    @Test
    fun `under one minute renders M_SS`() {
        assertEquals("0:30", WalkFormat.shortDuration(30_000L))
        assertEquals("0:01", WalkFormat.shortDuration(1_000L))
    }

    @Test
    fun `under one hour renders M_SS`() {
        assertEquals("1:30", WalkFormat.shortDuration(90_000L))
        assertEquals("59:59", WalkFormat.shortDuration(59 * 60 * 1_000L + 59_000L))
    }

    @Test
    fun `at one hour switches to H_MM`() {
        assertEquals("1:00", WalkFormat.shortDuration(60 * 60 * 1_000L))
        // 65 minutes = 1h05m, NOT "65:00"
        assertEquals("1:05", WalkFormat.shortDuration(65 * 60 * 1_000L))
    }

    @Test
    fun `multiple hours render as H_MM`() {
        assertEquals("2:05", WalkFormat.shortDuration(125 * 60 * 1_000L))
        assertEquals("12:34", WalkFormat.shortDuration((12 * 60 + 34) * 60 * 1_000L))
    }
}
