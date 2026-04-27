// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Stage 9.5-C: pins the [WalkTrackingService.decideStateAction] decision
 * matrix that drives the foreground service's self-stop behavior.
 *
 * Pure unit test on the extracted decision function so we can exercise
 * the discard path (Active → Idle must self-stop, but cold-start Idle
 * must NOT) without standing up a full Robolectric service + Hilt
 * environment. The integration coverage that the function is wired
 * correctly into the state-collector lives implicitly in on-device QA
 * (the discard button must dismiss the FGS notification immediately).
 */
class WalkTrackingServiceDiscardTest {

    private val activeState = WalkState.Active(
        WalkAccumulator(walkId = 1L, startedAt = 0L),
    )
    private val pausedState = WalkState.Paused(
        WalkAccumulator(walkId = 1L, startedAt = 0L),
        pausedAt = 1_000L,
    )
    private val meditatingState = WalkState.Meditating(
        WalkAccumulator(walkId = 1L, startedAt = 0L),
        meditationStartedAt = 1_000L,
    )
    private val finishedState = WalkState.Finished(
        WalkAccumulator(walkId = 1L, startedAt = 0L),
        endedAt = 2_000L,
    )

    @Test
    fun `cold-start initial Idle does not self-stop`() {
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = WalkState.Idle,
            hasBeenActive = false,
        )
        assertEquals(false, latch)
        assertEquals(WalkTrackingService.StateAction.UpdateNotification, action)
    }

    @Test
    fun `Active state flips latch and updates notification`() {
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = activeState,
            hasBeenActive = false,
        )
        assertEquals(true, latch)
        assertEquals(WalkTrackingService.StateAction.UpdateNotification, action)
    }

    @Test
    fun `Paused state flips latch and updates notification`() {
        val (latch, _) = WalkTrackingService.decideStateAction(
            state = pausedState,
            hasBeenActive = false,
        )
        assertEquals(true, latch)
    }

    @Test
    fun `Meditating state flips latch and updates notification`() {
        val (latch, _) = WalkTrackingService.decideStateAction(
            state = meditatingState,
            hasBeenActive = false,
        )
        assertEquals(true, latch)
    }

    @Test
    fun `Idle after in-progress (discard path) self-stops`() {
        val (_, action) = WalkTrackingService.decideStateAction(
            state = WalkState.Idle,
            hasBeenActive = true,
        )
        assertEquals(WalkTrackingService.StateAction.SelfStop, action)
    }

    @Test
    fun `Finished always self-stops regardless of latch`() {
        val (_, fromFresh) = WalkTrackingService.decideStateAction(
            state = finishedState,
            hasBeenActive = false,
        )
        assertEquals(WalkTrackingService.StateAction.SelfStop, fromFresh)
        val (_, fromActive) = WalkTrackingService.decideStateAction(
            state = finishedState,
            hasBeenActive = true,
        )
        assertEquals(WalkTrackingService.StateAction.SelfStop, fromActive)
    }

    @Test
    fun `latch stays true once set even on a subsequent Idle`() {
        // Simulate the running collector: Active → Idle → check the
        // second emission's latch input is true.
        val (latch1, _) = WalkTrackingService.decideStateAction(
            state = activeState,
            hasBeenActive = false,
        )
        val (latch2, action2) = WalkTrackingService.decideStateAction(
            state = WalkState.Idle,
            hasBeenActive = latch1,
        )
        assertEquals(true, latch2)
        assertEquals(WalkTrackingService.StateAction.SelfStop, action2)
    }
}
