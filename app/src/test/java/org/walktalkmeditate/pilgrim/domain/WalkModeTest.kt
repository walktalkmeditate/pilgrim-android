// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `WalkModeTests.swift@7c200bf`'s model half. The subtitle,
 * button label, and quotes are string resources the slot's UI owns (U21).
 */
class WalkModeTest {

    @Test
    fun `Honor is the middle of the three modes, in iOS's order`() {
        assertEquals(listOf(WalkMode.Wander, WalkMode.Honor, WalkMode.Seek), WalkMode.entries)
    }

    @Test
    fun `every mode is available with the release flag on, as on iOS`() {
        WalkMode.entries.forEach { mode ->
            assertTrue("$mode", mode.isAvailable(honorEnabled = true))
        }
    }

    @Test
    fun `with the release flag off only the Honor slot is unavailable`() {
        assertTrue(WalkMode.Wander.isAvailable(honorEnabled = false))
        assertFalse(WalkMode.Honor.isAvailable(honorEnabled = false))
        assertTrue(WalkMode.Seek.isAvailable(honorEnabled = false))
    }

    @Test
    fun `the Honor wire value parses`() {
        assertEquals(WalkMode.Honor, WalkMode.fromWire(WalkMode.Honor.name))
        assertEquals(WalkMode.Honor, WalkMode.fromWire("Honor"))
    }

    @Test
    fun `the retired Together wire value and unknown values fall back to Wander`() {
        assertEquals(WalkMode.Wander, WalkMode.fromWire("Together"))
        assertEquals(WalkMode.Wander, WalkMode.fromWire("Pilgrimage"))
        assertEquals(WalkMode.Wander, WalkMode.fromWire(null))
    }
}
