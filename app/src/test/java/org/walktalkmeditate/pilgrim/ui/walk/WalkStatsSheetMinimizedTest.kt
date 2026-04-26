// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class WalkStatsSheetMinimizedTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `minimized sheet shows time distance and steps placeholder`() {
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Minimized,
                onStateChange = {},
                walkState = WalkState.Active(WalkAccumulator(1L, 0L, distanceMeters = 250.0)),
                totalElapsedMillis = 90_000L,
                distanceMeters = 250.0,
                walkMillis = 90_000L,
                talkMillis = 0L,
                meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        composeRule.onAllNodesWithText("1:30")
            .filterToOne(hasParent(hasTestTag(MINIMIZED_LAYER_TAG)))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("0.25 km")
            .filterToOne(hasParent(hasTestTag(MINIMIZED_LAYER_TAG)))
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Steps")
            .filterToOne(hasParent(hasTestTag(MINIMIZED_LAYER_TAG)))
            .assertIsDisplayed()
    }

    @Test
    fun `tap on minimized invokes onStateChange Expanded`() {
        var newState: SheetState? = null
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Minimized,
                onStateChange = { newState = it },
                walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                totalElapsedMillis = 0L,
                distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        composeRule.onAllNodesWithText("Time")
            .filterToOne(hasParent(hasTestTag(MINIMIZED_LAYER_TAG)))
            .performClick()
        assertEquals(SheetState.Expanded, newState)
    }
}
