// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
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

    private fun render(
        recording: VoiceRecording = baseRecording,
        pendingSubstate: PendingTranscriptionSubstate =
            PendingTranscriptionSubstate.QueuedForProcessing,
        retranscribeEnabled: Boolean = true,
        onManualTranscribe: () -> Unit = {},
        onOpenModelDownloadSheet: () -> Unit = {},
        onRetryModelDownload: () -> Unit = {},
        onRetranscribe: () -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                VoiceRecordingsSection(
                    walkStartTimestamp = recording.startTimestamp,
                    recordings = listOf(recording),
                    playbackUiState = PlaybackUiState(
                        playingRecordingId = null,
                        isPlaying = false,
                        errorMessage = null,
                    ),
                    playbackSpeed = 1.0f,
                    playbackPositionMillisFlow = MutableStateFlow(0L),
                    waveforms = emptyMap(),
                    onPlay = {},
                    onPause = {},
                    onCycleSpeed = {},
                    onSeek = {},
                    onSaveTranscription = { _, _ -> },
                    onRetranscribe = { onRetranscribe() },
                    onEnsureWaveform = { _, _ -> },
                    pendingSubstate = pendingSubstate,
                    retranscribeEnabled = retranscribeEnabled,
                    onManualTranscribe = onManualTranscribe,
                    onOpenModelDownloadSheet = onOpenModelDownloadSheet,
                    onRetryModelDownload = onRetryModelDownload,
                )
            }
        }
    }

    @Test
    fun shortTranscription_showsFullText_noToggle() {
        render()
        composeRule.onNodeWithText("short transcription").assertExists()
        // Toggle should NOT be present.
        val toggleCount = composeRule.onAllNodesWithText("Show more").fetchSemanticsNodes().size
        assert(toggleCount == 0) { "expected no 'Show more' toggle for short text, got $toggleCount" }
    }

    @Test
    fun longTranscription_collapsedByDefault_showsExpandToggle() {
        render(recording = baseRecording.copy(transcription = "a".repeat(281)))
        composeRule.onNodeWithText("Show more").assertExists()
    }

    // AF66 (iOS PR #45) ACCEPTANCE check: the play/pause icon's touch target
    // meets the 48dp a11y minimum. NOTE: Compose's framework
    // minimumTouchTargetSize already inflates ANY clickable's touch bounds to
    // 48dp, so this passes regardless of minimumInteractiveComponentSize — it
    // documents the requirement is met, it does NOT pin the modifier (whose
    // real job is reserving 48dp of LAYOUT, on a non-semantics wrapper, so the
    // adjacent transcription icons don't share hit areas). Overlap/spacing of
    // the adjacent icons is confirmed on-device (matrix needs-device).
    @Test
    fun playPauseIcon_meets48dpTouchTarget() {
        render()
        composeRule.onNodeWithContentDescription("Play")
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
    }

    // --- U11 pending-substate rendering (spec section 3) ---------------

    @Test
    fun manualPending_showsPlainPendingAndTranscribeAffordance_neverDownloadLanguage() {
        var transcribes = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.ManualPending(
                transcribeEnabled = true,
            ),
            onManualTranscribe = { transcribes++ },
        )
        composeRule.onNodeWithText("Not yet transcribed").assertExists()
        composeRule.onNodeWithText("Transcribe").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, transcribes) }
        // Pref-off rows must never speak download language (the lie guard).
        assertTrue(
            composeRule.onAllNodesWithText("Downloading", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("download", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun manualPending_preReady_disablesTranscribeAffordance() {
        var transcribes = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.ManualPending(
                transcribeEnabled = false,
            ),
            onManualTranscribe = { transcribes++ },
        )
        composeRule.onNodeWithText("Transcribe").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, transcribes) }
    }

    @Test
    fun waitingOnDownload_downloading_showsByteProgress_andOpensSheet() {
        var sheetOpens = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.WaitingOnDownload(
                WhisperModelState.Downloading(
                    bytesDownloaded = 42_000_000L,
                    totalBytes = 148_000_000L,
                ),
            ),
            onOpenModelDownloadSheet = { sheetOpens++ },
        )
        composeRule.onNodeWithText("Downloading model — 42 of 148 MB")
            .assertExists()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, sheetOpens) }
    }

    @Test
    fun waitingOnDownload_waitingUnmetered_showsWifiCopy() {
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.WaitingOnDownload(
                WhisperModelState.WaitingUnmetered,
            ),
        )
        composeRule
            .onNodeWithText("Waiting for Wi-Fi to download transcription model")
            .assertExists()
    }

    @Test
    fun queuedForProcessing_showsQueuedCopy() {
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.QueuedForProcessing,
        )
        composeRule.onNodeWithText("Queued for transcription…").assertExists()
    }

    @Test
    fun failedChecksum_showsRetryAction() {
        var retries = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.DownloadFailedChecksum,
            onRetryModelDownload = { retries++ },
        )
        composeRule.onNodeWithText("Model download failed").assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun failedStorage_showsFreeSpaceCopy_andOpensSheet() {
        var sheetOpens = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.DownloadFailedStorage,
            onOpenModelDownloadSheet = { sheetOpens++ },
        )
        composeRule
            .onNodeWithText(
                "Not enough space for the transcription model — free up space to continue",
            )
            .assertExists()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, sheetOpens) }
    }

    // --- U11 retranscribe gating (spec section 5) -----------------------

    @Test
    fun retranscribeIcon_disabledPreReady() {
        var retranscribes = 0
        render(
            retranscribeEnabled = false,
            onRetranscribe = { retranscribes++ },
        )
        composeRule.onNodeWithContentDescription("Retranscribe").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, retranscribes) }
    }

    @Test
    fun retranscribeIcon_enabledWhenReady_firesCallback() {
        var retranscribes = 0
        render(
            retranscribeEnabled = true,
            onRetranscribe = { retranscribes++ },
        )
        composeRule.onNodeWithContentDescription("Retranscribe")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retranscribes) }
    }
}
