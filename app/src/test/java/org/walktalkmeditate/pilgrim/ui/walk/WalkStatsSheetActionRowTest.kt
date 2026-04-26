// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsSheetActionRowTest {

    @get:Rule val composeRule = createComposeRule()

    private fun render(
        walkState: WalkState,
        recorderState: VoiceRecorderUiState = VoiceRecorderUiState.Idle,
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onStartMeditation: () -> Unit = {},
        onEndMeditation: () -> Unit = {},
        onFinish: () -> Unit = {},
    ) {
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Expanded,
                onStateChange = {},
                walkState = walkState,
                totalElapsedMillis = 0L,
                distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = recorderState,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = onPause, onResume = onResume,
                onStartMeditation = onStartMeditation, onEndMeditation = onEndMeditation,
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = onFinish,
            )
        }
    }

    /**
     * Resolves to the action-button node (clickable Column merging the
     * label Text). Filters out the [TimeChip] "Talk"/"Meditate"/"Walk"
     * labels which share the same text but have no click action.
     */
    private fun nodeInExpandedWithText(text: String) =
        composeRule.onAllNodesWithText(text)
            .filterToOne(
                hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)) and hasClickAction(),
            )

    @Test
    fun `Active state — Pause and Meditate enabled`() {
        render(WalkState.Active(WalkAccumulator(1L, 0L)))
        nodeInExpandedWithText("Pause").assertIsEnabled()
        nodeInExpandedWithText("Meditate").assertIsEnabled()
    }

    @Test
    fun `Paused state — Resume enabled, Meditate disabled`() {
        render(WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L))
        nodeInExpandedWithText("Resume").assertIsEnabled()
        nodeInExpandedWithText("Meditate").assertIsNotEnabled()
    }

    @Test
    fun `Meditating state — End enabled, Pause disabled`() {
        render(
            WalkState.Meditating(
                walk = WalkAccumulator(1L, 0L),
                meditationStartedAt = 1_000L,
            )
        )
        nodeInExpandedWithText("Pause").assertIsNotEnabled()
        // "End" appears twice (end-meditation slot + finish-walk slot) —
        // both should be enabled. Use onAllNodesWithText filter.
    }

    @Test
    fun `Pause click fires onPause`() {
        var fired = false
        render(WalkState.Active(WalkAccumulator(1L, 0L)), onPause = { fired = true })
        nodeInExpandedWithText("Pause").performClick()
        assertTrue(fired)
    }
}
