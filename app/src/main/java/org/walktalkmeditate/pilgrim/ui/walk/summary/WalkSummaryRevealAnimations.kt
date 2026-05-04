// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * Per-section reveal stagger constants. Match iOS
 * `WalkSummaryView.swift` lines 320-542 timing parameters.
 *
 * iOS table:
 *   journeyQuote     duration 800ms, delay 0
 *   durationHero     duration 600ms, delay 0, fires on Zoomed (not just Revealed)
 *   milestoneCallout duration 800ms, delay 300ms
 *   statsRow         duration 600ms, delay 200ms
 *   weatherLine      duration 600ms, delay 200ms
 *   celestialLine    duration 600ms, delay 300ms
 *   timeBreakdown    duration 600ms, delay 400ms
 */
internal const val REVEAL_DURATION_HERO_MS = 600
internal const val REVEAL_DURATION_QUOTE_MS = 800
internal const val REVEAL_DURATION_CALLOUT_MS = 800
internal const val REVEAL_DURATION_DEFAULT_MS = 600
internal const val REVEAL_DELAY_STATS_MS = 200
internal const val REVEAL_DELAY_CELESTIAL_MS = 300
internal const val REVEAL_DELAY_BREAKDOWN_MS = 400

/**
 * Pure target-alpha computation for a section in the Walk Summary
 * reveal stagger. Extracted so unit tests can gate every
 * [RevealPhase] × [fireOnZoomed] combination without driving
 * Compose's animation clock.
 */
internal fun targetAlpha(revealPhase: RevealPhase, fireOnZoomed: Boolean): Float = when {
    fireOnZoomed -> if (revealPhase != RevealPhase.Hidden) 1f else 0f
    else -> if (revealPhase == RevealPhase.Revealed) 1f else 0f
}

/**
 * Per-section animated alpha for the Walk Summary reveal stagger.
 * Returns 1f when [revealPhase] matches the visibility predicate
 * (or when [reduceMotion] collapses the animation to 0ms).
 *
 * @param fireOnZoomed when true, the section becomes visible at
 *   [RevealPhase.Zoomed] (one phase earlier than the rest). Used
 *   only by [WalkDurationHero] to match iOS — the duration text
 *   appears WITH the map zoom, not after.
 */
@Composable
internal fun rememberRevealAlpha(
    revealPhase: RevealPhase,
    durationMs: Int,
    delayMs: Int,
    reduceMotion: Boolean,
    fireOnZoomed: Boolean = false,
): Float {
    val target = targetAlpha(revealPhase, fireOnZoomed)
    val anim by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) {
            tween(0)
        } else {
            tween(durationMs, delayMillis = delayMs, easing = EaseIn)
        },
        label = "reveal-alpha-$durationMs-$delayMs",
    )
    return anim
}
