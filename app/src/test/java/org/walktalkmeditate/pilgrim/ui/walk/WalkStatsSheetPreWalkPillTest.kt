// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetPreWalkPillTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `pill shows Set an intention text when no draft`() {
        composeRule.setContent {
            HarnessIdle(preWalkIntention = null, onSet = {})
        }
        composeRule.onNodeWithText("Set an intention").assertIsDisplayed()
    }

    @Test fun `pill shows draft text when set`() {
        composeRule.setContent {
            HarnessIdle(preWalkIntention = "walk well", onSet = {})
        }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test fun `pill is clickable and fires onSet`() {
        var fired = false
        composeRule.setContent {
            HarnessIdle(preWalkIntention = null, onSet = { fired = true })
        }
        composeRule.onNodeWithText("Set an intention").performClick()
        assertEquals(true, fired)
    }

    @androidx.compose.runtime.Composable
    private fun HarnessIdle(preWalkIntention: String?, onSet: () -> Unit) {
        WalkStatsSheet(
            state = SheetState.Expanded,
            onStateChange = {},
            walkState = WalkState.Idle,
            totalElapsedMillis = 0L,
            distanceMeters = 0.0,
            walkMillis = 0L,
            talkMillis = 0L,
            meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle,
            audioLevel = 0f,
            recordingsCount = 0,
            intention = null,
            preWalkIntention = preWalkIntention,
            onSetPreWalkIntention = onSet,
            onPause = {},
            onResume = {},
            onStartWalk = {},
            onStartMeditation = {},
            onEndMeditation = {},
            onToggleRecording = {},
            onPermissionDenied = {},
            onDismissError = {},
            onFinish = {},
        )
    }
}
