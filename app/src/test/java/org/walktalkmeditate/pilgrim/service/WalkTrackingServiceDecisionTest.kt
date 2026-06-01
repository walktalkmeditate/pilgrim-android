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
    fun `Finished with hasBeenActive=false is UpdateNotification (cached-tracker second-walk path)`() {
        // The bug this test pins: a fresh `:tracker` service starting
        // walk N on a process whose cached @Singleton controller is
        // still in `Finished(walk N-1)` must NOT SelfStop on the first
        // emission. SelfStop here triggers onDestroy → scope.cancel()
        // mid-controller.startWalk, leaving the walk row in Room with
        // state stuck at Finished. End never wires up because
        // reduceFinished(Finish) → effect=None.
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = state,
            hasBeenActive = false,
        )
        assertEquals(StateAction.UpdateNotification, action)
        assertEquals(false, latch)
    }

    @Test
    fun `Finished with hasBeenActive=true is SelfStop (walk just finished)`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 1_000L,
        )
        val (latch, action) = WalkTrackingService.decideStateAction(
            state = state,
            hasBeenActive = true,
        )
        assertEquals(StateAction.SelfStop, action)
        assertEquals(true, latch)
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

    // --- decideStartAction: how startTracking dispatches against the
    // (state, isFreshStart, restored?) tuple. The Finished + isFreshStart
    // case is the second-walk-in-a-session regression these tests pin:
    // the cached :tracker process keeps the controller in Finished after
    // the prior walk wraps; the reducer accepts Finished → Start, so the
    // service must dispatch it too. ----------------------------------

    private val finishedState = WalkState.Finished(
        walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
        endedAt = 1_000L,
    )
    private val activeState = WalkState.Active(WalkAccumulator(walkId = 2L, startedAt = 0L))

    @Test
    fun `Idle + freshStart + no restored walk = StartFresh`() {
        assertEquals(
            WalkTrackingService.StartAction.StartFresh,
            WalkTrackingService.decideStartAction(
                state = WalkState.Idle,
                isFreshStart = true,
                hasRestoredWalk = false,
            ),
        )
    }

    @Test
    fun `Idle + restored walk = AdoptRestored (revival path)`() {
        assertEquals(
            WalkTrackingService.StartAction.AdoptRestored,
            WalkTrackingService.decideStartAction(
                state = WalkState.Idle,
                isFreshStart = false,
                hasRestoredWalk = true,
            ),
        )
    }

    @Test
    fun `Idle + no freshStart + no restored walk = StopNoWalk`() {
        assertEquals(
            WalkTrackingService.StartAction.StopNoWalk,
            WalkTrackingService.decideStartAction(
                state = WalkState.Idle,
                isFreshStart = false,
                hasRestoredWalk = false,
            ),
        )
    }

    @Test
    fun `Finished + freshStart = StartFresh (regression — second walk in cached tracker)`() {
        // The bug this test pins: when the :tracker process survives
        // between walks (the common cached-process case), the
        // @Singleton WalkController stays in Finished after the prior
        // walk. The next UI ACTION_START must still dispatch
        // controller.startWalk — the reducer accepts Finished → Start
        // → startFresh, but startTracking used to gate on Idle only,
        // silently dropping the dispatch and stranding the user on a
        // dead Start button.
        assertEquals(
            WalkTrackingService.StartAction.StartFresh,
            WalkTrackingService.decideStartAction(
                state = finishedState,
                isFreshStart = true,
                hasRestoredWalk = false,
            ),
        )
    }

    @Test
    fun `Finished + no freshStart = StopNoWalk`() {
        // A null-action ACTION_START redelivery into the Finished
        // process must not silently linger — stop the service. There
        // is no walk to revive (Finished walks are closed in Room).
        assertEquals(
            WalkTrackingService.StartAction.StopNoWalk,
            WalkTrackingService.decideStartAction(
                state = finishedState,
                isFreshStart = false,
                hasRestoredWalk = false,
            ),
        )
    }

    @Test
    fun `in-progress states are IgnoreInProgress regardless of freshStart`() {
        listOf(activeState, WalkState.Paused(activeState.walk, pausedAt = 100L)).forEach { state ->
            listOf(true, false).forEach { freshStart ->
                assertEquals(
                    "expected IgnoreInProgress for $state freshStart=$freshStart",
                    WalkTrackingService.StartAction.IgnoreInProgress,
                    WalkTrackingService.decideStartAction(
                        state = state,
                        isFreshStart = freshStart,
                        hasRestoredWalk = false,
                    ),
                )
            }
        }
    }
}
