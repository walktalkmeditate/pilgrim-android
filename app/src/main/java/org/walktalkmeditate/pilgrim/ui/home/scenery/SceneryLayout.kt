// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Offset

/**
 * Pure scenery-layout math for the journal canvas, exposed for JVM tests
 * (Stage 3-C rule). HomeScreen calls these from its scenery block —
 * [sceneryCenterPx] at composition, [sceneryParallaxXPx] and
 * [sceneryAgeAlpha] feeding the scenery `graphicsLayer` lambda.
 */

/**
 * Scenery center relative to its own walk dot — iOS
 * `InkScrollView+Scenery.swift:25,51@c1745e8`:
 * `xOffset = side == .left ? -40 - size/2 : 40 + size/2`, then
 * `.offset(x: xOffset + placement.offset, y: -4)`.
 *
 * The displacement is a pure offset from the dot's center, never an
 * absolute scroll-content coordinate — the 3f9d3db bug class (content
 * coordinates applied twice through a positioned host frame) cannot
 * arise as long as callers add this to the dot's own center in the
 * canvas space the dot itself is placed in.
 */
internal fun sceneryCenterPx(
    dotXPx: Float,
    dotYPx: Float,
    scenerySizePx: Float,
    side: ScenerySide,
    jitterPx: Float,
    clearancePx: Float,
    liftPx: Float,
): Offset {
    val sign = if (side == ScenerySide.Left) -1f else 1f
    return Offset(
        x = dotXPx + sign * (clearancePx + scenerySizePx / 2f) + jitterPx,
        y = dotYPx - liftPx,
    )
}

/**
 * Horizontal parallax drift — iOS
 * `InkScrollView+Scenery.swift:52-57@c1745e8`:
 * `distFromCenter = (frame.midY − viewportHeight/2) / (viewportHeight/2)`,
 * `content.offset(x: distFromCenter * parallaxWeight)`. An item at the
 * viewport center doesn't move; at the bottom edge it drifts right by
 * its type's full weight, at the top edge left. No clamp — items past
 * the edges keep scaling linearly (iOS-verbatim; they're offscreen).
 *
 * Coordinate space is the journal canvas, with the viewport center
 * approximated as `scrollOffset + viewportHeight/2` — the same
 * convention the scroll haptic engine ships with (the header stack
 * above the canvas shifts the neutral line by a small constant; see
 * `docs/parity/2026-07-15-port-scenery-depth-u16.md` § 1).
 *
 * NOT gated on reduce-motion: iOS applies its `.visualEffect`
 * unconditionally — parallax is scroll-driven, not autonomous.
 */
internal fun sceneryParallaxXPx(
    sceneryCenterYPx: Float,
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
    weightPx: Float,
): Float {
    if (viewportHeightPx <= 0f) return 0f
    val halfViewportPx = viewportHeightPx / 2f
    val viewportCenterYPx = scrollOffsetPx + halfViewportPx
    return (sceneryCenterYPx - viewportCenterYPx) / halfViewportPx * weightPx
}

/**
 * The item ages with its walk — scenery multiplies the dot's own fade
 * (newest 1.0 → oldest 0.5, `WalkDotMath.dotOpacity`). Seeking gates
 * refuse the age fade — old stone grows older, not fainter
 * (iOS `InkScrollView+Scenery.swift:38-40@c1745e8`, 6e80a91). Practice
 * gates and everything else dim with their walk.
 */
internal fun sceneryAgeAlpha(gateKind: WalkThreshold?, dotFade: Float): Float =
    if (gateKind == WalkThreshold.Seeking) 1f else dotFade
