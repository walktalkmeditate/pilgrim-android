// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonPhaseTest {

    @Test
    fun `isWaxing true at new moon (age 0)`() {
        val phase = MoonPhase(name = "New Moon", illumination = 0.0, ageInDays = 0.0)
        assertTrue(phase.isWaxing)
    }

    @Test
    fun `isWaxing true mid-waxing (age 7)`() {
        val phase = MoonPhase(name = "First Quarter", illumination = 0.5, ageInDays = 7.0)
        assertTrue(phase.isWaxing)
    }

    @Test
    fun `isWaxing true just before midpoint (age 14_7)`() {
        val phase = MoonPhase(name = "Full Moon", illumination = 0.99, ageInDays = 14.7)
        assertTrue(phase.isWaxing)
    }

    @Test
    fun `isWaxing false just past midpoint (age 14_8)`() {
        val phase = MoonPhase(name = "Full Moon", illumination = 0.99, ageInDays = 14.8)
        assertFalse(phase.isWaxing)
    }

    @Test
    fun `isWaxing false at last quarter (age 22)`() {
        val phase = MoonPhase(name = "Last Quarter", illumination = 0.5, ageInDays = 22.0)
        assertFalse(phase.isWaxing)
    }

    @Test
    fun `isWaxing false at end-of-cycle (age near 29)`() {
        val phase = MoonPhase(name = "Waning Crescent", illumination = 0.02, ageInDays = 29.0)
        assertFalse(phase.isWaxing)
    }
}
