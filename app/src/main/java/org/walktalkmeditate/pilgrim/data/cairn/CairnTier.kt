// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

/**
 * iOS parity `CairnTier.swift@db4196e` — seven tiers based on stone
 * count. `soundTier` (1..7) picks the bundled `stone_tier_N.m4a`
 * sample on placement; `circleRadius` is used inside the cairn
 * detail hero (not on the map pin); `glows` is only true for
 * [Eternal] (gates a radial gradient halo in CairnDetailView).
 *
 * Map-pin size is computed separately as `12 + ordinal` (12..18 dp)
 * — different formula from `circleRadius` to keep map pins ambient.
 */
enum class CairnTier(
    val minStones: Int,
    val soundTier: Int,
    val circleRadius: Float,
    val glows: Boolean,
) {
    Faint(minStones = 0, soundTier = 1, circleRadius = 5f, glows = false),
    Small(minStones = 3, soundTier = 2, circleRadius = 7f, glows = false),
    Medium(minStones = 7, soundTier = 3, circleRadius = 9f, glows = false),
    Large(minStones = 12, soundTier = 4, circleRadius = 11f, glows = false),
    Great(minStones = 42, soundTier = 5, circleRadius = 13f, glows = false),
    Sacred(minStones = 77, soundTier = 6, circleRadius = 15f, glows = false),
    Eternal(minStones = 108, soundTier = 7, circleRadius = 17f, glows = true);

    /** The next tier up, or null if already [Eternal] (iOS `CairnTier.nextTier`). */
    val next: CairnTier? get() = entries.getOrNull(ordinal + 1)

    /** Stones still needed to reach [next], or null at [Eternal]. */
    fun stonesToNext(stoneCount: Int): Int? =
        next?.let { (it.minStones - stoneCount).coerceAtLeast(0) }

    /**
     * Fill fraction in [0,1] toward [next] within this tier's band
     * (iOS `CairnDetailView.progressSection`). 0 at [Eternal].
     */
    fun progressToNext(stoneCount: Int): Float {
        val n = next ?: return 0f
        val range = n.minStones - minStones
        if (range <= 0) return 0f
        return ((stoneCount - minStones).toFloat() / range).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * Map a raw stone count to its [CairnTier]. Linear scan of the
         * descending-threshold list (7 entries — cheap, no math).
         */
        fun forStoneCount(count: Int): CairnTier {
            // Walk in descending threshold order so the first match
            // wins; an in-bounds count always returns the tier whose
            // minStones <= count.
            return entries.lastOrNull { count >= it.minStones } ?: Faint
        }
    }
}
