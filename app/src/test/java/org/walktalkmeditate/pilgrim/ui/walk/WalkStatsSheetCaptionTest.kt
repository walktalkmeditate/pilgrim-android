// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetCaptionTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `caption renders intention when set`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = "walk well") }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test fun `caption renders fallback when intention is null`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = null) }
        composeRule.onNodeWithText("every step is enough").assertIsDisplayed()
    }

    @Test fun `caption renders fallback when intention is whitespace only`() {
        composeRule.setContent { WalkStatsSheetForCaption(intention = "   ") }
        composeRule.onNodeWithText("every step is enough").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun WalkStatsSheetForCaption(intention: String?) {
        WalkStatsSheet(
            state = SheetState.Expanded,
            onStateChange = {},
            walkState = WalkState.Active(WalkAccumulator(walkId = 1, startedAt = 0L)),
            totalElapsedMillis = 0L,
            distanceMeters = 0.0,
            walkMillis = 0L,
            talkMillis = 0L,
            meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle,
            audioLevel = 0f,
            recordingsCount = 0,
            intention = intention,
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
