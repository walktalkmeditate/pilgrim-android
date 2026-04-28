// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit (no Robolectric needed — `ZodiacSystem` does not call
 * `android.util.Log`). Mirrors `AppearanceMode` / `BreathRhythm` style.
 */
class ZodiacSystemTest {

    @Test
    fun `default is Tropical`() {
        assertEquals(ZodiacSystem.Tropical, ZodiacSystem.DEFAULT)
    }

    @Test
    fun `storageValue is iOS-faithful lowercase name`() {
        assertEquals("tropical", ZodiacSystem.Tropical.storageValue())
        assertEquals("sidereal", ZodiacSystem.Sidereal.storageValue())
    }

    @Test
    fun `fromStorageValue round-trips through every enum value`() {
        ZodiacSystem.entries.forEach { value ->
            assertEquals(value, ZodiacSystem.fromStorageValue(value.storageValue()))
        }
    }

    @Test
    fun `fromStorageValue null falls back to default`() {
        assertEquals(ZodiacSystem.DEFAULT, ZodiacSystem.fromStorageValue(null))
    }

    @Test
    fun `fromStorageValue unknown string falls back to default`() {
        assertEquals(ZodiacSystem.DEFAULT, ZodiacSystem.fromStorageValue("vedic"))
        assertEquals(ZodiacSystem.DEFAULT, ZodiacSystem.fromStorageValue(""))
        assertEquals(ZodiacSystem.DEFAULT, ZodiacSystem.fromStorageValue("TROPICAL")) // case-sensitive
    }
}
