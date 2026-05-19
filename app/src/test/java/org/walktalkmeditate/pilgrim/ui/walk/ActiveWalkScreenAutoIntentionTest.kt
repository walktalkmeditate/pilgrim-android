// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Covers the auto-intention prompt predicate
 * (`shouldAutoPromptIntention`) extracted from the `ActiveWalkScreen`
 * pre-walk auto-prompt LaunchedEffect so the iOS-parity rule
 * (`ActiveWalkView.swift:362-379@v1.6.0`) is verified without standing
 * up Compose + Hilt + Mapbox in a unit test.
 *
 * iOS fires the prompt in `.onAppear` on the PRE-walk surface (status
 * .waiting/.ready, BEFORE Start) with the gate purely
 * `!hasCheckedAutoIntention && beginWithIntention && intention == nil`
 * — no active/recording condition. Android's pre-walk surface is
 * `Idle` on a fresh start and `Finished` on the post-summary "wander
 * again" path (the @Singleton controller stays Finished until the next
 * startWalk()), so the gate is `!walkState.isInProgress`, matching the
 * batch-1 [canSetIntentionForState] convention.
 */
class ActiveWalkScreenAutoIntentionTest {

    private val accumulator = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    @Test
    fun `prompts on fresh Idle pre-walk surface, pref on, intention null, latch unset`() {
        assertTrue(
            shouldAutoPromptIntention(
                walkState = WalkState.Idle,
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `prompts on Finished post-summary wander-again pre-walk surface`() {
        // Done → "wander again" leaves the @Singleton controller in
        // Finished until the next startWalk(); iOS fires .onAppear on
        // this surface too, so the auto-prompt must arm here.
        assertTrue(
            shouldAutoPromptIntention(
                walkState = WalkState.Finished(accumulator, endedAt = 5_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt when beginWithIntention is off`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Idle,
                beginWithIntention = false,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt when intention draft already set (ellipsis-menu pre-walk path)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Idle,
                beginWithIntention = true,
                intention = "silence",
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt twice for the same surface (latch suppresses re-fire)`() {
        // The latch is set inside the LaunchedEffect before the delay;
        // subsequent recompositions hit this branch.
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Idle,
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = true,
            ),
        )
    }

    @Test
    fun `does not prompt while Active (pre-walk only — recovery surface)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Active(accumulator),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt while Paused (in progress — not a pre-walk surface)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Paused(accumulator, pausedAt = 2_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt while Meditating (in progress — not a pre-walk surface)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Meditating(accumulator, meditationStartedAt = 2_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }
}
