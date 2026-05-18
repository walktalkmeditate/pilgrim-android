// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Manual-QA batch 1, BUG 1: the pre-walk options sheet appeared empty.
 *
 * Covers the two predicates extracted from the `ActiveWalkScreen`
 * `LaunchedEffect(navWalkState::class)` block and the options-sheet
 * `canSetIntention` argument so the iOS-parity rules
 * (`WalkOptionsSheet.swift:46`, `ActiveWalkView.swift` dismissal
 * latch) are verified without standing up Compose + Hilt + Mapbox.
 */
class ActiveWalkScreenSheetGateTest {

    private val accumulator = WalkAccumulator(walkId = 1L, startedAt = 1_000L)
    private val active: WalkState = WalkState.Active(accumulator)
    private val paused: WalkState = WalkState.Paused(accumulator, pausedAt = 2_000L)
    private val meditating: WalkState =
        WalkState.Meditating(accumulator, meditationStartedAt = 2_000L)
    private val finished: WalkState = WalkState.Finished(accumulator, endedAt = 5_000L)

    @Test
    fun `Set Intention available pre-walk while Idle`() {
        assertTrue(canSetIntentionForState(WalkState.Idle))
    }

    @Test
    fun `Set Intention available pre-walk while Finished (Done then Wander again)`() {
        // iOS WalkOptionsSheet.swift:46 gates on `!isRecording`, true
        // for the post-summary Finished state the @Singleton controller
        // stays in. The old `is WalkState.Idle` check left the sheet
        // empty on this very common path.
        assertTrue(canSetIntentionForState(finished))
    }

    @Test
    fun `Set Intention hidden once a walk is in progress`() {
        assertFalse(canSetIntentionForState(active))
        assertFalse(canSetIntentionForState(paused))
        assertFalse(canSetIntentionForState(meditating))
    }

    @Test
    fun `does not force-dismiss sheets on a steady pre-walk Finished surface`() {
        // hasSeenInProgress is false on a fresh pre-walk composition
        // (Done → Wander again lands here). The freshly-opened options
        // sheet must survive.
        assertFalse(shouldForceDismissInWalkSheets(finished, hasSeenInProgress = false))
        assertFalse(shouldForceDismissInWalkSheets(WalkState.Idle, hasSeenInProgress = false))
    }

    @Test
    fun `force-dismisses sheets when a walk that WAS in progress leaves Active`() {
        assertTrue(shouldForceDismissInWalkSheets(finished, hasSeenInProgress = true))
        assertTrue(shouldForceDismissInWalkSheets(meditating, hasSeenInProgress = true))
        assertTrue(shouldForceDismissInWalkSheets(WalkState.Idle, hasSeenInProgress = true))
    }

    @Test
    fun `never force-dismisses while the walk is still Active or Paused`() {
        assertFalse(shouldForceDismissInWalkSheets(active, hasSeenInProgress = true))
        assertFalse(shouldForceDismissInWalkSheets(paused, hasSeenInProgress = true))
    }
}
