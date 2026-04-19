// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Full-screen overlay that stamps the walk's goshuin seal on-screen
 * with a 3-phase animation, holds for 2.5s, then calls [onDismiss].
 * Tap anywhere to dismiss early.
 *
 * Phases (ported from iOS's `SealRevealView.swift`):
 *  1. **Hidden → Pressing**: scale 1.2 → 0.95 over 200ms (easeIn),
 *     background and seal opacity cross-fade in.
 *  2. **Pressing → Revealed**: scale 0.95 → 1.0 via spring (matches
 *     iOS `spring(response: 0.4, dampingFraction: 0.6)` — see the
 *     design spec's "spring parameter conversion" note). A single
 *     `LongPress` haptic fires at this transition. Shadow fades in
 *     to 25% alpha.
 *  3. **Hold (2500ms) → Dismissing**: alpha 1 → 0 over 300ms, then
 *     [onDismiss] fires.
 *
 * A tap on the overlay background OR the seal itself cancels the
 * auto-dismiss and advances straight to phase 3.
 *
 * See `docs/superpowers/specs/2026-04-19-stage-4b-seal-reveal-design.md`.
 */
@Composable
fun SealRevealOverlay(
    spec: SealSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sealSizeDp: Int = DEFAULT_SEAL_SIZE_DP,
) {
    var phase by remember { mutableStateOf(SealRevealPhase.Hidden) }
    val haptic = LocalHapticFeedback.current
    // Snapshot the latest [onDismiss] so a parent that passes a fresh
    // lambda each recomposition doesn't leave us firing a stale
    // closure when the already-running LaunchedEffect resumes after
    // its delay. Standard rememberUpdatedState pattern for suspend-
    // scope callbacks.
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val scale by animateFloatAsState(
        targetValue = when (phase) {
            SealRevealPhase.Hidden, SealRevealPhase.Dismissing -> SCALE_HIDDEN
            SealRevealPhase.Pressing -> SCALE_PRESSED
            SealRevealPhase.Revealed -> SCALE_REVEALED
        },
        animationSpec = when (phase) {
            SealRevealPhase.Pressing -> tween(durationMillis = PRESS_DURATION_MS, easing = EaseIn)
            SealRevealPhase.Revealed -> spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)
            else -> tween(durationMillis = FADE_DURATION_MS)
        },
        label = "sealRevealScale",
    )
    val opacity by animateFloatAsState(
        targetValue = when (phase) {
            SealRevealPhase.Hidden, SealRevealPhase.Dismissing -> 0f
            SealRevealPhase.Pressing, SealRevealPhase.Revealed -> 1f
        },
        animationSpec = tween(durationMillis = FADE_DURATION_MS),
        label = "sealRevealOpacity",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (phase == SealRevealPhase.Revealed) SHADOW_ALPHA_REVEALED else 0f,
        animationSpec = tween(durationMillis = SHADOW_DURATION_MS),
        label = "sealRevealShadow",
    )

    // First effect drives the initial choreography. Each post-delay
    // phase assignment is guarded by a check that the user hasn't
    // already tapped to dismiss during the delay — without the check,
    // a tap during the 200ms press phase would (a) still fire a
    // confusing haptic 100-200ms later, and (b) overwrite the tap's
    // `phase = Dismissing` with `phase = Revealed`, regressing the
    // "tap to dismiss early" contract into a 2.8s hold.
    LaunchedEffect(Unit) {
        phase = SealRevealPhase.Pressing
        delay(PRESS_DURATION_MS.toLong())
        if (phase != SealRevealPhase.Pressing) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        phase = SealRevealPhase.Revealed
        delay(HOLD_DURATION_MS)
        if (phase == SealRevealPhase.Revealed) {
            phase = SealRevealPhase.Dismissing
        }
    }
    // Second effect handles the Dismissing → onDismiss fade-out
    // uniformly across auto-dismiss and tap-to-dismiss paths.
    LaunchedEffect(phase) {
        if (phase == SealRevealPhase.Dismissing) {
            delay(FADE_DURATION_MS.toLong())
            currentOnDismiss()
        }
    }

    // `MutableInteractionSource` + `indication = null` suppresses the
    // default material ripple — the overlay background shouldn't pulse.
    val interactionSource = remember { MutableInteractionSource() }
    // `clickable(enabled = phase != Dismissing)` releases touch capture
    // during the 300ms fade-out so taps aimed at the underlying
    // summary content (e.g., the Done button) land on the button
    // rather than being swallowed by the fading overlay.
    val overlayInteractive = phase != SealRevealPhase.Dismissing
    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(opacity)
            .background(pilgrimColors.parchment.copy(alpha = OVERLAY_BACKGROUND_ALPHA))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = overlayInteractive,
            ) {
                if (phase != SealRevealPhase.Dismissing) {
                    phase = SealRevealPhase.Dismissing
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sealSizeDp.dp)
                .scale(scale)
                .shadow(
                    elevation = SHADOW_ELEVATION_DP.dp,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha),
                ),
        ) {
            SealRenderer(spec = spec)
        }
    }
}

internal enum class SealRevealPhase { Hidden, Pressing, Revealed, Dismissing }

private const val SCALE_HIDDEN = 1.2f
private const val SCALE_PRESSED = 0.95f
private const val SCALE_REVEALED = 1.0f

private const val PRESS_DURATION_MS = 200
private const val FADE_DURATION_MS = 300
private const val SHADOW_DURATION_MS = 150
private const val HOLD_DURATION_MS = 2500L

// iOS `spring(response: 0.4, dampingFraction: 0.6)` doesn't map 1:1 to
// Compose's dampingRatio/stiffness; these are empirically close. Stage
// 4-B device QA may tune.
private const val SPRING_DAMPING = 0.6f
private const val SPRING_STIFFNESS = 500f

private const val OVERLAY_BACKGROUND_ALPHA = 0.95f
private const val SHADOW_ALPHA_REVEALED = 0.25f
private const val SHADOW_ELEVATION_DP = 12
private const val DEFAULT_SEAL_SIZE_DP = 220
