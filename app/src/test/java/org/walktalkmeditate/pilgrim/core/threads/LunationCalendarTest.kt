// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc

/**
 * Ported from `Pilgrim/Models/Threads/LunationCalendar.swift` (parity
 * spec `docs/parity/2026-08-26-threads-senses-port.md`). Synodic-month
 * windows minted ONLY as epoch + index×length (never `start + length`,
 * which drifts by a ulp and could split `lunation(n).end` from
 * `lunation(n+1).start`).
 */
class LunationCalendarTest {

    // --- boundary exactness ---------------------------------------------------

    @Test fun `lunation n end equals lunation n+1 start exactly, across a wide index range`() {
        for (n in -5..40) {
            assertEquals(
                "index $n: end must equal the next lunation's start bit-for-bit",
                LunationCalendar.lunation(n + 1).start,
                LunationCalendar.lunation(n).end,
            )
        }
    }

    @Test fun `fullMoon is the window midpoint`() {
        val lunation = LunationCalendar.lunation(3)
        val length = Duration.between(lunation.start, lunation.end)
        val expectedFullMoon = lunation.start.plusNanos(length.toNanos() / 2)
        // Nanosecond-level tolerance: fullMoon is minted independently as
        // start + length/2 (not derived from `length` computed above), so
        // an exact equality assertion would just be testing arithmetic
        // identity twice — compare within 1 microsecond instead.
        val deltaNanos = Duration.between(expectedFullMoon, lunation.fullMoon).abs().toNanos()
        assertTrue("expected fullMoon within 1us of the midpoint, got ${deltaNanos}ns off", deltaNanos <= 1_000L)
    }

    @Test fun `lunation index is stable — index field round-trips`() {
        assertEquals(7, LunationCalendar.lunation(7).index)
        assertEquals(-2, LunationCalendar.lunation(-2).index)
    }

    // --- lunation(containing:) correction guards --------------------------------

    @Test fun `a date exactly at a lunation start belongs to that lunation, never the previous one`() {
        for (n in -3..10) {
            val start = LunationCalendar.lunation(n).start
            assertEquals("index $n at its own start", n, LunationCalendar.lunationContaining(start).index)
        }
    }

    @Test fun `a date one nanosecond before a lunation end still belongs to that lunation`() {
        for (n in -3..10) {
            val justBeforeEnd = LunationCalendar.lunation(n).end.minusNanos(1)
            assertEquals("index $n just before its end", n, LunationCalendar.lunationContaining(justBeforeEnd).index)
        }
    }

    @Test fun `a date exactly at a lunation end belongs to the NEXT lunation (half-open)`() {
        for (n in -3..10) {
            val end = LunationCalendar.lunation(n).end
            assertEquals("index $n at its own end", n + 1, LunationCalendar.lunationContaining(end).index)
        }
    }

    @Test fun `a date mid-lunation resolves to that lunation`() {
        val lunation = LunationCalendar.lunation(12)
        val mid = lunation.fullMoon
        assertEquals(12, LunationCalendar.lunationContaining(mid).index)
    }

    // --- mostRecentClosed --------------------------------------------------------

    @Test fun `mostRecentClosed is containing index minus one, never the open lunation itself`() {
        val now = LunationCalendar.lunation(20).fullMoon
        val closed = LunationCalendar.mostRecentClosed(asOf = now)
        assertEquals(19, closed.index)
        assertNotEquals(LunationCalendar.lunationContaining(now).index, closed.index)
    }

    @Test fun `mostRecentClosed just after a lunation boundary reports the lunation that just closed`() {
        val boundary = LunationCalendar.lunation(8).end
        val closed = LunationCalendar.mostRecentClosed(asOf = boundary)
        assertEquals(8, closed.index)
    }

    // --- the 12-name month-moon table, verbatim ---------------------------------

    private fun lunationWithFullMoon(instant: Instant): Lunation =
        Lunation(index = 0, start = instant.minusSeconds(1), end = instant.plusSeconds(1), fullMoon = instant)

    @Test fun `month-moon names are verbatim including Hunter's Moon apostrophe and Corn Moon`() {
        val utc = ZoneId.of("UTC")
        val expected = listOf(
            1 to "Wolf Moon", 2 to "Snow Moon", 3 to "Worm Moon", 4 to "Pink Moon",
            5 to "Flower Moon", 6 to "Strawberry Moon", 7 to "Buck Moon", 8 to "Sturgeon Moon",
            9 to "Corn Moon", 10 to "Hunter's Moon", 11 to "Beaver Moon", 12 to "Cold Moon",
        )
        for ((month, name) in expected) {
            val instant = Instant.parse("2026-%02d-15T12:00:00Z".format(month))
            val lunation = lunationWithFullMoon(instant)
            assertEquals("month $month", name, LunationCalendar.moonName(lunation, utc))
        }
    }

    @Test fun `September is Corn Moon, never the folk-almanac Harvest Moon`() {
        val lunation = lunationWithFullMoon(Instant.parse("2026-09-10T00:00:00Z"))
        assertEquals("Corn Moon", LunationCalendar.moonName(lunation, ZoneId.of("UTC")))
    }

    @Test fun `October keeps the apostrophe in Hunter's Moon`() {
        val lunation = lunationWithFullMoon(Instant.parse("2026-10-10T00:00:00Z"))
        assertEquals("Hunter's Moon", LunationCalendar.moonName(lunation, ZoneId.of("UTC")))
    }

    // --- device-local timezone naming, not UTC ----------------------------------

    @Test fun `moonName resolves the calendar month in the GIVEN timezone, not UTC`() {
        // 2026-01-01T02:00:00Z is Jan 1 in UTC, but Dec 31 2025 18:00 in
        // America/Los_Angeles (UTC-8) — the same instant must honestly
        // report DIFFERENT names in the two zones.
        val instant = Instant.parse("2026-01-01T02:00:00Z")
        val lunation = lunationWithFullMoon(instant)

        val utcName = LunationCalendar.moonName(lunation, ZoneId.of("UTC"))
        val laName = LunationCalendar.moonName(lunation, ZoneId.of("America/Los_Angeles"))

        assertEquals("Wolf Moon", utcName)
        assertEquals("Cold Moon", laName)
    }

    @Test fun `moonName defaults to the system-default zone when none is given`() {
        val lunation = lunationWithFullMoon(Instant.parse("2026-06-15T12:00:00Z"))
        assertEquals(LunationCalendar.moonName(lunation, ZoneId.systemDefault()), LunationCalendar.moonName(lunation))
    }

    // --- shared-constant invariant -----------------------------------------------

    @Test fun `lunation length is derived from MoonCalc SYNODIC_DAYS, not redeclared`() {
        val lengthSeconds = Duration.between(LunationCalendar.lunation(0).start, LunationCalendar.lunation(0).end)
            .let { it.seconds + it.nano / 1_000_000_000.0 }
        assertEquals(MoonCalc.SYNODIC_DAYS * 86_400.0, lengthSeconds, 1e-6)
    }

    @Test fun `lunation 0 starts exactly at MoonCalc EPOCH`() {
        assertEquals(MoonCalc.EPOCH, LunationCalendar.lunation(0).start)
    }
}
