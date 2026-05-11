// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
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
 * Compose [Saver] for [RevealPhase] — uses ordinal so the value
 * survives configuration changes (rotation, font-size, multi-window
 * resize). Without this, `rememberSaveable` would fall back to
 * default autoSaver (which doesn't handle enums) and the cinematic
 * would replay every rotation, re-firing the count-up + haptic.
 *
 * Restore falls back to [RevealPhase.Hidden] on out-of-range ordinals
 * — defensive against the app-update path where a saved-state bundle
 * from an older release encodes an ordinal that no longer maps to a
 * valid enum entry (enum reordering, removal, etc.). The cinematic
 * replays from scratch in that case, which is acceptable on the rare
 * cross-version restore.
 */
internal val RevealPhaseSaver: Saver<RevealPhase, Int> = Saver(
    save = { it.ordinal },
    restore = { ordinal -> RevealPhase.entries.getOrNull(ordinal) ?: RevealPhase.Hidden },
)

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

/**
 * Camera ease duration when a timeline-bar segment tap zooms the
 * Walk Summary map into the segment's GPS bounds. Quick — the user
 * is interacting; a long ease feels unresponsive. iOS uses 350ms
 * (`WalkSummaryView.swift:954`).
 */
internal const val SEGMENT_ZOOM_EASE_MS = 350L

/** Below-map sections fade-in duration on Revealed. */
internal const val REVEAL_FADE_MS = 600

/**
 * Camera ease duration for the initial Hidden → Zoomed plant. iOS uses
 * `cameraDuration = 0.1` (`WalkSummaryView.swift:362`) — a quick pull-in,
 * not an instant snap, so the user perceives the map "arriving" at the
 * starting point before the longer reveal ease.
 */
internal const val REVEAL_ZOOM_PLANT_MS = 100L

/**
 * Number of progress increments in the distance count-up animation.
 * iOS schedules `for i in 0...steps` (`WalkSummaryView.swift:384`) —
 * 31 emissions, 30 transitions. The discrete cadence (≈14 fps) is the
 * old-odometer feel that distinguishes this from a smooth tween.
 */
internal const val COUNT_UP_STEPS = 30

/**
 * Per-step delay for the count-up emitter. iOS uses
 * `interval = 2.0 / 30 = 66.67 ms` (Double). Android rounds UP to 67 ms
 * so total = 30 × 67 = 2010 ms, preserving the perceived iOS rhythm
 * rather than truncating to 66 ms (which yields 1980 ms, +20 ms drift
 * in the wrong direction — the final-tick payoff frame matters most
 * perceptually).
 */
internal const val COUNT_UP_INTERVAL_MS = 67L

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
