// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        isSeek: Boolean = false,
        foundPlaces: Int = 0,
        threshold: WalkThreshold? = null,
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
        isSeek = isSeek,
        foundPlaces = foundPlaces,
        threshold = threshold,
    )

    /** 800 varied deterministic snapshots for lottery-distribution sweeps. */
    private fun lotterySweep(
        isSeek: Boolean = false,
        foundPlaces: Int = 0,
        threshold: WalkThreshold? = null,
    ): List<WalkSnapshot> = (0 until 800).map { i ->
        snap(
            uuid = "00000000-0000-0000-0000-" + i.toString().padStart(12, '0'),
            startMs = 1_700_000_000_000L + i * 3_600_000L,
            distanceM = 1_000.0 + i,
            durationSec = 600.0 + i,
            isSeek = isSeek,
            foundPlaces = foundPlaces,
            threshold = threshold,
        )
    }

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

    // --- Meaning outranks the lottery (iOS SceneryGeneratorTests@c1745e8) ---

    @Test
    fun `threshold walk always stands at a gate over many seeds`() {
        for (s in lotterySweep(threshold = WalkThreshold.Practice).take(50)) {
            assertEquals(SceneryType.Torii, SceneryGenerator.pick(s)?.type)
        }
    }

    @Test
    fun `gateKind shapes the gate`() {
        val practice = SceneryGenerator.pick(snap(threshold = WalkThreshold.Practice))
        assertEquals(WalkThreshold.Practice, practice?.gateKind)
        assertEquals("practice gates stand vermilion", "rust", practice?.tintTokenName)

        val seeking = SceneryGenerator.pick(snap(threshold = WalkThreshold.Seeking))
        assertEquals(WalkThreshold.Seeking, seeking?.gateKind)
        assertEquals("seeking gates stand weathered stone", "stone", seeking?.tintTokenName)
    }

    @Test
    fun `seek with found places always raises a cairn`() {
        for (s in lotterySweep(isSeek = true, foundPlaces = 2).take(50)) {
            assertEquals(SceneryType.Cairn, SceneryGenerator.pick(s)?.type)
        }
    }

    @Test
    fun `cairn stack grows with found places capped at five`() {
        assertEquals(3, SceneryGenerator.pick(snap(isSeek = true, foundPlaces = 1))?.stones)
        assertEquals(4, SceneryGenerator.pick(snap(isSeek = true, foundPlaces = 2))?.stones)
        assertEquals(5, SceneryGenerator.pick(snap(isSeek = true, foundPlaces = 3))?.stones)
        assertEquals(
            "the stack tops out at five stones",
            5,
            SceneryGenerator.pick(snap(isSeek = true, foundPlaces = 9))?.stones,
        )
    }

    @Test
    fun `threshold outranks cairn`() {
        val placement = SceneryGenerator.pick(
            snap(isSeek = true, foundPlaces = 1, threshold = WalkThreshold.Seeking),
        )
        assertEquals(
            "a gate marks the threshold even on a seek walk",
            SceneryType.Torii,
            placement?.type,
        )
    }

    @Test
    fun `seek without arrivals never raises a cairn`() {
        for (s in lotterySweep(isSeek = true, foundPlaces = 0).take(200)) {
            assertNotEquals(
                "no cairn without a found place",
                SceneryType.Cairn,
                SceneryGenerator.pick(s)?.type,
            )
        }
    }

    @Test
    fun `gate and cairn placements are deterministic`() {
        val gateWalk = snap(threshold = WalkThreshold.Seeking)
        assertEquals(SceneryGenerator.pick(gateWalk), SceneryGenerator.pick(gateWalk))

        val cairnWalk = snap(isSeek = true, foundPlaces = 4)
        assertEquals(SceneryGenerator.pick(cairnWalk), SceneryGenerator.pick(cairnWalk))
    }

    // --- The lottery itself ---

    @Test
    fun `the lottery never mints a torii or a cairn`() {
        for (s in lotterySweep()) {
            val placement = SceneryGenerator.pick(s) ?: continue
            assertNotEquals("the lottery must never mint a gate", SceneryType.Torii, placement.type)
            assertNotEquals("the lottery must never raise a cairn", SceneryType.Cairn, placement.type)
        }
    }

    @Test
    fun `drift lives in the retired gate band at about five percent of placements`() {
        var placements = 0
        var drifts = 0
        for (s in lotterySweep()) {
            val placement = SceneryGenerator.pick(s) ?: continue
            placements++
            if (placement.type == SceneryType.Drift) drifts++
        }
        assertTrue("the season's breath must appear in the lottery", drifts > 0)
        val fraction = drifts.toDouble() / placements
        // Drift owns 0.05 of the type roll → ~5% of placements. Fixed
        // fixture, wide window: exact-band correctness is pinned by the
        // legacy-generator comparison below.
        assertTrue("drift fraction $fraction should sit near 0.05", fraction in 0.01..0.12)
    }

    @Test
    fun `roughly a third of walks get scenery`() {
        val sweep = lotterySweep().take(600)
        val fraction = sweep.count { SceneryGenerator.pick(it) != null }.toDouble() / sweep.size
        assertTrue("fraction = $fraction", fraction > 0.25)
        assertTrue("fraction = $fraction", fraction < 0.45)
    }

    @Test
    fun `lottery walks keep their exact prior rolled scenery except torii becomes drift`() {
        var toriiConversions = 0
        for (s in lotterySweep()) {
            val legacy = legacyPick(s)
            val current = SceneryGenerator.pick(s)
            if (legacy == null) {
                assertNull("a walk the old generator left bare must stay bare", current)
                continue
            }
            assertNotNull(current)
            assertEquals(legacy.side, current!!.side)
            assertEquals(legacy.offset, current.offset, 0.0f)
            if (legacy.type == SceneryType.Torii) {
                toriiConversions++
                assertEquals(
                    "the retired random-torii band belongs to drift",
                    SceneryType.Drift,
                    current.type,
                )
            } else {
                assertEquals(
                    "every other walk's rolled item stays exactly what it has always been",
                    legacy.type,
                    current.type,
                )
            }
        }
        assertTrue("the fixture must exercise the retired band", toriiConversions > 0)
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

    @Test
    fun `default snapshot fields keep pre-U14 call sites in the lottery`() {
        val s = snap()
        assertFalse(s.isSeek)
        assertEquals(0, s.foundPlaces)
        assertNull(s.threshold)
        val placement = SceneryGenerator.pick(s)
        if (placement != null) {
            assertNull(placement.gateKind)
        }
    }

    private data class LegacyPlacement(
        val type: SceneryType,
        val side: ScenerySide,
        val offset: Float,
    )

    /**
     * Verbatim replica of the pre-U14 generator (the iOS-v1.8.0-parity
     * lottery with the random torii still in the 0.05 band) — the fixture
     * proving the drift splice left every other band bit-identical.
     */
    private fun legacyPick(snapshot: WalkSnapshot): LegacyPlacement? {
        val seed = legacySeed(snapshot)
        val roll1 = legacyRandom(seed, 1uL)
        if (roll1 >= 0.35) return null
        val roll2 = legacyRandom(seed, 2uL)
        var cumulative = 0.0
        var type = SceneryType.Tree
        for ((candidate, weight) in LEGACY_WEIGHTS) {
            cumulative += weight
            if (roll2 < cumulative) {
                type = candidate
                break
            }
        }
        val roll3 = legacyRandom(seed, 3uL)
        val side = if (roll3 < 0.5) ScenerySide.Left else ScenerySide.Right
        val roll4 = legacyRandom(seed, 4uL)
        return LegacyPlacement(type, side, (roll4 * 15.0 - 7.5).toFloat())
    }

    private fun legacySeed(snapshot: WalkSnapshot): ULong {
        var h: ULong = 14695981039346656037uL
        val u = java.util.UUID.fromString(snapshot.uuid)
        val bytes = ByteArray(16)
        for (i in 0 until 8) bytes[i] = (u.mostSignificantBits shr ((7 - i) * 8)).toByte()
        for (i in 0 until 8) bytes[8 + i] = (u.leastSignificantBits shr ((7 - i) * 8)).toByte()
        bytes.forEach { h = (h xor it.toULong()) * 1099511628211uL }
        h = (h xor (snapshot.startMs / 1000L).toULong()) * 1099511628211uL
        h = (h xor (snapshot.distanceM * 100.0).toLong().toULong()) * 1099511628211uL
        h = (h xor snapshot.durationSec.toLong().toULong()) * 1099511628211uL
        return h
    }

    private fun legacyRandom(seed: ULong, salt: ULong): Double {
        var mixed = seed + salt * 6364136223846793005uL
        mixed = mixed xor (mixed shr 33)
        mixed *= 0xff51afd7ed558ccduL
        mixed = mixed xor (mixed shr 33)
        mixed *= 0xc4ceb9fe1a85ec53uL
        mixed = mixed xor (mixed shr 33)
        return (mixed % 10000uL).toDouble() / 10000.0
    }

    private companion object {
        val LEGACY_WEIGHTS = listOf(
            SceneryType.Tree to 0.27,
            SceneryType.Lantern to 0.18,
            SceneryType.Grass to 0.22,
            SceneryType.Butterfly to 0.14,
            SceneryType.Mountain to 0.11,
            SceneryType.Torii to 0.05,
            SceneryType.Moon to 0.03,
        )
    }
}
