// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.settings.about.PilgrimLogo
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimMotion
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Timeline for the post-permissions breath transition. Port of iOS
 * `BreathTransitionView.runTransition` (`BreathTransitionView.swift`).
 *
 * Full sequence: logo fades+scales in over 1.0 s, the inhale begins at
 * a fixed 1.2 s, a soft haptic fires one breath later, the exhale
 * begins 0.3 s after that, and completion lands one final breath after
 * the exhale. Reduce-motion collapses everything to a 0.5 s hold.
 */
internal data class BreathTransitionPlan(
    val reduceMotion: Boolean,
    val breathMs: Long,
    val inhaleStartMs: Long,
    val hapticAtMs: Long,
    val exhaleStartMs: Long,
    val completeAtMs: Long,
)

internal fun breathTransitionPlan(
    reduceMotion: Boolean,
    breathMs: Long = PilgrimMotion.BREATH_MS.toLong(),
): BreathTransitionPlan {
    if (reduceMotion) {
        return BreathTransitionPlan(
            reduceMotion = true,
            breathMs = breathMs,
            inhaleStartMs = 0L,
            hapticAtMs = 0L,
            exhaleStartMs = 0L,
            completeAtMs = REDUCE_MOTION_HOLD_MS,
        )
    }
    val inhaleStart = INHALE_START_MS
    val exhaleStart = inhaleStart + breathMs + EXHALE_GAP_MS
    return BreathTransitionPlan(
        reduceMotion = false,
        breathMs = breathMs,
        inhaleStartMs = inhaleStart,
        hapticAtMs = inhaleStart + breathMs,
        exhaleStartMs = exhaleStart,
        completeAtMs = exhaleStart + breathMs,
    )
}

private const val REDUCE_MOTION_HOLD_MS = 500L
private const val INHALE_START_MS = 1_200L
private const val EXHALE_GAP_MS = 300L
private const val FADE_IN_MS = 1_000

/**
 * Full-screen contemplative beat shown after permissions, before the
 * app proper — a single inhale/exhale of the Pilgrim logo over a
 * warming parchment. iOS `BreathTransitionView`.
 */
@Composable
fun BreathTransitionScreen(onComplete: () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val plan = remember(reduceMotion) { breathTransitionPlan(reduceMotion) }
    val breathMs = plan.breathMs.toInt()
    val currentOnComplete by rememberUpdatedState(onComplete)
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val parchment = pilgrimColors.parchment
    val a11yLabel = stringResource(R.string.breath_transition_a11y)

    // Absorb back during the beat — iOS SetupCoordinator presents this
    // modally with no dismiss; a stray back press must not skip it.
    BackHandler {}

    // Completion must fire exactly once, and not be lost if the timeline
    // ends while the app is backgrounded (below STARTED). A backgrounded
    // finish parks in pendingComplete and is replayed on ON_RESUME; the
    // AtomicBoolean keeps it single-shot across both paths.
    val done = remember { AtomicBoolean(false) }
    var pendingComplete by remember { mutableStateOf(false) }
    val finish = {
        if (!done.get()) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (done.compareAndSet(false, true)) currentOnComplete()
            } else {
                pendingComplete = true
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingComplete && !done.get()) {
                finish()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val logoAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val logoScale = remember { Animatable(if (reduceMotion) 1f else 0.9f) }
    val warmthAlpha = remember { Animatable(0.02f) }

    LaunchedEffect(Unit) {
        if (plan.reduceMotion) {
            delay(plan.completeAtMs)
            finish()
            return@LaunchedEffect
        }
        launch { logoAlpha.animateTo(1f, tween(FADE_IN_MS, easing = EaseInOut)) }
        launch { logoScale.animateTo(1f, tween(FADE_IN_MS, easing = EaseInOut)) }
        delay(plan.inhaleStartMs)
        launch { logoScale.animateTo(1.04f, tween(breathMs, easing = EaseInOut)) }
        launch { warmthAlpha.animateTo(0.04f, tween(breathMs, easing = EaseInOut)) }
        delay(plan.hapticAtMs - plan.inhaleStartMs)
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        delay(plan.exhaleStartMs - plan.hapticAtMs)
        launch { logoScale.animateTo(0.95f, tween(breathMs, easing = EaseInOut)) }
        launch { logoAlpha.animateTo(0f, tween(breathMs, easing = EaseInOut)) }
        launch { warmthAlpha.animateTo(0f, tween(breathMs, easing = EaseInOut)) }
        delay(plan.completeAtMs - plan.exhaleStartMs)
        finish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = a11yLabel }
            .background(parchment)
            .drawBehind {
                drawRect(Color.Yellow.copy(alpha = warmthAlpha.value))
            },
        contentAlignment = Alignment.Center,
    ) {
        PilgrimLogo(
            size = 80.dp,
            modifier = Modifier.graphicsLayer {
                alpha = logoAlpha.value
                scaleX = logoScale.value
                scaleY = logoScale.value
            },
        )
    }
}
