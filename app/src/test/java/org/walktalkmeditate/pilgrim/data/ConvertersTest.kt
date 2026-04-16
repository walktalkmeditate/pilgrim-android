// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `WalkEventType round trips for every known variant`() {
        for (variant in WalkEventType.entries) {
            val name = converters.walkEventTypeToString(variant)
            assertEquals(variant, converters.stringToWalkEventType(name))
        }
    }

    @Test
    fun `unknown WalkEventType name falls back to PAUSED instead of throwing`() {
        val decoded = converters.stringToWalkEventType("SOME_FUTURE_EVENT_TYPE")
        assertEquals(WalkEventType.PAUSED, decoded)
    }

    @Test
    fun `ActivityType round trips for every known variant`() {
        for (variant in ActivityType.entries) {
            val name = converters.activityTypeToString(variant)
            assertEquals(variant, converters.stringToActivityType(name))
        }
    }

    @Test
    fun `unknown ActivityType name falls back to WALKING instead of throwing`() {
        val decoded = converters.stringToActivityType("REST_OF_TIME")
        assertEquals(ActivityType.WALKING, decoded)
    }
}
