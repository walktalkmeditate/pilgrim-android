// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CairnTierTest {

    @Test
    fun `forStoneCount maps thresholds`() {
        assertEquals(CairnTier.Faint, CairnTier.forStoneCount(0))
        assertEquals(CairnTier.Faint, CairnTier.forStoneCount(2))
        assertEquals(CairnTier.Small, CairnTier.forStoneCount(3))
        assertEquals(CairnTier.Medium, CairnTier.forStoneCount(7))
        assertEquals(CairnTier.Large, CairnTier.forStoneCount(12))
        assertEquals(CairnTier.Great, CairnTier.forStoneCount(42))
        assertEquals(CairnTier.Sacred, CairnTier.forStoneCount(77))
        assertEquals(CairnTier.Eternal, CairnTier.forStoneCount(108))
        assertEquals(CairnTier.Eternal, CairnTier.forStoneCount(500))
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
