// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

class CelestialSnapshotTest {

    private fun snapshot(
        system: ZodiacSystem,
        moonTropical: ZodiacSign? = ZodiacSign.Cancer,
        moonSidereal: ZodiacSign? = ZodiacSign.Gemini,
        includeMoon: Boolean = true,
    ): CelestialSnapshot {
        val moon = if (includeMoon) {
            PlanetaryPosition(
                planet = Planet.Moon,
                longitude = 95.0,
                tropical = ZodiacPosition(moonTropical ?: ZodiacSign.Cancer, 5.0),
                sidereal = ZodiacPosition(moonSidereal ?: ZodiacSign.Gemini, 5.0),
                isRetrograde = false,
                isIngress = false,
            )
        } else null
        return CelestialSnapshot(
            positions = listOfNotNull(moon),
            planetaryHour = PlanetaryHour(planet = Planet.Mars, dayRuler = Planet.Sun),
            elementBalance = ElementBalance(counts = emptyMap(), dominant = null),
            system = system,
            seasonalMarker = null,
        )
    }

    @Test
    fun `moonZodiacSymbol returns tropical sign for tropical system`() {
        val cs = snapshot(system = ZodiacSystem.Tropical)
        assertEquals(ZodiacSign.Cancer.symbol, cs.moonZodiacSymbol())
    }

    @Test
    fun `moonZodiacSymbol returns sidereal sign for sidereal system`() {
        val cs = snapshot(system = ZodiacSystem.Sidereal)
        assertEquals(ZodiacSign.Gemini.symbol, cs.moonZodiacSymbol())
    }

    @Test
    fun `moonZodiacSymbol returns null when moon position absent`() {
        val cs = snapshot(system = ZodiacSystem.Tropical, includeMoon = false)
        assertNull(cs.moonZodiacSymbol())
    }
}
