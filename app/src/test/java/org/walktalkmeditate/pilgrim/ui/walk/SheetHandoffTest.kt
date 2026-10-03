// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The options sheet's hand-off to "the day", the waypoint, whisper, stone
 * and intention sheets (iOS's 0.3 s `asyncAfter`,
 * `ActiveWalkView.swift:229-305@7c200bf`), and its cancel when the walk
 * finishes, is discarded or goes into meditation inside the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SheetHandoffTest {

    @Test
    fun `the next sheet opens once the options sheet has had its 300 ms to close`() = runTest {
        val handoff = SheetHandoff(this)
        var opened = false

        handoff.open { opened = true }
        advanceTimeBy(SHEET_HANDOFF_DELAY_MS - 1)
        runCurrent()
        assertFalse("not before 300 ms", opened)
        advanceTimeBy(1)
        runCurrent()

        assertTrue(opened)
    }

    @Test
    fun `a walk that leaves Active or Paused inside the window opens nothing`() = runTest {
        val handoff = SheetHandoff(this)
        var opened = false

        handoff.open { opened = true }
        advanceTimeBy(SHEET_HANDOFF_DELAY_MS / 2)
        handoff.cancel()
        advanceUntilIdle()

        assertFalse(opened)
    }

    @Test
    fun `a second row tapped inside the window replaces the first`() = runTest {
        val handoff = SheetHandoff(this)
        val opened = mutableListOf<String>()

        handoff.open { opened += "the day" }
        advanceTimeBy(SHEET_HANDOFF_DELAY_MS / 2)
        handoff.open { opened += "Drop Waypoint" }
        advanceUntilIdle()

        assertEquals(listOf("Drop Waypoint"), opened)
    }
}
