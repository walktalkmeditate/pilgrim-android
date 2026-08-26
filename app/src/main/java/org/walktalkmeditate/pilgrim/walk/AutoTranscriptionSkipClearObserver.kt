// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.isInProgress

/**
 * Two of the parity spec's five [AutoTranscriptionSkipState] clear-sites
 * (iOS `MainCoordinatorView` UI-12/UI-13) — the two that are pure
 * [WalkState] transitions, so one app-scoped observer covers both without
 * a call site at every walk-start/discard entry point:
 *
 *  1. **walk start** (iOS `startWalk()`) — a transition INTO [WalkState.Active]
 *     from a NOT-in-progress state (Idle or Finished). Guarding on the
 *     previous state, not just "now Active", excludes `resumeWalk()`'s
 *     Paused → Active, which is not a fresh start. Defense-in-depth,
 *     matching iOS's own reasoning: a stale flag from a walk whose
 *     summary the user never visited (site 4, [WalkSummaryViewModel]'s
 *     `onCleared`) would otherwise leak into the NEXT walk's summary in
 *     the same app session.
 *  2. **walk cancel/discard** (iOS `cancelWalk()`) — a transition INTO
 *     [WalkState.Idle]. Unconditional (not gated on the previous state),
 *     matching [WalkLifecycleObserver]'s own reasoning for the same
 *     transition: StateFlow conflation can elide an in-progress emission,
 *     so an Idle-gated-on-previous-state check can miss the transition
 *     entirely.
 *
 * The other three sites do not fit this shape and live elsewhere:
 *  - **active-walk dismiss, no-pending-summary branch** — collapses INTO
 *    site 2 on Android. iOS's sheet-dismiss callback fires for both the
 *    cancel path AND the finish path, with the pending-snapshot check
 *    distinguishing which; Android's nav model has no such single
 *    dismiss callback — `ActiveWalkScreen` calls exactly one of
 *    `onFinished` (transition to Finished — deliberately NOT a clear
 *    site, so the flag survives into the summary) or `onDiscarded`
 *    (transition to Idle — this class's site 2). There is no third way
 *    to leave the screen to reproduce iOS's "else" branch against.
 *  - **walk-summary dismiss** — `WalkSummaryViewModel.onCleared()`
 *    (fires when the summary's NavBackStackEntry-scoped ViewModel is
 *    torn down — Done tap or back/scrim dismiss both pop the entry).
 *  - **manual transcribe-all, non-empty results** —
 *    `WalkSummaryViewModel.transcribePendingRecordings()`'s landed-ids
 *    observer.
 */
@Singleton
class AutoTranscriptionSkipClearObserver @Inject constructor(
    @WalkFinalizationObservedState walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @WalkFinalizationScope private val scope: CoroutineScope,
    private val skipState: AutoTranscriptionSkipState,
) {
    init {
        scope.launch {
            var firstEmission = true
            var previousInProgress = false
            walkState.collect { state ->
                if (firstEmission) {
                    firstEmission = false
                    previousInProgress = state.isInProgress
                    return@collect
                }
                when {
                    state is WalkState.Idle -> skipState.clear()
                    state is WalkState.Active && !previousInProgress -> skipState.clear()
                    else -> Unit
                }
                previousInProgress = state.isInProgress
            }
        }
    }
}
