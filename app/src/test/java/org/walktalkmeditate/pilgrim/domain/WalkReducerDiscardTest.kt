// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WalkReducerDiscardTest {

    @Test
    fun `Discard from Active transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Active(WalkAccumulator(walkId = 42L, startedAt = 0L))
        val (next, effect) = WalkReducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 42L), effect)
    }

    @Test
    fun `Discard from Paused transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 7L, startedAt = 0L),
            pausedAt = 100L,
        )
        val (next, effect) = WalkReducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 7L), effect)
    }

    @Test
    fun `Discard from Meditating transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 9L, startedAt = 0L),
            meditationStartedAt = 500L,
        )
        val (next, effect) = WalkReducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 9L), effect)
    }

    @Test
    fun `Discard from Idle is a no-op`() {
        val (next, effect) = WalkReducer.reduce(WalkState.Idle, WalkAction.Discard(at = 1_000L))
        assertSame(WalkState.Idle, next)
        assertEquals(WalkEffect.None, effect)
    }

    @Test
    fun `Discard from Finished is a no-op (walk already saved)`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 3L, startedAt = 0L),
            endedAt = 5_000L,
        )
        val (next, effect) = WalkReducer.reduce(state, WalkAction.Discard(at = 6_000L))
        assertSame(state, next)
        assertEquals(WalkEffect.None, effect)
    }
}
