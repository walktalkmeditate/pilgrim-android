// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-decision tests for the #43 grant ritual, mirroring iOS
 * PermissionRitualTests' `shouldPlayBell` cases.
 */
class PermissionRitualTest {

    @Test
    fun `bell plays when granted, sounds on, and not yet played`() {
        assertTrue(
            PermissionRitual.shouldPlayBell(
                granted = true,
                soundsEnabled = true,
                alreadyPlayed = false,
            ),
        )
    }

    @Test
    fun `no bell when not granted`() {
        assertFalse(
            PermissionRitual.shouldPlayBell(
                granted = false,
                soundsEnabled = true,
                alreadyPlayed = false,
            ),
        )
    }

    @Test
    fun `no bell when sounds disabled`() {
        assertFalse(
            PermissionRitual.shouldPlayBell(
                granted = true,
                soundsEnabled = false,
                alreadyPlayed = false,
            ),
        )
    }

    @Test
    fun `no bell when already played`() {
        assertFalse(
            PermissionRitual.shouldPlayBell(
                granted = true,
                soundsEnabled = true,
                alreadyPlayed = true,
            ),
        )
    }
}
