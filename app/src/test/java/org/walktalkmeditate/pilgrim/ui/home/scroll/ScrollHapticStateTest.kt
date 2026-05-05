// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollHapticStateTest {

    private val dotsPx = listOf(100f, 200f, 300f, 400f)
    private val sizesPx = listOf(10f, 16f, 12f, 20f) // index 1 + 3 are "large"
    private val milestonesPx = listOf(250f)

    private fun newState() = ScrollHapticState(
        dotPositionsPx = dotsPx,
        dotSizesPx = sizesPx,
        milestonePositionsPx = milestonesPx,
        largeDotCutoffPx = 15f,
        dotThresholdPx = 20f,
        milestoneThresholdPx = 25f,
    )

    @Test
    fun `light dot fires inside 20px of small dot`() {
        val state = newState()
        // viewport center at 110 → distance 10 from dot 0 (size 10 = small)
        val event = state.handleViewportCenterPx(110f)
        assertTrue(event is HapticEvent.LightDot)
        assertEquals(0, (event as HapticEvent.LightDot).dotIndex)
    }

    @Test
    fun `heavy dot fires for large dot inside 20px`() {
        val state = newState()
        val event = state.handleViewportCenterPx(195f) // dot 1 size 16 = large
        assertTrue(event is HapticEvent.HeavyDot)
    }

    @Test
    fun `dot does not refire when same dot still in window`() {
        val state = newState()
        val first = state.handleViewportCenterPx(105f)
        assertTrue(first !is HapticEvent.None)
        val second = state.handleViewportCenterPx(110f)
        assertEquals(HapticEvent.None, second)
    }

    @Test
    fun `dot rearms after leaving window`() {
        val state = newState()
        state.handleViewportCenterPx(100f)
        state.handleViewportCenterPx(150f) // outside dot 0 window
        val refire = state.handleViewportCenterPx(100f)
        assertTrue(refire is HapticEvent.LightDot)
    }

    @Test
    fun `milestone fires inside 25px window`() {
        val state = newState()
        val event = state.handleViewportCenterPx(255f)
        assertTrue(event is HapticEvent.Milestone)
    }

    @Test
    fun `outside any window emits None`() {
        val state = newState()
        assertEquals(HapticEvent.None, state.handleViewportCenterPx(50f))
    }
}
