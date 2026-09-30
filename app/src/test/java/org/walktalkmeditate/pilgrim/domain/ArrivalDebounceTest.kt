// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports iOS `UnitTests/Seek/ArrivalDebounceTests.swift@7c200bf` test for
 * test, then pins the Android-only null accuracy (a fix with no accuracy,
 * which iOS never has) and the count's behaviour past the threshold.
 */
class ArrivalDebounceTest {

    private fun debounce() = ArrivalDebounce(requiredFixes = 3, accuracyMeters = 50.0)

    @Test
    fun `three consecutive inside fixes arrive`() {
        val d = debounce()
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
        assertTrue(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
    }

    @Test
    fun `an outside fix resets the count`() {
        val d = debounce()
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        assertFalse(d.register(distance = 40.0, radius = 30.0, accuracy = 5.0))
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
        assertTrue(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
    }

    @Test
    fun `poor accuracy neither advances nor resets`() {
        val d = debounce()
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 120.0))
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = -1.0))
        assertTrue(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
    }

    @Test
    fun `a fix with no accuracy neither advances nor resets`() {
        val d = debounce()
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        d.register(distance = 10.0, radius = 30.0, accuracy = 5.0)
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = null))
        assertEquals(2, d.consecutiveInside)
        assertTrue(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
    }

    @Test
    fun `the radius and the accuracy gate are both inclusive`() {
        val d = ArrivalDebounce(requiredFixes = 1, accuracyMeters = 50.0)
        assertTrue(d.register(distance = 30.0, radius = 30.0, accuracy = 50.0))
    }

    @Test
    fun `the count holds true past the threshold until reset`() {
        val d = debounce()
        repeat(3) { d.register(distance = 10.0, radius = 30.0, accuracy = 5.0) }
        assertTrue(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
        d.reset()
        assertEquals(0, d.consecutiveInside)
        assertFalse(d.register(distance = 10.0, radius = 30.0, accuracy = 5.0))
    }
}
