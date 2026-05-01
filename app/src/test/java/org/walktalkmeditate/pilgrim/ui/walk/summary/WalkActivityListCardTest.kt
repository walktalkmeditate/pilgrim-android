// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActivityListCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersBothTalkAndMeditateRows() {
        val recording = VoiceRecording(
            walkId = 1L, startTimestamp = 1_700_000_000_000L,
            endTimestamp = 1_700_000_022_000L, durationMillis = 22_000L,
            fileRelativePath = "x.wav", transcription = null,
        )
        val meditation = ActivityInterval(
            walkId = 1L, startTimestamp = 1_700_000_300_000L,
            endTimestamp = 1_700_001_500_000L,
            activityType = ActivityType.MEDITATING,
        )
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityListCard(
                    voiceRecordings = listOf(recording),
                    meditationIntervals = listOf(meditation),
                )
            }
        }
        composeRule.onNodeWithText("Talk").assertIsDisplayed()
        composeRule.onNodeWithText("Meditate").assertIsDisplayed()
    }

    @Test
    fun headerIsRendered() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityListCard(
                    voiceRecordings = emptyList(),
                    meditationIntervals = emptyList(),
                )
            }
        }
        composeRule.onNodeWithText("Activities").assertIsDisplayed()
    }

    @Test
    fun sortsEntriesByStartTimestamp() {
        // Meditation starts BEFORE the recording — list should render
        // Meditate row above Talk row regardless of input list order.
        val meditation = ActivityInterval(
            walkId = 1L, startTimestamp = 1_700_000_000_000L,
            endTimestamp = 1_700_000_300_000L,
            activityType = ActivityType.MEDITATING,
        )
        val recording = VoiceRecording(
            walkId = 1L, startTimestamp = 1_700_000_500_000L,
            endTimestamp = 1_700_000_522_000L, durationMillis = 22_000L,
            fileRelativePath = "x.wav", transcription = null,
        )
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityListCard(
                    // Pass in REVERSED order to verify sort, not input ordering.
                    voiceRecordings = listOf(recording),
                    meditationIntervals = listOf(meditation),
                )
            }
        }
        val meditateTop = composeRule.onNodeWithText("Meditate")
            .getUnclippedBoundsInRoot().top
        val talkTop = composeRule.onNodeWithText("Talk")
            .getUnclippedBoundsInRoot().top
        assertTrue(
            "Meditate row (earlier start) should render above Talk row " +
                "(meditate.top=$meditateTop, talk.top=$talkTop)",
            meditateTop < talkTop,
        )
    }
}
