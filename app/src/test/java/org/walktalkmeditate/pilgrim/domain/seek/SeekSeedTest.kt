// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint

class SeekSeedTest {

    private val momentMillis = 1_790_000_000_000L

    private fun seed(
        intention: String? = "let go",
        momentMillis: Long = this.momentMillis,
        fix: LocationPoint? = null,
        entropy: ULong = 7uL,
    ): ULong = SeekSeed.make(intention, momentMillis, fix, entropy)

    private fun fix(
        timestamp: Long = 0L,
        latitude: Double = 42.8782,
        longitude: Double = -8.5448,
        altitudeMeters: Double? = 12.5,
        horizontalAccuracyMeters: Float? = 8f,
    ): LocationPoint = LocationPoint(
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        altitudeMeters = altitudeMeters,
    )

    @Test
    fun `same question same moment same entropy same seed`() {
        assertEquals(seed(), seed())
    }

    @Test
    fun `the intention is a voice in the seed`() {
        assertNotEquals(
            "a different question must be sent a different way",
            seed(intention = "let go"),
            seed(intention = "find courage"),
        )
        assertNotEquals(
            "the question exactly as asked - case and all",
            seed(intention = "let go"),
            seed(intention = "Let go"),
        )
        assertNotEquals(seed(intention = "let go"), seed(intention = null))
    }

    @Test
    fun `the moment is a voice in the seed`() {
        assertNotEquals(
            "the same question a second later never repeats the way",
            seed(),
            seed(momentMillis = momentMillis + 1_000L),
        )
    }

    @Test
    fun `entropy is a voice in the seed`() {
        assertNotEquals(seed(entropy = 7uL), seed(entropy = 8uL))
    }

    @Test
    fun `empty and null intention read as unasked`() {
        assertEquals(seed(intention = null), seed(intention = ""))
    }

    @Test
    fun `the fix is a voice in the seed`() {
        assertNotEquals(seed(fix = null), seed(fix = fix()))
        assertNotEquals(seed(fix = fix()), seed(fix = fix(latitude = 42.8792)))
        assertEquals(
            "only the four position components enter the digest",
            seed(fix = fix(timestamp = 1L)),
            seed(fix = fix(timestamp = 2L)),
        )
    }

    @Test
    fun `absent fix components read as zero in the digest`() {
        assertEquals(
            seed(fix = fix(altitudeMeters = null, horizontalAccuracyMeters = null)),
            seed(fix = fix(altitudeMeters = 0.0, horizontalAccuracyMeters = 0f)),
        )
    }

    /**
     * Fixtures hand-computed with an independent implementation (python
     * hashlib + struct little-endian packing) of the iOS byte layout —
     * spec B3. A mismatch means the digest byte order drifted from iOS.
     */
    @Test
    fun `seed derivation matches the iOS byte layout`() {
        assertEquals(16996866564468935451uL, seed())
        assertEquals(16688271835005157925uL, seed(intention = null))
        assertEquals(15651861120168273365uL, seed(fix = fix()))
    }

    @Test
    fun `seeded generator is deterministic per seed`() {
        val first = SeekSeededGenerator(42uL)
        val second = SeekSeededGenerator(42uL)
        val other = SeekSeededGenerator(43uL)
        val a = List(4) { first.nextULong() }
        val b = List(4) { second.nextULong() }
        val c = List(4) { other.nextULong() }
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    /**
     * Seed 0 is the published SplitMix64 reference vector; seed 42 was
     * cross-computed independently. Pins bit-parity with the iOS
     * generator (spec B4).
     */
    @Test
    fun `generator matches the SplitMix64 reference sequence`() {
        val zero = SeekSeededGenerator(0uL)
        assertEquals(0xe220a8397b1dcdafuL, zero.nextULong())
        assertEquals(0x6e789e6aa1b965f4uL, zero.nextULong())

        val fortyTwo = SeekSeededGenerator(42uL)
        assertEquals(0xbdd732262feb6e95uL, fortyTwo.nextULong())
        assertEquals(0x28efe333b266f103uL, fortyTwo.nextULong())
        assertEquals(0x47526757130f9f52uL, fortyTwo.nextULong())
        assertEquals(0x581ce1ff0e4ae394uL, fortyTwo.nextULong())
    }

    @Test
    fun `seeded chain generation is reproducible`() {
        val start = SeekPoint(latitude = 42.8782, longitude = -8.5448)
        val one = SeekChainGenerator.generate(60, start, SeekSeededGenerator(99uL))
        val two = SeekChainGenerator.generate(60, start, SeekSeededGenerator(99uL))
        val three = SeekChainGenerator.generate(60, start, SeekSeededGenerator(100uL))
        assertEquals("one seed is one seek", one, two)
        assertNotEquals("a different seed must be sent a different way", one, three)
    }
}
