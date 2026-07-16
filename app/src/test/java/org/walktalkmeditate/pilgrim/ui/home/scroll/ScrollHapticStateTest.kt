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

    // ---- Dot-kind vocabulary (U16) — mirrors iOS
    // ScrollHapticEngineTests.swift@c1745e8 (dots [100,200,300,500],
    // sizes [10,20,10,20], kinds [plain, gate, cairn, plain]).

    private fun kindState() = ScrollHapticState(
        dotPositionsPx = listOf(100f, 200f, 300f, 500f),
        dotSizesPx = listOf(10f, 20f, 10f, 20f),
        milestonePositionsPx = emptyList(),
        dotKinds = listOf(
            DotHapticKind.Plain,
            DotHapticKind.Gate,
            DotHapticKind.Cairn,
            DotHapticKind.Plain,
        ),
        largeDotCutoffPx = 15f,
        dotThresholdPx = 20f,
        milestoneThresholdPx = 25f,
    )

    @Test
    fun `gate dot fires the gate event regardless of size`() {
        // Dot 1 is size 20 (large — would be Heavy); the gate kind wins.
        val event = kindState().handleViewportCenterPx(200f)
        assertEquals(HapticEvent.GateDot(1), event)
    }

    @Test
    fun `cairn dot fires the cairn event`() {
        val event = kindState().handleViewportCenterPx(300f)
        assertEquals(HapticEvent.CairnDot(2), event)
    }

    @Test
    fun `plain dots keep the size vocabulary`() {
        val state = kindState()
        assertEquals(HapticEvent.LightDot(0), state.handleViewportCenterPx(100f))
        assertEquals(HapticEvent.HeavyDot(3), state.handleViewportCenterPx(500f))
    }

    @Test
    fun `same dot with a kind does not retrigger inside the window`() {
        val state = kindState()
        assertEquals(HapticEvent.GateDot(1), state.handleViewportCenterPx(200f))
        assertEquals(HapticEvent.None, state.handleViewportCenterPx(205f))
    }

    @Test
    fun `missing kinds fall back to size`() {
        // No kinds configured = the old vocabulary.
        val state = ScrollHapticState(
            dotPositionsPx = listOf(100f),
            dotSizesPx = listOf(20f),
            milestonePositionsPx = emptyList(),
        )
        assertEquals(HapticEvent.HeavyDot(0), state.handleViewportCenterPx(100f))
    }
}
