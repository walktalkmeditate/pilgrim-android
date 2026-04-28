// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm

/**
 * A soft moss-glow circle that breathes — scales 0.45 → 1.0 and back
 * over a [BreathRhythm]-driven 4-phase cycle:
 *  - inhale (scale 0.45 → 1.0, [FastOutSlowInEasing])
 *  - hold-in  (hold at 1.0)
 *  - exhale (scale 1.0 → 0.45, [FastOutSlowInEasing])
 *  - hold-out (hold at 0.45)
 *
 * Phases with 0 seconds are skipped — `keyframes` collapses them to
 * a zero-duration segment so the animation continues to the next phase
 * without a visual stutter. When [BreathRhythm.isNone] (id 6 — "Still
 * focus point"), the circle remains static at the inhaled scale (no
 * animation) — meditation-as-still-focus mode.
 *
 * Pure visual composable: takes the moss color + rhythm as parameters
 * (no theme read, no DI) so previews and tests don't need a
 * `PilgrimTheme` or `WalkViewModel` wrapper.
 */
@Composable
internal fun BreathingCircle(
    moss: Color,
    modifier: Modifier = Modifier,
    breathRhythm: BreathRhythm = BreathRhythm.byId(BreathRhythm.DEFAULT_ID),
) {
    // Cache the moss-alpha color lists so a 30-minute session doesn't
    // allocate on every frame. The brushes are built inside the Canvas
    // DrawScope below with a FIXED `radius` — scale is applied via
    // `Modifier.graphicsLayer { scaleX / scaleY }` (lambda form) on the
    // Canvas, so the draw block runs only when `moss` or canvas
    // dimensions change.
    val outerColors = remember(moss) {
        listOf(
            moss.copy(alpha = 0.5f),
            moss.copy(alpha = 0.15f),
            moss.copy(alpha = 0f),
        )
    }
    val innerColors = remember(moss) {
        listOf(
            moss.copy(alpha = 0.7f),
            moss.copy(alpha = 0.3f),
        )
    }

    val scale = if (breathRhythm.isNone) {
        // No-cadence mode: hold the inhaled scale. No animation, no
        // recomposition driver, no jitter. The circle is a still focal
        // point — matches iOS's open-meditation interpretation.
        SCALE_INHALED
    } else {
        rememberBreathScale(breathRhythm)
    }

    Canvas(
        // `Modifier.graphicsLayer { scaleX = scale; scaleY = scale }`
        // with the **lambda form** reads the animated `scale` during
        // the DRAW phase — `BreathingCircle` does NOT recompose on
        // every frame. The compositor updates only the layer's
        // transform matrix per frame. The alternative value form
        // `Modifier.scale(scale)` reads `scale` in composition scope,
        // causing ~108K unnecessary recompositions over a 30-min
        // session (real overhead under battery saver).
        //
        // The DrawScope block runs only when the modifier chain above
        // changes (stable) or `moss` changes (rare theme flip).
        // Brushes + color lists are allocated once, not per frame.
        modifier = modifier
            .size(CIRCLE_SIZE_DP.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f

        // Outer halo — moss at 50% fading to 0%, broad and soft.
        drawCircle(
            brush = Brush.radialGradient(
                colors = outerColors,
                center = center,
                radius = outerRadius,
            ),
            radius = outerRadius,
            center = center,
        )
        // Inner core — moss at 70% fading to 30%, denser focal point.
        val innerRadius = outerRadius * INNER_CORE_FRACTION
        drawCircle(
            brush = Brush.radialGradient(
                colors = innerColors,
                center = center,
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = center,
        )
    }
}

/**
 * Build a 4-phase breath-cycle keyframe spec from a [BreathRhythm].
 * Returns a [Float] state that animates indefinitely. `keyframes` over
 * `infiniteRepeatable` lets phase 2 (hold-in) and phase 4 (hold-out)
 * be expressed as identical-value keyframes that the animator
 * interprets as a hold.
 *
 * Easing: inhale + exhale use [FastOutSlowInEasing] for the eased
 * acceleration; the holds use [LinearEasing] (irrelevant since the
 * value doesn't change but matches `keyframes` defaults cleanly).
 *
 * Defensive: if the total cycle ms ends up at 0 (shouldn't happen
 * for any non-`isNone` rhythm, but a future preset with all zeros
 * would crash), fall back to the inhaled scale — same shape as the
 * `isNone` branch.
 */
@Composable
private fun rememberBreathScale(rhythm: BreathRhythm): Float {
    val transition = rememberInfiniteTransition(label = "breath")

    val inhaleMs = (rhythm.inhaleSeconds * 1000.0).toInt()
    val holdInMs = (rhythm.holdInSeconds * 1000.0).toInt()
    val exhaleMs = (rhythm.exhaleSeconds * 1000.0).toInt()
    val holdOutMs = (rhythm.holdOutSeconds * 1000.0).toInt()
    val totalMs = inhaleMs + holdInMs + exhaleMs + holdOutMs

    if (totalMs <= 0) {
        // Defensive: `isNone` should already short-circuit upstream;
        // this guard catches any future zero-only preset.
        return SCALE_INHALED
    }

    val animation = remember(rhythm.id) {
        keyframes<Float> {
            durationMillis = totalMs
            // Phase 0: starting (exhaled) value at t=0.
            SCALE_EXHALED at 0 using FastOutSlowInEasing
            // Phase 1: end of inhale — at SCALE_INHALED.
            SCALE_INHALED at inhaleMs using LinearEasing
            // Phase 2: end of hold-in — still at SCALE_INHALED (a hold).
            SCALE_INHALED at (inhaleMs + holdInMs) using FastOutSlowInEasing
            // Phase 3: end of exhale — at SCALE_EXHALED.
            SCALE_EXHALED at (inhaleMs + holdInMs + exhaleMs) using LinearEasing
            // Phase 4: end of hold-out — back at SCALE_EXHALED, repeat.
            SCALE_EXHALED at (inhaleMs + holdInMs + exhaleMs + holdOutMs)
        }
    }

    val scale by transition.animateFloat(
        initialValue = SCALE_EXHALED,
        targetValue = SCALE_EXHALED,
        animationSpec = infiniteRepeatable(
            animation = animation,
        ),
        label = "breathScale",
    )
    return scale
}

private const val SCALE_EXHALED = 0.45f
private const val SCALE_INHALED = 1.0f
private const val CIRCLE_SIZE_DP = 320
private const val INNER_CORE_FRACTION = 0.5f
