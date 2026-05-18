// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Manual-QA batch 1, BUG 2: tapping Leave from the pre-walk surface
 * did nothing.
 *
 * The Leave-confirm action ([performLeaveWalk]) mirrors iOS
 * `MainCoordinatorView.cancelWalk()`: it MUST always purge walk state
 * AND navigate away, regardless of whether a walk was ever in
 * progress. The previous code relied on the `hasSeenInProgress`-gated
 * Idle observer for the navigation, which never fired on the pre-walk
 * (Finished / Idle) path.
 */
class ActiveWalkLeaveTest {

    @Test
    fun `Leave from pre-walk (Finished) purges state and navigates`() {
        val calls = mutableListOf<String>()
        performLeaveWalk(
            discardWalk = { calls += "discard" },
            onDiscarded = { calls += "discarded" },
        )
        assertEquals(listOf("discard", "discarded"), calls)
    }

    @Test
    fun `Leave from Active still purges and navigates (in-walk path unchanged)`() {
        val calls = mutableListOf<String>()
        performLeaveWalk(
            discardWalk = { calls += "discard" },
            onDiscarded = { calls += "discarded" },
        )
        assertEquals(listOf("discard", "discarded"), calls)
    }

    @Test
    fun `discard runs before navigation so the FGS stops before the screen pops`() {
        val order = mutableListOf<Int>()
        performLeaveWalk(
            discardWalk = { order += 1 },
            onDiscarded = { order += 2 },
        )
        assertEquals(listOf(1, 2), order)
    }
}
