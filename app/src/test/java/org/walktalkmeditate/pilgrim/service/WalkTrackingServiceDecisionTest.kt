// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.StateAction

/**
 * Pure function tests for the state-collector decision logic in
 * [WalkTrackingService.decideStateAction]. Robolectric service tests
 * would require @HiltAndroidApp + hilt-android-testing infra that
 * doesn't exist in this project; isolating the decision into a pure
 * function lets us verify the contract without that scope creep.
 *
 * Wiring (the running collector calls decideStateAction + applies the
 * result) is covered implicitly by on-device QA.
 */
class WalkTrackingServiceDecisionTest {

    @Test
    fun `Finished is always SelfStop regardless of latch`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        assertEquals(StateAction.SelfStop, WalkTrackingService.decideStateAction(state, false).second)
        assertEquals(StateAction.SelfStop, WalkTrackingService.decideStateAction(state, true).second)
    }

    @Test
    fun `Idle with hasBeenActive=true is SelfStop (discard path)`() {
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = WalkState.Idle,
            hasBeenActive = true,
        )
        assertEquals(StateAction.SelfStop, action)
        assertEquals(true, latch)
    }

    @Test
    fun `Idle with hasBeenActive=false is UpdateNotification (cold-start)`() {
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = WalkState.Idle,
            hasBeenActive = false,
        )
        assertEquals(StateAction.UpdateNotification, action)
        assertEquals(false, latch)
    }

    @Test
    fun `Active sets latch and returns UpdateNotification`() {
        val state = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = state,
            hasBeenActive = false,
        )
        assertEquals(StateAction.UpdateNotification, action)
        assertEquals(true, latch)
    }

    @Test
    fun `Paused sets latch and returns UpdateNotification`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            pausedAt = 100L,
        )
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = state,
            hasBeenActive = false,
        )
        assertEquals(StateAction.UpdateNotification, action)
        assertEquals(true, latch)
    }

    @Test
    fun `Meditating sets latch and returns UpdateNotification`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            meditationStartedAt = 500L,
        )
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = state,
            hasBeenActive = false,
        )
        assertEquals(StateAction.UpdateNotification, action)
        assertEquals(true, latch)
    }
}
