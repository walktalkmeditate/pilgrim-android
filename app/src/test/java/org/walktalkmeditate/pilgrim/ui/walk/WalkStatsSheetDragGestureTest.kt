// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
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
class WalkStatsSheetDragGestureTest {

    @get:Rule val composeRule = createComposeRule()

    private fun renderHost(
        initialState: SheetState,
        walkState: WalkState = WalkState.Active(WalkAccumulator(1L, 0L)),
        captureState: ((SheetState) -> Unit) = {},
    ): () -> SheetState {
        var state by mutableStateOf(initialState)
        composeRule.setContent {
            WalkStatsSheet(
                state = state,
                onStateChange = {
                    state = it
                    captureState(it)
                },
                walkState = walkState,
                totalElapsedMillis = 0L, distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {}, onStartWalk = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
                modifier = Modifier
                    .testTag("walk-sheet-root")
                    .height(400.dp),
            )
        }
        return { state }
    }

    @Test
    fun `swipe up from minimized expands the sheet`() {
        val getState = renderHost(SheetState.Minimized)
        composeRule.onNodeWithTag("walk-sheet-root").performTouchInput {
            swipeUp(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Expanded, getState())
    }

    @Test
    fun `swipe down from expanded collapses the sheet`() {
        val getState = renderHost(SheetState.Expanded)
        composeRule.onNodeWithTag("walk-sheet-root").performTouchInput {
            swipeDown(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Minimized, getState())
    }

    @Test
    fun `drag does not transition when canDrag is false (Paused)`() {
        val getState = renderHost(
            initialState = SheetState.Minimized,
            walkState = WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L),
        )
        composeRule.onNodeWithTag("walk-sheet-root").performTouchInput {
            swipeUp(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Minimized, getState())
    }
}
