// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonCalcTest {

    @Test fun `known new moon 2024-05-08 has near-zero illumination`() {
        // New moon at 2024-05-08 03:22 UTC per timeanddate.com.
        // The simplified synodic-period epoch drifts over decades, so
        // the phase *name* at a given date isn't guaranteed to match
        // the astronomical reality — but the *illumination* curve is
        // symmetric across the cycle, so near-zero remains near-zero
        // whether age is interpreted as ~0 or ~synodic.
        val phase = MoonCalc.moonPhase(Instant.parse("2024-05-08T03:22:00Z"))
        assertTrue(
            "illumination should be < 0.05 near new moon, got ${phase.illumination}",
            phase.illumination < 0.05,
        )
    }

    @Test fun `known full moon 2024-06-22 has near-full illumination`() {
        // Full moon at 2024-06-22 01:08 UTC per timeanddate.com.
        val phase = MoonCalc.moonPhase(Instant.parse("2024-06-22T01:08:00Z"))
        assertTrue(
            "illumination should be > 0.95 near full moon, got ${phase.illumination}",
            phase.illumination > 0.95,
        )
    }

    @Test fun `phase name buckets match expected string per age bucket`() {
        // Synthetic test using offsets from epoch so we don't depend on
        // astronomical reality. bucketWidth = 29.5306 / 8 ≈ 3.6913.
        // We advance the epoch by 0.5 * bucketWidth (middle of each
        // bucket) and assert the name is the expected one.
        val epoch = Instant.parse("2000-01-06T18:14:00Z")
        val bucketDays = 29.530588770576 / 8.0
        val expected = listOf(
            "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
            "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent",
        )
        for (bucket in 0..7) {
            val offsetMillis = ((bucket + 0.5) * bucketDays * 86_400_000).toLong()
            val sample = epoch.plusMillis(offsetMillis)
            val name = MoonCalc.moonPhase(sample).name
            assertEquals("bucket $bucket", expected[bucket], name)
        }
    }

    @Test fun `age is within synodic period range`() {
        val instants = listOf(
            "2000-01-06T18:14:00Z", // epoch
            "2012-03-14T09:00:00Z",
            "2024-07-04T12:00:00Z",
            "2099-12-31T23:59:59Z",
            "1980-01-15T00:00:00Z",
        )
        val synodic = 29.530588770576
        for (iso in instants) {
            val phase = MoonCalc.moonPhase(Instant.parse(iso))
            assertTrue("age < 0: $iso", phase.ageInDays >= 0.0)
            assertTrue("age >= synodic: $iso", phase.ageInDays < synodic)
            assertTrue(
                "illumination outside [0,1]: $iso got ${phase.illumination}",
                phase.illumination in 0.0..1.0,
            )
        }
    }

    @Test fun `age advances by one-twenty-fourth day across one hour`() {
        val t0 = Instant.parse("2024-06-22T00:00:00Z")
        val t1 = t0.plus(Duration.ofHours(1))
        val a0 = MoonCalc.moonPhase(t0).ageInDays
        val a1 = MoonCalc.moonPhase(t1).ageInDays
        val delta = a1 - a0
        assertEquals(1.0 / 24.0, delta, 1e-6)
    }

    @Test fun `phase name is always one of the 8 canonical strings`() {
        val canonical = setOf(
            "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
            "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent",
        )
        val instants = listOf(
            "2024-01-01T00:00:00Z",
            "2024-03-15T06:00:00Z",
            "2024-05-08T03:22:00Z",
            "2024-06-22T01:08:00Z",
            "2024-10-01T12:00:00Z",
            "2025-02-14T00:00:00Z",
        )
        for (iso in instants) {
            val phase = MoonCalc.moonPhase(Instant.parse(iso))
            assertTrue(
                "unexpected phase name ${phase.name} for $iso",
                phase.name in canonical,
            )
        }
    }

    @Test fun `pre-epoch instants still produce valid phase`() {
        // Tests the raw % correction for negative dividend.
        val phase = MoonCalc.moonPhase(Instant.parse("1969-07-20T20:17:40Z"))
        assertTrue(phase.ageInDays >= 0.0)
        assertTrue(phase.ageInDays < 29.531)
        assertTrue(phase.illumination in 0.0..1.0)
    }
}
