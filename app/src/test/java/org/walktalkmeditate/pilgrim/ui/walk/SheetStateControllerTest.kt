// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SheetStateControllerTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Active emission triggers Minimized`() {
        val captures = mutableListOf<SheetState>()
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L)),
                onUpdateState = { captures += it },
            )
        }
        composeRule.waitForIdle()
        assertEquals(listOf(SheetState.Minimized), captures)
    }

    @Test
    fun `Meditating emission triggers Expanded immediately`() {
        val captures = mutableListOf<SheetState>()
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Meditating(
                    walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
                    meditationStartedAt = 1_000L,
                ),
                onUpdateState = { captures += it },
            )
        }
        composeRule.waitForIdle()
        assertEquals(listOf(SheetState.Expanded), captures)
    }

    @Test
    fun `Paused emission Expanded fires after 800ms`() {
        val captures = mutableListOf<SheetState>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Paused(
                    walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
                    pausedAt = 5_000L,
                ),
                onUpdateState = { captures += it },
            )
        }
        composeRule.mainClock.advanceTimeBy(799L, ignoreFrameDuration = true)
        assertEquals(emptyList<SheetState>(), captures)
        composeRule.mainClock.advanceTimeBy(2L, ignoreFrameDuration = true)
        assertEquals(listOf(SheetState.Expanded), captures)
    }

    @Test
    fun `Paused then Active within debounce cancels Expanded and fires Minimized`() {
        val captures = mutableListOf<SheetState>()
        var state: WalkState by mutableStateOf<WalkState>(
            WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L)
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SheetStateController(walkState = state, onUpdateState = { captures += it })
        }
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.runOnUiThread {
            state = WalkState.Active(WalkAccumulator(1L, 0L))
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(1_000L)
        assertEquals(listOf(SheetState.Minimized), captures)
    }
}
