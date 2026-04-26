// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetSavedStateTest {

    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun Harness() {
        var state by rememberSaveable { mutableStateOf(SheetState.Expanded) }
        // Marker text observable from outside the sheet.
        Text(text = "marker:${state.name}")
        WalkStatsSheet(
            state = state,
            onStateChange = { state = it },
            walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
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

    @Test
    fun `SheetState survives recreation via StateRestorationTester`() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent { Harness() }
        // Initial: Expanded.
        composeRule.onNodeWithText("marker:Expanded").assertExists()
        // Drag-collapse: swipe down to switch to Minimized.
        composeRule.onNodeWithTag("walk-sheet-root").performTouchInput {
            swipeDown(durationMillis = 150)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("marker:Minimized").assertExists()
        // Trigger config-change-style state restoration.
        tester.emulateSavedInstanceStateRestore()
        // Assert the state was preserved.
        composeRule.onNodeWithText("marker:Minimized").assertExists()
    }
}
