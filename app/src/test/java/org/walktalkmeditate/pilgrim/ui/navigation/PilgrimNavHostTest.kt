// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin-tests for the bottom-nav membership rule + Routes.PATH constant.
 * Full Compose-Nav integration tests deferred to a later parity-test
 * stage (manual QA per spec section 14 covers the live behavior).
 */
class PilgrimNavHostTest {

    @Test
    fun `Routes_PATH constant value is path`() {
        assertEquals("path", Routes.PATH)
    }

    @Test
    fun `TAB_ROUTES contains exactly PATH HOME SETTINGS`() {
        val expected = setOf(Routes.PATH, Routes.HOME, Routes.SETTINGS)
        assertEquals(expected, TAB_ROUTES)
    }

    @Test
    fun `tab routes include PATH`() {
        assertTrue(Routes.PATH in TAB_ROUTES)
    }

    @Test
    fun `non-tab routes are excluded from TAB_ROUTES`() {
        // Spot-check immersive + pushed routes.
        assertFalse(Routes.ACTIVE_WALK in TAB_ROUTES)
        assertFalse(Routes.MEDITATION in TAB_ROUTES)
        assertFalse(Routes.GOSHUIN in TAB_ROUTES)
    }
}
