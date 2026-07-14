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
    fun `unknown WalkEventType name falls back to UNKNOWN instead of throwing`() {
        val decoded = converters.stringToWalkEventType("SOME_FUTURE_EVENT_TYPE")
        assertEquals(WalkEventType.UNKNOWN, decoded)
    }

    @Test
    fun `seek WalkEventType names round trip through storage`() {
        assertEquals(
            WalkEventType.SEEK_MODE,
            converters.stringToWalkEventType(converters.walkEventTypeToString(WalkEventType.SEEK_MODE)),
        )
        assertEquals(
            WalkEventType.SEEK_ARRIVAL,
            converters.stringToWalkEventType(converters.walkEventTypeToString(WalkEventType.SEEK_ARRIVAL)),
        )
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
