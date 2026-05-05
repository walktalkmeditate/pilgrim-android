// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin dot geometry helpers, ported from iOS `InkScrollView.swift`.
 * Exposed as `internal` (package-visible) so Robolectric tests exercise
 * the formulas without composition.
 */
internal object WalkDotMath {

    private const val MIN_DURATION_SEC = 300.0   // 5 min
    private const val MAX_DURATION_SEC = 7200.0  // 2 h
    private const val MIN_DOT_DP = 8f
    private const val MAX_DOT_DP = 22f

    /**
     * Linear scale duration → dot diameter (dp). 5 min → 8 dp, 2 h → 22 dp.
     * Verbatim port of iOS `InkScrollView.swift` size formula.
     */
    fun dotSize(durationSec: Double): Float {
        val clamped = min(max(durationSec, MIN_DURATION_SEC), MAX_DURATION_SEC)
        val frac = (clamped - MIN_DURATION_SEC) / (MAX_DURATION_SEC - MIN_DURATION_SEC)
        return (MIN_DOT_DP + frac * (MAX_DOT_DP - MIN_DOT_DP)).toFloat()
    }

    /**
     * Verbatim port of iOS InkScrollView.swift:493-497.
     * Newest walk (index 0) → 1.0, oldest (index total-1) → 0.5.
     */
    fun dotOpacity(index: Int, total: Int): Float =
        if (total <= 1) 1f else 1f - (index.toFloat() / (total - 1)) * 0.5f

    /** iOS InkScrollView.swift:636 — distance-label α = dotOpacity * 0.7. */
    fun labelOpacity(index: Int, total: Int): Float =
        dotOpacity(index, total) * 0.7f
}
