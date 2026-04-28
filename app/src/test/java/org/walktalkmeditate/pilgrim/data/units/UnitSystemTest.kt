// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit (no Robolectric needed — `UnitSystem` does not call
 * `android.util.Log`). Mirrors `AppearanceMode` / `ZodiacSystem`
 * style.
 */
class UnitSystemTest {

    @Test
    fun `default is Metric`() {
        assertEquals(UnitSystem.Metric, UnitSystem.DEFAULT)
    }

    @Test
    fun `storageValue is iOS-faithful UnitLength symbol`() {
        assertEquals("kilometers", UnitSystem.Metric.storageValue())
        assertEquals("miles", UnitSystem.Imperial.storageValue())
    }

    @Test
    fun `fromStorageValue round-trips through every enum value`() {
        UnitSystem.entries.forEach { value ->
            assertEquals(value, UnitSystem.fromStorageValue(value.storageValue()))
        }
    }

    @Test
    fun `fromStorageValue null falls back to default`() {
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromStorageValue(null))
    }

    @Test
    fun `fromStorageValue unknown string falls back to default`() {
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromStorageValue("nautical-miles"))
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromStorageValue(""))
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromStorageValue("KILOMETERS")) // case-sensitive
        assertEquals(UnitSystem.DEFAULT, UnitSystem.fromStorageValue("metric")) // not a UnitLength symbol
    }
}
