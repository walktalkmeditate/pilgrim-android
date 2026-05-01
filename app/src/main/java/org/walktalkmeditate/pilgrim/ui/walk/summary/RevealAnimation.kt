// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Phase of the post-walk Walk Summary reveal sequence. Matches iOS
 * `WalkSummaryView.RevealPhase` (`WalkSummaryView.swift:46-48`).
 *
 *  - [Hidden]   — initial state on first composition; map alpha = 0,
 *                 below-map sections invisible, count-up sits at 0.
 *  - [Zoomed]   — camera is planted at the route's first GPS point at
 *                 zoom 16; held for ~800ms.
 *  - [Revealed] — camera animates over 2.5s to fit-bounds; below-map
 *                 sections fade in over 600ms; distance counts up 0 →
 *                 final over 2s with smooth-step easing.
 */
internal enum class RevealPhase { Hidden, Zoomed, Revealed }

/**
 * iOS uses `progress * progress * (3 - 2*progress)` for the count-up
 * fraction. Compose's stock easings don't expose this curve directly;
 * declared here so production + tests share one definition.
 */
internal val SmoothStepEasing = Easing { fraction ->
    fraction * fraction * (3f - 2f * fraction)
}

/** Time the camera holds at the zoomed-in plant before fanning out. */
internal const val ZOOM_HOLD_MS = 800L

/** Camera ease duration for Zoomed → Revealed transition. */
internal const val REVEAL_CAMERA_EASE_MS = 2_500L

/** Below-map sections fade-in duration on Revealed. */
internal const val REVEAL_FADE_MS = 600

/** Distance count-up animation duration. */
internal const val COUNT_UP_DURATION_MS = 2_000

/**
 * Theme-resolved colors for the route polyline segments. Tokens read at
 * the @Composable layer (LocalPilgrimColors), packaged here so
 * [PilgrimMap] doesn't need to depend on the theme module directly.
 */
@Immutable
data class RouteSegmentColors(
    val walking: Color,
    val talking: Color,
    val meditating: Color,
)

/**
 * Theme-resolved colors for the Walk Summary map's annotation pins
 * (start/end + meditation + voice recording). Same packaging pattern
 * as [RouteSegmentColors] — read at the @Composable layer
 * (LocalPilgrimColors), passed into [PilgrimMap] so it doesn't need
 * to depend on the theme module directly.
 */
@Immutable
data class WalkAnnotationColors(
    val startEnd: Color,
    val meditation: Color,
    val voice: Color,
)
