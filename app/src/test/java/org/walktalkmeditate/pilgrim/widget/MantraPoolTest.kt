// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MantraPoolTest {

    private val mantras = listOf(
        "Walk well.",
        "Every step is enough.",
        "Begin where you are.",
        "Slow is a speed.",
        "Breathe with your feet.",
        "Presence, step by step.",
        "The path is the way.",
        "Nowhere to arrive.",
        "Solvitur ambulando.",
        "One step is plenty.",
    ).joinToString(MantraPool.DELIMITER)

    @Test
    fun `phraseFor returns the same phrase for the same dayOfYear`() {
        val date = LocalDate.of(2026, 5, 1)
        val a = MantraPool.phraseFor(date, mantras)
        val b = MantraPool.phraseFor(date, mantras)
        assertEquals(a, b)
    }

    @Test
    fun `phraseFor wraps modulo phrase count over a full year`() {
        val seen = mutableSetOf<String>()
        var date = LocalDate.of(2026, 1, 1)
        for (i in 1..366) {
            seen += MantraPool.phraseFor(date, mantras)
            date = date.plusDays(1)
        }
        // 10 unique phrases across 366 days.
        assertEquals(10, seen.size)
    }

    @Test
    fun `phraseFor on day 1 vs day 2 returns different phrases`() {
        val day1 = MantraPool.phraseFor(LocalDate.of(2026, 1, 1), mantras)
        val day2 = MantraPool.phraseFor(LocalDate.of(2026, 1, 2), mantras)
        assertNotEquals(day1, day2)
    }

    @Test
    fun `phraseFor on day N and day N+phraseCount returns the same phrase`() {
        val n = LocalDate.of(2026, 3, 15)
        // Add exactly 10 days = 1 cycle through the 10-phrase pool.
        val cycled = MantraPool.phraseFor(n.plusDays(10), mantras)
        assertEquals(MantraPool.phraseFor(n, mantras), cycled)
    }

    @Test
    fun `empty allMantrasJoined returns empty string`() {
        assertEquals("", MantraPool.phraseFor(LocalDate.now(), ""))
    }

    @Test
    fun `whitespace-only allMantrasJoined returns empty string`() {
        assertEquals("", MantraPool.phraseFor(LocalDate.now(), "   "))
        assertEquals("", MantraPool.phraseFor(LocalDate.now(), "|||"))
    }

    @Test
    fun `single-phrase input returns that phrase for every date`() {
        val onePhrase = "Walk well."
        val seen = mutableSetOf<String>()
        var date = LocalDate.of(2026, 1, 1)
        for (i in 1..30) {
            seen += MantraPool.phraseFor(date, onePhrase)
            date = date.plusDays(1)
        }
        assertEquals(setOf("Walk well."), seen)
    }
}
