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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
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
        manualTranscribingIds: Set<Long> = emptySet(),
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
                    manualTranscribingIds = manualTranscribingIds,
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

    // Defensive rendering only — the mapper routes the no-usable-model
    // cell to ManualPreparing, so production never shows this chip
    // disabled (the v1.3.0 QA finding).
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
    fun manualPreparing_showsPreparingCaption_andOpensSheet_neverWaitingLanguage() {
        var sheetOpens = 0
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.ManualPreparing(
                WhisperModelState.Downloading(
                    bytesDownloaded = 42_000_000L,
                    totalBytes = 148_000_000L,
                ),
            ),
            onOpenModelDownloadSheet = { sheetOpens++ },
        )
        composeRule.onNodeWithText("Preparing transcription model…")
            .assertExists()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, sheetOpens) }
        // Pref-off rows must never speak waiting language (the honesty
        // rule: nothing was promised) — the sheet carries the detail.
        assertTrue(
            composeRule.onAllNodesWithText("Waiting", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("waiting", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // --- Post-press "Transcribing…" feedback (v1.3.0 QA finding) -------

    @Test
    fun manualTranscribingRow_showsTranscribing_insteadOfPendingSubstate() {
        render(
            recording = baseRecording.copy(transcription = null),
            pendingSubstate = PendingTranscriptionSubstate.ManualPending(
                transcribeEnabled = true,
            ),
            manualTranscribingIds = setOf(baseRecording.id),
        )
        composeRule.onNodeWithText("Transcribing…").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("Transcribe")
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("Not yet transcribed")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun manualTranscribingSet_ignoredOnceTheTranscriptLands() {
        render(
            recording = baseRecording,
            manualTranscribingIds = setOf(baseRecording.id),
        )
        composeRule.onNodeWithText("short transcription").assertExists()
        assertTrue(
            composeRule.onAllNodesWithText("Transcribing…")
                .fetchSemanticsNodes().isEmpty(),
        )
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

    // --- Icon-cluster spacing (user product decision 2026-08-18: way
    // closer — see ICON_CLUSTER_TOUCH_TARGET's doc comment in
    // VoiceRecordingsSection.kt) ----------------------------------------

    // Round-2 device QA: even the round-1 iOS-parity fix (4dp coded gap)
    // still read as huge gaps next to a compact transcript, because
    // `minimumInteractiveComponentSize()`'s 48dp default dominated the
    // visual pitch regardless of the coded gap. The fix scopes
    // `LocalMinimumInteractiveComponentSize` down to iOS's own 44pt
    // tap-target floor for just this cluster (still a genuine ≥44dp
    // touch target per icon — a11y preserved, not shrunk) and shrinks
    // the icon box to Material's bare 24dp, then overlaps consecutive
    // 44dp touch boxes with a negative Arrangement gap so the VISUAL
    // icons land at the target ~32dp pitch (24dp icon + ~8dp gap).
    // Robolectric computes real layout/measure/place (only Canvas
    // *painting* is stubbed — Stage 3-C lesson), so the gap between each
    // icon's semantics bounds is a reliable, non-flaky assertion.
    //
    // Each icon's `minimumInteractiveComponentSize()` reservation is
    // symmetric, so it doesn't move the icon's OWN semantics bounds
    // (still exactly [ICON_CLUSTER_VISUAL_SIZE], confirmed empirically
    // by the round-1 version of this same test) — the measured distance
    // between two consecutive icons' bounds is
    // `(ICON_CLUSTER_TOUCH_TARGET + ICON_CLUSTER_ARRANGEMENT_GAP) -
    // ICON_CLUSTER_VISUAL_SIZE`.
    @Test
    fun transcriptionActionIcons_useTightenedSpacing() {
        render()
        val pencil = composeRule.onNodeWithContentDescription("Edit transcription")
            .fetchSemanticsNode()
        val copy = composeRule.onNodeWithContentDescription("Copy transcription")
            .fetchSemanticsNode()
        val retranscribe = composeRule.onNodeWithContentDescription("Retranscribe")
            .fetchSemanticsNode()

        val expectedGapPx = with(composeRule.density) {
            (
                ICON_CLUSTER_TOUCH_TARGET + ICON_CLUSTER_ARRANGEMENT_GAP -
                    ICON_CLUSTER_VISUAL_SIZE
                ).toPx()
        }
        val pencilToCopyGap = copy.boundsInRoot.top - pencil.boundsInRoot.bottom
        val copyToRetranscribeGap = retranscribe.boundsInRoot.top - copy.boundsInRoot.bottom

        assertEquals(
            "pencil-to-copy gap must match the tightened cluster pitch",
            expectedGapPx,
            pencilToCopyGap,
            1f,
        )
        assertEquals(
            "copy-to-retranscribe gap must match the tightened cluster pitch",
            expectedGapPx,
            copyToRetranscribeGap,
            1f,
        )
    }

    /**
     * A11y-preservation regression guard for the same directive:
     * shrinking the VISUAL icon box (and letting touch boxes overlap)
     * must not shrink any individual icon's OWN real touch target.
     * Same 48dp framework floor as [playPauseIcon_meets48dpTouchTarget]
     * above (`assertTouchWidthIsEqualTo`/`Height` measure the platform's
     * own touch-bounds inflation, independent of
     * `minimumInteractiveComponentSize()`/[ICON_CLUSTER_TOUCH_TARGET] —
     * confirmed empirically: asserting [ICON_CLUSTER_TOUCH_TARGET]
     * (44dp) here fails with "Actual width is 48.0.dp"). Complements
     * [transcriptionActionIcons_useTightenedSpacing]'s gap assertion —
     * that test alone couldn't tell "genuinely full touch target,
     * tightly packed" apart from "silently shrunk to fit," since both
     * would show the same tightened gap between icon glyphs.
     */
    @Test
    fun transcriptionActionIcons_retainFullTouchTargetDespiteOverlap() {
        render()
        val pencil = composeRule.onNodeWithContentDescription("Edit transcription")
        val copy = composeRule.onNodeWithContentDescription("Copy transcription")
        val retranscribe = composeRule.onNodeWithContentDescription("Retranscribe")
        val platformTouchTargetFloor = 48.dp

        pencil.assertTouchWidthIsEqualTo(platformTouchTargetFloor)
        pencil.assertTouchHeightIsEqualTo(platformTouchTargetFloor)
        copy.assertTouchWidthIsEqualTo(platformTouchTargetFloor)
        copy.assertTouchHeightIsEqualTo(platformTouchTargetFloor)
        retranscribe.assertTouchWidthIsEqualTo(platformTouchTargetFloor)
        retranscribe.assertTouchHeightIsEqualTo(platformTouchTargetFloor)
    }
}
