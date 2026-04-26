// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkModeTest {

    @Test
    fun `isAvailable true only for Wander`() {
        assertTrue(WalkMode.Wander.isAvailable)
        assertFalse(WalkMode.Together.isAvailable)
        assertFalse(WalkMode.Seek.isAvailable)
    }

    @Test
    fun `enum has exactly three modes`() {
        assertEquals(3, WalkMode.entries.size)
    }
}
