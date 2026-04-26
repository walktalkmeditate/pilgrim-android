// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetExpandedTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `expanded sheet renders timer caption stats and chips`() {
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Expanded,
                onStateChange = {},
                walkState = WalkState.Active(WalkAccumulator(1L, 0L, distanceMeters = 1_234.0)),
                totalElapsedMillis = (1 * 3600 + 23 * 60 + 45) * 1_000L,
                distanceMeters = 1_234.0,
                walkMillis = 60_000L,
                talkMillis = 90_000L,
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

        fun nodeInExpandedWithText(text: String) =
            composeRule.onAllNodesWithText(text)
                .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))

        nodeInExpandedWithText("1:23:45").assertExists()
        nodeInExpandedWithText("every step is enough").assertExists()
        nodeInExpandedWithText("1.23 km").assertExists()
        // Steps + Ascent placeholders inside expanded layer.
        composeRule.onAllNodesWithText("Steps")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))
            .assertExists()
        composeRule.onAllNodesWithText("Ascent")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))
            .assertExists()
        // Three "—" placeholders total: 2 inside expanded (Steps, Ascent) +
        // 1 inside minimized (Steps). Plus meditate chip placeholder for
        // meditateMillis = 0L = "—" inside expanded → 4.
        composeRule.onAllNodesWithText("—")
            .assertCountEquals(4)
        // Chip values via WalkFormat.shortDuration: walk=1:00, talk=1:30
        nodeInExpandedWithText("1:00").assertExists()
        nodeInExpandedWithText("1:30").assertExists()
        // Chip labels.
        composeRule.onAllNodesWithText("Walk")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))
            .assertExists()
        composeRule.onAllNodesWithText("Talk")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))
            .assertExists()
        composeRule.onAllNodesWithText("Meditate")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)))
            .assertExists()
    }
}
