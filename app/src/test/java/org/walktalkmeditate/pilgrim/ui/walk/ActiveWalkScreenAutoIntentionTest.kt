// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Stage 10-C: covers the auto-intention prompt predicate
 * (`shouldAutoPromptIntention`) extracted from the
 * `ActiveWalkScreen` LaunchedEffect so the iOS-parity rule
 * (`ActiveWalkView.swift:374`) is verified without standing up
 * Compose + Hilt + Mapbox in a unit test. The Composable wrapper
 * around the predicate adds a 0.5s `delay` and a `showAutoIntention`
 * bit flip — both straightforward enough to verify by code reading
 * and exercise live during device QA.
 */
class ActiveWalkScreenAutoIntentionTest {

    private val activeAccumulator = WalkAccumulator(walkId = 1L, startedAt = 1_000L)
    private val activeState: WalkState = WalkState.Active(activeAccumulator)

    @Test
    fun `prompts when Active, pref on, intention null, latch unset`() {
        assertTrue(
            shouldAutoPromptIntention(
                walkState = activeState,
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
                walkState = activeState,
                beginWithIntention = false,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt when intention already set (ellipsis-menu pre-walk path)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = activeState,
                beginWithIntention = true,
                intention = "silence",
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt twice for the same walk (latch suppresses re-fire)`() {
        // The latch is set inside the LaunchedEffect after the first
        // fire passes the predicate; subsequent recompositions hit
        // this branch.
        assertFalse(
            shouldAutoPromptIntention(
                walkState = activeState,
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = true,
            ),
        )
    }

    @Test
    fun `does not prompt while Idle (must wait for Active transition)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Idle,
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt while Paused (mirrors iOS only-on-Active gate)`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Paused(activeAccumulator, pausedAt = 2_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt while Meditating`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Meditating(activeAccumulator, meditationStartedAt = 2_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }

    @Test
    fun `does not prompt while Finished`() {
        assertFalse(
            shouldAutoPromptIntention(
                walkState = WalkState.Finished(activeAccumulator, endedAt = 5_000L),
                beginWithIntention = true,
                intention = null,
                hasCheckedAutoIntention = false,
            ),
        )
    }
}
