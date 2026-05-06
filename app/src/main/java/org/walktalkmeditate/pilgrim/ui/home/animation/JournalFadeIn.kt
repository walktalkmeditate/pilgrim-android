// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Cascading fade-in state. Per iOS `InkScrollView.swift`:
 *   - segments[i]: delay 200 + i*30 ms, duration 1200 ms, EaseOut
 *   - dots[i]    : delay 300 + i*30 ms, duration  500 ms, EaseOut
 *   - scenery[i] : shares dot timing
 *
 * Stage 5-A lesson: consumer sites use
 *   `Modifier.graphicsLayer { alpha = state.dotAlpha(i) }`
 * lambda form — NOT the value form `Modifier.alpha(state.dotAlpha(i))`,
 * which reads animated state in COMPOSITION phase and causes thousands
 * of unnecessary recompositions per session.
 *
 * Stage 5-A lesson: `rememberSaveable` for `hasAppeared` so screen
 * rotation doesn't replay the fade.
 *
 * `LocalReduceMotion` gate: when reduceMotion is true, every alpha
 * snaps to 1f immediately (no animation).
 */
@Composable
fun rememberJournalFadeIn(reduceMotion: Boolean): JournalFadeInState {
    var hasAppeared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasAppeared = true }
    return remember(reduceMotion, hasAppeared) {
        JournalFadeInState(hasAppeared = hasAppeared, reduceMotion = reduceMotion)
    }
}

@Stable
class JournalFadeInState(
    private val hasAppeared: Boolean,
    private val reduceMotion: Boolean,
) {
    @Composable
    fun segmentAlpha(index: Int): Float {
        if (reduceMotion) return 1f
        val target = if (hasAppeared) 1f else 0f
        val animation = animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = SEGMENT_DURATION_MS,
                delayMillis = SEGMENT_DELAY_BASE_MS + index * STAGGER_STEP_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "journal-segment-fade",
        )
        return animation.value
    }

    @Composable
    fun dotAlpha(index: Int): Float {
        if (reduceMotion) return 1f
        val target = if (hasAppeared) 1f else 0f
        val animation = animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = DOT_DURATION_MS,
                delayMillis = DOT_DELAY_BASE_MS + index * STAGGER_STEP_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "journal-dot-fade",
        )
        return animation.value
    }

    /** Scenery shares dot timing per spec. */
    @Composable
    fun sceneryAlpha(index: Int): Float = dotAlpha(index)

    private companion object {
        const val SEGMENT_DELAY_BASE_MS = 200
        const val SEGMENT_DURATION_MS = 1200
        const val DOT_DELAY_BASE_MS = 300
        const val DOT_DURATION_MS = 500
        const val STAGGER_STEP_MS = 30
    }
}
