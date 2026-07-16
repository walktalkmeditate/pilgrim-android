// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanternSceneryTest {

    @Test
    fun `lantern lights from five pm through the small hours`() {
        assertTrue(walkMetTheDark(17))
        assertTrue(walkMetTheDark(23))
        assertTrue(walkMetTheDark(0))
        // 05:59 carries hour 5 — still inside the dark window.
        assertTrue(walkMetTheDark(5))
    }

    @Test
    fun `lantern stays dark through daylight hours`() {
        // 06:00 is the first daylight hour; 16:59 the last.
        assertFalse(walkMetTheDark(6))
        assertFalse(walkMetTheDark(12))
        assertFalse(walkMetTheDark(16))
    }
}
