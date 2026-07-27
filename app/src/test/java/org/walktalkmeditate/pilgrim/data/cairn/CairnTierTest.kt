// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CairnTierTest {

    @Test
    fun `forStoneCount maps thresholds`() {
        // iOS parity CairnTierTests.testTierThresholdTable@9a418e4 —
        // every band's entry AND exit count.
        assertEquals(CairnTier.Faint, CairnTier.forStoneCount(0))
        assertEquals(CairnTier.Faint, CairnTier.forStoneCount(2))
        assertEquals(CairnTier.Small, CairnTier.forStoneCount(3))
        assertEquals(CairnTier.Small, CairnTier.forStoneCount(6))
        assertEquals(CairnTier.Medium, CairnTier.forStoneCount(7))
        assertEquals(CairnTier.Medium, CairnTier.forStoneCount(11))
        assertEquals(CairnTier.Large, CairnTier.forStoneCount(12))
        assertEquals(CairnTier.Large, CairnTier.forStoneCount(41))
        assertEquals(CairnTier.Great, CairnTier.forStoneCount(42))
        assertEquals(CairnTier.Great, CairnTier.forStoneCount(76))
        assertEquals(CairnTier.Sacred, CairnTier.forStoneCount(77))
        assertEquals(CairnTier.Sacred, CairnTier.forStoneCount(107))
        assertEquals(CairnTier.Eternal, CairnTier.forStoneCount(108))
        assertEquals(CairnTier.Eternal, CairnTier.forStoneCount(500))
    }

    // iOS parity CairnTierTests@9a418e4 — the add-a-stone sheet
    // previews CachedCairn.becomingTier, the tier the cairn becomes
    // with the walker's stone.

    private fun cairn(stones: Int) = CachedCairn(
        id = "test",
        latitude = 0.0,
        longitude = 0.0,
        stoneCount = stones,
        lastPlacedAt = "",
    )

    @Test
    fun `becoming six stones crosses into medium`() {
        // AE1: the walker sees what their stone makes.
        assertEquals(CairnTier.Medium, cairn(6).becomingTier)
    }

    @Test
    fun `becoming eight stones stays medium`() {
        // AE2: most stones deepen a tier, not change it.
        assertEquals(CairnTier.Medium, cairn(8).becomingTier)
    }

    @Test
    fun `becoming for a new cairn is faint`() {
        // AE3: a first stone begins a faint cairn.
        assertEquals(CairnTier.Faint, CairnTier.forStoneCount(1))
    }

    @Test
    fun `becoming crosses every remaining threshold`() {
        assertEquals(CairnTier.Small, cairn(2).becomingTier)
        assertEquals(CairnTier.Large, cairn(11).becomingTier)
        assertEquals(CairnTier.Great, cairn(41).becomingTier)
        assertEquals(CairnTier.Sacred, cairn(76).becomingTier)
        assertEquals(CairnTier.Eternal, cairn(107).becomingTier)
    }

    @Test
    fun `soundTier boundary counts`() {
        // iOS parity CairnTierTests.testSoundTier_boundaryCounts@9a418e4.
        assertEquals(1, CairnTier.forStoneCount(2).soundTier)
        assertEquals(2, CairnTier.forStoneCount(3).soundTier)
        assertEquals(2, CairnTier.forStoneCount(6).soundTier)
        assertEquals(3, CairnTier.forStoneCount(7).soundTier)
        assertEquals(4, CairnTier.forStoneCount(41).soundTier)
        // The great crossing must reach the milestone haptic gate.
        assertEquals(5, CairnTier.forStoneCount(42).soundTier)
        assertEquals(6, CairnTier.forStoneCount(77).soundTier)
        assertEquals(7, CairnTier.forStoneCount(108).soundTier)
    }

    @Test
    fun `next walks up the tiers and stops at eternal`() {
        assertEquals(CairnTier.Small, CairnTier.Faint.next)
        assertEquals(CairnTier.Eternal, CairnTier.Sacred.next)
        assertNull(CairnTier.Eternal.next)
    }

    @Test
    fun `stonesToNext counts down to the next threshold`() {
        // Faint band, 1 stone → Small needs 3 → 2 more.
        assertEquals(2, CairnTier.Faint.stonesToNext(1))
        // Large band, 12 stones → Great needs 42 → 30 more.
        assertEquals(30, CairnTier.Large.stonesToNext(12))
        // Never negative.
        assertEquals(0, CairnTier.Faint.stonesToNext(99))
        // Eternal has no next.
        assertNull(CairnTier.Eternal.stonesToNext(108))
    }

    @Test
    fun `progressToNext is the fraction within the band`() {
        assertEquals(0f, CairnTier.Faint.progressToNext(0), 0.0001f)
        // Faint band is 0..3 → 1 stone is 1/3.
        assertEquals(1f / 3f, CairnTier.Faint.progressToNext(1), 0.0001f)
        // Clamped to 1.
        assertEquals(1f, CairnTier.Faint.progressToNext(3), 0.0001f)
        // Eternal → 0 (no next band).
        assertEquals(0f, CairnTier.Eternal.progressToNext(200), 0.0001f)
    }
}
