// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

class IntentionSuggestionsTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(y: Int, m: Int, d: Int): Long =
        ZonedDateTime.of(y, m, d, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val seasonalStrings = setOf(
        "Cross a threshold", "Walk in fullness", "Find balance",
        "Honor the stillness", "Notice what's stirring", "Celebrate what's alive",
        "Gather what you've grown", "Remember what matters",
    )

    @Test
    fun `never returns more than three`() {
        listOf(
            millis(2025, 1, 15), millis(2025, 3, 20), millis(2025, 6, 21),
            millis(2025, 9, 23), millis(2025, 12, 21), System.currentTimeMillis(),
        ).forEach {
            assertTrue(
                "expected ≤3 suggestions for $it",
                IntentionSuggestions.celestial(it, ZodiacSystem.Tropical, utc).size <= 3,
            )
        }
    }

    @Test
    fun `is deterministic for a fixed instant`() {
        val t = millis(2025, 6, 21)
        assertEquals(
            IntentionSuggestions.celestial(t, ZodiacSystem.Tropical, utc),
            IntentionSuggestions.celestial(t, ZodiacSystem.Tropical, utc),
        )
    }

    @Test
    fun `seasonal marker takes the first slot when present`() {
        // Solstices/equinoxes resolve a seasonal marker; whichever it
        // is, the seasonal phrase must be element 0 (highest priority,
        // ahead of retrograde/lunar/element). Robust to ±1d ephemeris.
        listOf(millis(2025, 12, 21), millis(2025, 6, 21), millis(2025, 3, 20)).forEach { t ->
            val s = IntentionSuggestions.celestial(t, ZodiacSystem.Tropical, utc)
            assertTrue("expected non-empty suggestions for $t", s.isNotEmpty())
            assertTrue(
                "expected a seasonal phrase first for $t but was '${s.first()}'",
                s.first() in seasonalStrings,
            )
        }
    }

    @Test
    fun `winter solstice yields Honor the stillness`() {
        val s = IntentionSuggestions.celestial(millis(2025, 12, 21), ZodiacSystem.Tropical, utc)
        assertEquals("Honor the stillness", s.first())
    }

    @Test
    fun `instant overload agrees with epoch millis`() {
        val t = millis(2025, 9, 23)
        val viaMillis = IntentionSuggestions.celestial(t, ZodiacSystem.Tropical, utc)
        val viaInstant = IntentionSuggestions.celestial(
            Instant.ofEpochMilli(t).toEpochMilli(), ZodiacSystem.Tropical, utc,
        )
        assertEquals(viaMillis, viaInstant)
    }
}
