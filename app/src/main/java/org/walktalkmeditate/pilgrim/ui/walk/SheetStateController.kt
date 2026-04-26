// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.domain.WalkState

private const val PAUSE_DEBOUNCE_MS = 800L

/**
 * Drives [SheetState] transitions from observed [walkState] changes.
 *
 * Pause-debounce is implemented purely through `LaunchedEffect`'s
 * cancel-on-key-change semantics: when the state flips back to Active
 * mid-debounce, the in-flight Paused coroutine is cancelled BEFORE
 * `delay()` returns, the new Active coroutine launches, and immediately
 * calls `Minimized`. There is no "re-check after delay" branch — that
 * pattern doesn't work because the captured `walkState` parameter is
 * whatever it was at launch time.
 *
 * Key on `walkState::class` so location-sample-driven Active → Active
 * recompositions don't re-fire the Minimized side-effect.
 */
@Composable
fun SheetStateController(
    walkState: WalkState,
    onUpdateState: (SheetState) -> Unit,
) {
    val onUpdate by rememberUpdatedState(onUpdateState)
    LaunchedEffect(walkState::class) {
        when (walkState) {
            is WalkState.Active -> onUpdate(SheetState.Minimized)
            is WalkState.Paused -> {
                delay(PAUSE_DEBOUNCE_MS)
                onUpdate(SheetState.Expanded)
            }
            is WalkState.Meditating -> onUpdate(SheetState.Expanded)
            WalkState.Idle, is WalkState.Finished -> Unit
        }
    }
}
