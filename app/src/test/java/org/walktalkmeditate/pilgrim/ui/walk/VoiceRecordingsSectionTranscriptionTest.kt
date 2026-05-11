// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingsSectionTranscriptionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val baseRecording = VoiceRecording(
        id = 1L,
        walkId = 100L,
        fileRelativePath = "voice/100/1.wav",
        startTimestamp = 1_700_000_000_000L,
        endTimestamp = 1_700_000_005_000L,
        durationMillis = 5_000L,
        transcription = "short transcription",
        wordsPerMinute = null,
        isEnhanced = false,
    )

    @Test
    fun shortTranscription_showsFullText_noToggle() {
        composeRule.setContent {
            PilgrimTheme {
                VoiceRecordingsSection(
                    walkStartTimestamp = baseRecording.startTimestamp,
                    recordings = listOf(baseRecording),
                    playbackUiState = PlaybackUiState(
                        playingRecordingId = null,
                        isPlaying = false,
                        errorMessage = null,
                    ),
                    onPlay = {},
                    onPause = {},
                )
            }
        }
        composeRule.onNodeWithText("short transcription").assertExists()
        // Toggle should NOT be present.
        val toggleCount = composeRule.onAllNodesWithText("Show more").fetchSemanticsNodes().size
        assert(toggleCount == 0) { "expected no 'Show more' toggle for short text, got $toggleCount" }
    }

    @Test
    fun longTranscription_collapsedByDefault_showsExpandToggle() {
        val long = "a".repeat(281)
        composeRule.setContent {
            PilgrimTheme {
                VoiceRecordingsSection(
                    walkStartTimestamp = baseRecording.startTimestamp,
                    recordings = listOf(baseRecording.copy(transcription = long)),
                    playbackUiState = PlaybackUiState(
                        playingRecordingId = null,
                        isPlaying = false,
                        errorMessage = null,
                    ),
                    onPlay = {},
                    onPause = {},
                )
            }
        }
        composeRule.onNodeWithText("Show more").assertExists()
    }
}
