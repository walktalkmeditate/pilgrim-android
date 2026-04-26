// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
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
                onPause = {}, onResume = {}, onStartWalk = {},
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
    fun `Active state — Meditate enabled, no Pause button`() {
        render(WalkState.Active(WalkAccumulator(1L, 0L)))
        nodeInExpandedWithText("Meditate").assertIsEnabled()
        // Pause button was removed in Stage 9.5-B device-QA pass to
        // match iOS reference. Verify no clickable "Pause" exists.
        composeRule.onAllNodesWithText("Pause")
            .assertCountEquals(0)
    }

    @Test
    fun `Meditating state — End enabled, Mic enabled`() {
        render(
            WalkState.Meditating(
                walk = WalkAccumulator(1L, 0L),
                meditationStartedAt = 1_000L,
            )
        )
        // Two "End" labels: end-meditation slot + finish-walk slot.
        // Both should be clickable + enabled.
        val endButtons = composeRule.onAllNodesWithText("End")
        endButtons.assertCountEquals(2)
        endButtons[0].assertIsEnabled()
        endButtons[1].assertIsEnabled()
        // Mic ("Talk") stays enabled during meditation per
        // walkState.isInProgress contract.
        composeRule.onAllNodesWithText("Talk")
            .filterToOne(hasAnyAncestor(hasTestTag(EXPANDED_LAYER_TAG)) and hasClickAction())
            .assertIsEnabled()
    }

    @Test
    fun `Meditate click fires onStartMeditation`() {
        var fired = false
        render(
            WalkState.Active(WalkAccumulator(1L, 0L)),
            onStartMeditation = { fired = true },
        )
        nodeInExpandedWithText("Meditate").performClick()
        assertTrue(fired)
    }

    @Test
    fun `Finish click fires onFinish`() {
        var fired = false
        render(
            WalkState.Active(WalkAccumulator(1L, 0L)),
            onFinish = { fired = true },
        )
        nodeInExpandedWithText("End").performClick()
        assertTrue(fired)
    }
}
