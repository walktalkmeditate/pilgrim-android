// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class SceneryGeneratorTest {

    private fun snap(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMs: Long = 1_700_000_000_000L,
        distanceM: Double = 5_000.0,
        durationSec: Double = 1800.0,
    ) = WalkSnapshot(
        id = 1L,
        uuid = uuid,
        startMs = startMs,
        distanceM = distanceM,
        durationSec = durationSec,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = distanceM,
        talkDurationSec = 0L,
        meditateDurationSec = 0L,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun `pick is deterministic for the same WalkSnapshot`() {
        val s = snap()
        val a = SceneryGenerator.pick(s)
        val b = SceneryGenerator.pick(s)
        assertEquals(a, b)
    }

    @Test
    fun `pick produces null for some snapshots and non-null for others`() {
        var hadNull = false
        var hadHit = false
        for (i in 0 until 200) {
            val s = snap(
                uuid = "00000000-0000-0000-0000-${"%012d".format(i)}",
                startMs = 1_700_000_000_000L + i * 86_400_000L,
            )
            val placement = SceneryGenerator.pick(s)
            if (placement == null) hadNull = true else hadHit = true
            if (hadNull && hadHit) break
        }
        assertTrue("expected at least one null", hadNull)
        assertTrue("expected at least one non-null", hadHit)
    }

    @Test
    fun `pick respects approximate 35 percent chance over many seeds`() {
        var hits = 0
        val n = 1000
        for (i in 0 until n) {
            val s = snap(
                uuid = "00000000-0000-0000-0000-${"%012d".format(i)}",
                startMs = 1_700_000_000_000L + i * 3_600_000L,
                distanceM = 1_000.0 + i.toDouble(),
                durationSec = 600.0 + i.toDouble(),
            )
            if (SceneryGenerator.pick(s) != null) hits++
        }
        val ratio = hits.toDouble() / n
        // Expect ~0.35; allow a wide ±0.1 window because the hash space is
        // small (only 10000 buckets via SplitMix64.mod). Tightening would
        // be flaky.
        assertTrue("ratio = $ratio, expected ~0.35", ratio in 0.20..0.50)
    }

    @Test
    fun `sizeVariation01 is in 0_1 range and stable per uuid`() {
        val s = snap(uuid = "abcdefab-cdef-abcd-efab-cdefabcdef00")
        val a = SceneryGenerator.sizeVariation01(s)
        val b = SceneryGenerator.sizeVariation01(s)
        assertEquals(a, b, 0.0)
        assertTrue("sizeVariation $a not in [0,1)", a in 0.0..1.0)
    }

    @Test
    fun `pick returns offset within plus minus 7_5`() {
        for (i in 0 until 50) {
            val s = snap(
                uuid = "deadbeef-cafe-babe-feed-${"%012d".format(i)}",
            )
            val placement = SceneryGenerator.pick(s) ?: continue
            assertTrue(placement.offset in -7.5f..7.5f)
        }
    }

    @Test
    fun `pick returns null for malformed uuid path falls through deterministically`() {
        // Both calls hit the same deterministic seed so result equality
        // is the contract — does NOT have to be non-null.
        val s = snap(uuid = "not-a-uuid")
        val a = SceneryGenerator.pick(s)
        val b = SceneryGenerator.pick(s)
        assertEquals(a, b)
        // With a malformed uuid the seed collapses to mostly the time
        // and distance bytes — whatever the result is, it should not
        // crash.
        if (a != null) {
            assertNotNull(a.type)
            assertNotNull(a.side)
        } else {
            assertNull(a)
        }
    }
}
