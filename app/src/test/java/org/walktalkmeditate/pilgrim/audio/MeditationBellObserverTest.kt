// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Unit tests for [MeditationBellObserver] using a counting
 * [FakeBellPlayer] + `MutableStateFlow<WalkState>`. Covers:
 *
 *  - First emission (app-init snapshot) does NOT fire a bell
 *    regardless of state (matches the cold-start Idle or the
 *    restored-session Meditating case).
 *  - Active↔Meditating transitions fire exactly one bell each
 *    direction.
 *  - Meditating→Finished fires one bell (walk finished during
 *    meditation).
 *  - Idle→Active (and other non-meditation transitions) fire zero
 *    bells.
 *  - Full sequence through multiple meditations accumulates the
 *    correct count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeditationBellObserverTest {

    private val acc = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    @Test fun `first emission Idle does not fire bell`() = runTest {
        val s = newScenario(initial = WalkState.Idle)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `first emission Meditating does not fire bell`() = runTest {
        // Restored-session case: the @Singleton controller is already
        // in Meditating state before the observer subscribes. The
        // observer treats this as a non-transition — no bell.
        val s = newScenario(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `Active then Meditating fires one bell`() = runTest {
        val s = newScenario(initial = WalkState.Active(acc))
        advanceUntilIdle()
        s.state.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `Meditating then Active fires one bell`() = runTest {
        val s = newScenario(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        s.state.value = WalkState.Active(acc)
        advanceUntilIdle()
        // First emission (Meditating) skipped as the snapshot; the
        // subsequent transition to Active is the real ring.
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `Meditating then Finished fires one bell`() = runTest {
        val s = newScenario(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        s.state.value = WalkState.Finished(acc, endedAt = 3_000L)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `same-class Meditating re-emission does not fire bell`() = runTest {
        // Guard for a future refactor: if the observer ever compared
        // by value instead of class, two Meditating states with
        // different `meditationStartedAt` timestamps would count as
        // a transition and ring a spurious bell. Class-compare is
        // correct; this test documents the invariant.
        val s = newScenario(initial = WalkState.Meditating(acc, meditationStartedAt = 2_000L))
        advanceUntilIdle()
        s.state.value = WalkState.Meditating(acc, meditationStartedAt = 3_000L)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `Idle then Meditating (restore path) does not fire bell`() = runTest {
        // Real-world scenario: user's walk was mid-meditation when the
        // process was killed. App relaunches: `WalkController` inits
        // to `Idle`. HomeScreen's resume-check calls `restoreActiveWalk`,
        // which writes `Meditating` to the state flow. This is not a
        // user-initiated boundary — the user hasn't tapped Meditate
        // this session. Observer must suppress the bell for this path
        // even though the Meditating state is the second emission
        // (not the first, which is what the generic skip catches).
        val s = newScenario(initial = WalkState.Idle)
        advanceUntilIdle()
        s.state.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `Idle then Active fires zero bells`() = runTest {
        val s = newScenario(initial = WalkState.Idle)
        advanceUntilIdle()
        s.state.value = WalkState.Active(acc)
        advanceUntilIdle()
        // Idle→Active is a walk-start transition, not a meditation
        // boundary. No bell.
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `full sequence fires four bells`() = runTest {
        // Idle → Active → Meditating → Active → Meditating → Finished
        // = 4 meditation boundary transitions → 4 bells.
        val s = newScenario(initial = WalkState.Idle)
        advanceUntilIdle()

        s.state.value = WalkState.Active(acc); advanceUntilIdle()
        assertEquals(0, s.player.playCount)   // Idle→Active, no bell

        s.state.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L); advanceUntilIdle()
        assertEquals(1, s.player.playCount)   // Active→Meditating

        s.state.value = WalkState.Active(acc); advanceUntilIdle()
        assertEquals(2, s.player.playCount)   // Meditating→Active

        s.state.value = WalkState.Meditating(acc, meditationStartedAt = 4_000L); advanceUntilIdle()
        assertEquals(3, s.player.playCount)   // Active→Meditating

        s.state.value = WalkState.Finished(acc, endedAt = 5_000L); advanceUntilIdle()
        assertEquals(4, s.player.playCount)   // Meditating→Finished

        s.cancel()
    }

    // ----- scaffolding ----------------------------------------------

    private class Scenario(
        val state: MutableStateFlow<WalkState>,
        val player: FakeBellPlayer,
        val scope: CoroutineScope,
    ) {
        fun cancel() {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun TestScope.newScenario(initial: WalkState): Scenario {
        val state = MutableStateFlow(initial)
        val fakePlayer = FakeBellPlayer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        MeditationBellObserver(
            walkState = state,
            bellPlayer = fakePlayer,
            scope = scope,
        )
        return Scenario(state, fakePlayer, scope)
    }
}

/** Counts [play] calls. All other behaviors are no-ops. */
private class FakeBellPlayer : BellPlaying {
    var playCount = 0
    override fun play() {
        playCount += 1
    }
}
