// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

/**
 * Parchment patina alpha for the goshuin screen background. Four
 * walk-count breakpoints ported from iOS's
 * `GoshuinPageView.patinaColor`:
 *  - `0..10`   → `0f` (no tint)
 *  - `11..30`  → [PATINA_ALPHA_TIER_1]
 *  - `31..70`  → [PATINA_ALPHA_TIER_2]
 *  - `71+`     → [PATINA_ALPHA_TIER_3]
 *
 * The goshuin screen applies this alpha to a `pilgrimColors.dawn`
 * overlay above the parchment background. The book visibly ages as
 * practice deepens — wabi-sabi reward, not UI decoration. iOS applies
 * the tint per-page inside its paged TabView; Android's endless
 * `LazyVerticalGrid` applies it once to the whole screen.
 *
 * Pure function; [GoshuinPatinaTest] exercises every tier plus the
 * negative-count guard.
 */
internal fun patinaAlphaFor(walkCount: Int): Float = when {
    walkCount < PATINA_TIER_1_COUNT -> 0f
    walkCount < PATINA_TIER_2_COUNT -> PATINA_ALPHA_TIER_1
    walkCount < PATINA_TIER_3_COUNT -> PATINA_ALPHA_TIER_2
    else -> PATINA_ALPHA_TIER_3
}

internal const val PATINA_TIER_1_COUNT = 11
internal const val PATINA_TIER_2_COUNT = 31
internal const val PATINA_TIER_3_COUNT = 71
internal const val PATINA_ALPHA_TIER_1 = 0.03f
internal const val PATINA_ALPHA_TIER_2 = 0.07f
internal const val PATINA_ALPHA_TIER_3 = 0.12f
