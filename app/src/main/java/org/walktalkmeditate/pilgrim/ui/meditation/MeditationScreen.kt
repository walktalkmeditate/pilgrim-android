// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * Stage 5-A: contemplative meditation surface. Entered from
 * `ActiveWalkScreen` when the walk state transitions to
 * [WalkState.Meditating]. Breathing circle + session timer + Done
 * button. No audio; no rhythm picker; no sensors. Domain layer
 * (reducer + `MEDITATION_START/END` events + `replayWalkEventTotals`)
 * already handles the accounting.
 *
 * State observer: when the walk state transitions AWAY from
 * Meditating — either because the user tapped Done (→ Active) or
 * because the walk was externally finished (→ Finished) — fires
 * [onEnded] so the NavHost can pop back to ActiveWalk. Mirrors
 * `ActiveWalkScreen`'s Finished→onFinished pattern.
 *
 * Keeps the screen on for the session via
 * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]. Cleared on
 * dispose so a meditation session doesn't leak the flag into
 * subsequent screens.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5a-meditation-core-design.md`.
 */
@Composable
fun MeditationScreen(
    onEnded: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Snapshot the latest `onEnded` so a parent passing a fresh lambda
    // each recomposition doesn't leave us firing a stale closure when
    // the state-observer LaunchedEffect resumes. Same rememberUpdatedState
    // pattern as Stage 4-B's SealRevealOverlay.
    val currentOnEnded by rememberUpdatedState(onEnded)

    // Session timer: start at 0 on screen entry, tick once per second.
    // Intentionally NOT derived from `WalkState.Meditating.meditationStartedAt`
    // — the user's mental model is "the timer started when I saw this
    // screen". Accounting truth lives in the reducer via
    // `totalMeditatedMillis`, unaffected by what the UI displays.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TIMER_TICK_MS)
            elapsedSeconds += 1
        }
    }

    // Observe state transitions AWAY from Meditating. Keyed on state
    // class (not the full state) so Active→Active recompositions on
    // location samples wouldn't re-fire — here state is Meditating on
    // entry so the first composition doesn't fire, only real
    // transitions do.
    LaunchedEffect(ui.walkState::class) {
        if (ui.walkState !is WalkState.Meditating) {
            currentOnEnded()
        }
    }

    // FLAG_KEEP_SCREEN_ON for the duration of the composable.
    // `LocalActivity.current` is the correct Compose accessor (as of
    // androidx.activity.compose 1.10+) — casting `LocalContext.current`
    // to Activity triggers the `ContextCastToActivity` lint error
    // because a Context isn't guaranteed to be an Activity (preview,
    // ComponentDialog, etc.). `LocalActivity` is nullable, which
    // matches the preview / Robolectric case cleanly.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Double-tap guard on Done: `endMeditation` is an async dispatch;
    // the state transition lands a frame or two later. Without the
    // guard, rapid double-taps fire two endMeditation coroutines (the
    // reducer ignores the second — Active has no MeditateEnd branch —
    // but two dismissal animations is messy UX). Also blocks
    // hardware-back from triggering endMeditation after the button
    // tap already did.
    var didEnd by remember { mutableStateOf(false) }
    val endSession: () -> Unit = {
        if (!didEnd) {
            didEnd = true
            viewModel.endMeditation()
        }
    }

    // Intercept hardware back; treat as Done. Without this, back pops
    // to ActiveWalk with the controller still in Meditating, and
    // ActiveWalkScreen's state observer would immediately bounce back
    // to MeditationScreen — oscillation bug.
    BackHandler(enabled = !didEnd) { endSession() }

    val moss = pilgrimColors.moss
    MeditationScreenContent(
        elapsedSeconds = elapsedSeconds,
        mossColor = moss,
        enabled = !didEnd,
        onDone = endSession,
    )
}

/**
 * Pure composable — takes explicit state + colors so tests and
 * previews don't need a `WalkViewModel` or `PilgrimTheme`. Matches
 * the `GoshuinScreenContent` pattern from Stage 4-C.
 */
@Composable
internal fun MeditationScreenContent(
    elapsedSeconds: Int,
    mossColor: Color,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PilgrimSpacing.big),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BreathingCircle(moss = mossColor)
            Spacer(Modifier.height(PilgrimSpacing.big))
            Text(
                text = formatTimer(elapsedSeconds),
                style = pilgrimType.statValue,
                color = pilgrimColors.fog,
            )
        }
        OutlinedButton(
            onClick = onDone,
            enabled = enabled,
            shape = RoundedCornerShape(DONE_BUTTON_CORNER_DP.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.meditation_done),
                style = pilgrimType.button,
                color = pilgrimColors.fog,
            )
        }
    }
}

private fun formatTimer(elapsedSeconds: Int): String {
    val total = elapsedSeconds.coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

private const val TIMER_TICK_MS = 1_000L
private const val DONE_BUTTON_CORNER_DP = 24
