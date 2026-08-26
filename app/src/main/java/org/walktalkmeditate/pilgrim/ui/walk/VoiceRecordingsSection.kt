// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.TranscriptionRunner
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionPlaceholder
import org.walktalkmeditate.pilgrim.ui.recordings.transcriptionNeedsExpansion
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * What a null-transcription row should say about itself — the U11
 * substate matrix `f(autoTranscribe pref x model state)` from
 * `docs/parity/2026-07-26-port-download-ux-u11.md` (section 3). The
 * row's existence IS the pending work (U9 C6 re-kicks every
 * null-transcription row), so no third axis is needed.
 */
@Immutable
sealed interface PendingTranscriptionSubstate {

    /**
     * Auto-transcribe is OFF and a usable model is on disk (U10
     * gating: verified base OR the transitional exact-size tiny):
     * nothing is running and nothing was promised, so the row shows a
     * plain "not transcribed" state with a manual Transcribe
     * affordance — never download language. [transcribeEnabled] is
     * always true from the mapper (the no-usable-model cell routes to
     * [ManualPreparing] instead); it stays a field so the rendering
     * keeps a defensive disabled path.
     */
    @Immutable
    data class ManualPending(val transcribeEnabled: Boolean) : PendingTranscriptionSubstate

    /**
     * Auto-transcribe is OFF and no usable model is on disk yet — the
     * manual affordance would be a mute disabled chip (the v1.3.0 QA
     * finding), so the row states the true fact ("Preparing
     * transcription model…") and taps through to [ModelDownloadSheet]
     * for the delivery detail. Honesty rule stands: never the word
     * "waiting" under pref OFF — nothing was promised. [modelState]
     * carries the delivery phase for the display surface.
     */
    @Immutable
    data class ManualPreparing(val modelState: WhisperModelState) : PendingTranscriptionSubstate

    /**
     * Auto-transcribe is ON and the model has not been delivered —
     * the row explains the delivery phase and taps through to
     * [ModelDownloadSheet]. [modelState] is one of Absent / Enqueued /
     * WaitingUnmetered / Downloading / Verifying.
     */
    @Immutable
    data class WaitingOnDownload(val modelState: WhisperModelState) : PendingTranscriptionSubstate

    /** Auto-transcribe ON, model Ready — the worker owns it from here. */
    data object QueuedForProcessing : PendingTranscriptionSubstate

    /**
     * U6: auto-transcribe ON, model Ready, but the walk's post-finish
     * batch never got enqueued — [org.walktalkmeditate.pilgrim.core.threads.BatteryGate]
     * closed at the enqueue site. Distinct from [QueuedForProcessing]:
     * that state promises the worker owns it from here, which is false
     * while the walk-level skip banner is showing — nothing is queued
     * until the user (or a future retry) actually kicks one off.
     * [transcribeEnabled] mirrors [ManualPending]'s shape so the row can
     * offer the same recovery affordance.
     */
    @Immutable
    data class SkippedForBattery(val transcribeEnabled: Boolean) : PendingTranscriptionSubstate

    /** Terminal download failure — row exposes Retry (U9 C5). */
    data object DownloadFailedChecksum : PendingTranscriptionSubstate

    /** Terminal storage failure — row carries the free-space copy. */
    data object DownloadFailedStorage : PendingTranscriptionSubstate
}

/**
 * Pure mapper for the U11 substate matrix — unit-testable without
 * Compose. [modelUsable] is [WhisperModelStore][org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore]'s
 * usability probe: it gates the pref-OFF manual affordance, while
 * [modelState] stays the display surface.
 *
 * [isSkippedForBattery] (U6) only ever reroutes the pref-ON/model-Ready
 * cell — the one case that would otherwise claim "queued" while nothing
 * is. The pref-OFF cells are already honest (a plain "not transcribed" +
 * manual affordance, no queuing claim), and a non-Ready model state is
 * already the true reason nothing has happened yet — the skip flag adds
 * no new information there, so it does not override either.
 */
fun pendingTranscriptionSubstate(
    autoTranscribe: Boolean,
    modelState: WhisperModelState,
    modelUsable: Boolean,
    isSkippedForBattery: Boolean = false,
): PendingTranscriptionSubstate = if (!autoTranscribe) {
    if (modelUsable) {
        PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true)
    } else {
        PendingTranscriptionSubstate.ManualPreparing(modelState)
    }
} else {
    when (modelState) {
        is WhisperModelState.Ready -> if (isSkippedForBattery) {
            PendingTranscriptionSubstate.SkippedForBattery(transcribeEnabled = true)
        } else {
            PendingTranscriptionSubstate.QueuedForProcessing
        }
        WhisperModelState.FailedChecksum -> PendingTranscriptionSubstate.DownloadFailedChecksum
        WhisperModelState.FailedStorage -> PendingTranscriptionSubstate.DownloadFailedStorage
        WhisperModelState.Absent,
        WhisperModelState.Enqueued,
        WhisperModelState.WaitingUnmetered,
        is WhisperModelState.Downloading,
        WhisperModelState.Verifying,
        -> PendingTranscriptionSubstate.WaitingOnDownload(modelState)
    }
}

/**
 * U6 parity spec UI-24: battery icon tinted `dawn`, caption typography,
 * copy pinned verbatim against iOS's literal string. `liveRegion =
 * Polite` (the IntentionSettingSheet countdown precedent) announces the
 * banner's appearance AND its clearing to TalkBack — this is the one
 * surface a walker relying on a screen reader has for "why didn't my
 * walk get transcribed automatically."
 */
@Composable
private fun AutoTranscriptionSkippedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.Battery2Bar,
            contentDescription = null,
            tint = pilgrimColors.dawn,
        )
        Text(
            text = stringResource(R.string.transcription_skipped_battery),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            // IntentionSettingSheet countdown precedent: the modifier
            // lives on the Text itself, not a wrapping container, so the
            // liveRegion property attaches to the semantics node a
            // screen reader (and `onNodeWithText`) actually finds.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * iOS parity `WalkSummaryView.recordingsSection@db4196e` +
 * `VoiceRecordingRow.swift@db4196e`. ParchmentSecondary card wrapping
 * a header row ("Voice Recordings" + count badge) and one row per
 * recording. Each row shows a play/pause control, the "Recording N"
 * title with duration + Enhanced badge, the wall-clock start time, a
 * speed-cycle pill (1x / 1.5x / 2x), a waveform placeholder, and the
 * transcription text in a `parchmentTertiary` container with a
 * right-side action column (copy + retranscribe). Tapping the text
 * enters inline edit mode (BasicTextField + Done button).
 */
@Composable
fun VoiceRecordingsSection(
    walkStartTimestamp: Long,
    recordings: List<VoiceRecording>,
    playbackUiState: PlaybackUiState,
    playbackSpeed: Float,
    // StateFlow (not Long) so the per-row WaveformBarView can
    // collectAsState inside its own composable scope. The 100ms
    // playback tick then recomposes the bar only — section/header/
    // non-playing rows stay stable.
    playbackPositionMillisFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    waveforms: Map<Long, FloatArray>,
    onPlay: (VoiceRecording) -> Unit,
    onPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Float) -> Unit,
    onSaveTranscription: (recordingId: Long, newText: String) -> Unit,
    onRetranscribe: (recordingId: Long) -> Unit,
    onEnsureWaveform: (recordingId: Long, relativePath: String) -> Unit,
    pendingSubstate: PendingTranscriptionSubstate,
    retranscribeEnabled: Boolean,
    // Optimistic post-press feedback (v1.3.0 QA finding): ids the VM
    // recorded at the Transcribe / retranscribe press. Rows in the set
    // whose transcription is still null render "Transcribing…" instead
    // of the pending substate; they drop out when the transcript lands.
    manualTranscribingIds: Set<Long>,
    // U6: whether AutoTranscriptionSkipState.skipReason is currently set
    // — drives the section-level banner, independent of any one row's
    // own [pendingSubstate] (parity spec UI-21: the two are gated by
    // separately-set properties on the same singleton, not mutually
    // exclusive by construction).
    autoTranscriptionSkipped: Boolean,
    // U6: whether a transcription batch is CURRENTLY running for this
    // walk (derived from the shared WorkManager record, not a local
    // flag). Every null-transcription row renders "Transcribing…"
    // instead of its pending substate while this is true — the
    // transcribe-all affordance disappearing, not merely disabling.
    isBatchInFlight: Boolean,
    onManualTranscribe: () -> Unit,
    onOpenModelDownloadSheet: () -> Unit,
    onRetryModelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recordings.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.summary_recordings_header),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = recordings.size.toString(),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        if (autoTranscriptionSkipped) {
            AutoTranscriptionSkippedBanner()
        }
        recordings.forEachIndexed { index, recording ->
            val isActive = playbackUiState.playingRecordingId == recording.id
            val isThisRowPlaying = isActive && playbackUiState.isPlaying
            VoiceRecordingRow(
                indexLabel = index + 1,
                recording = recording,
                walkStartTimestamp = walkStartTimestamp,
                isPlaying = isThisRowPlaying,
                isActive = isActive,
                // Each row passes the flow + an isActive flag down; only
                // the active row's bar actually subscribes (gated below).
                playbackPositionMillisFlow = playbackPositionMillisFlow,
                playbackSpeed = playbackSpeed,
                waveformSamples = waveforms[recording.id],
                onPlay = { onPlay(recording) },
                onPause = onPause,
                onSeek = onSeek,
                onCycleSpeed = onCycleSpeed,
                onSaveTranscription = { newText -> onSaveTranscription(recording.id, newText) },
                onRetranscribe = { onRetranscribe(recording.id) },
                onEnsureWaveform = {
                    onEnsureWaveform(recording.id, recording.fileRelativePath)
                },
                // Only null-transcription rows render the substate;
                // passing null keeps transcribed rows' params stable
                // across download-progress emissions.
                pendingSubstate = if (recording.transcription == null) pendingSubstate else null,
                // U6: a batch in flight overrides the substate the SAME
                // way an optimistic manual-press marker does — both mean
                // "don't show the chip, something is genuinely running."
                isManualTranscribing = recording.transcription == null &&
                    (recording.id in manualTranscribingIds || isBatchInFlight),
                retranscribeEnabled = retranscribeEnabled,
                onManualTranscribe = onManualTranscribe,
                onOpenModelDownloadSheet = onOpenModelDownloadSheet,
                onRetryModelDownload = onRetryModelDownload,
            )
        }
    }
}

@Composable
private fun VoiceRecordingRow(
    indexLabel: Int,
    recording: VoiceRecording,
    walkStartTimestamp: Long,
    isPlaying: Boolean,
    isActive: Boolean,
    playbackPositionMillisFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    playbackSpeed: Float,
    waveformSamples: FloatArray?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onCycleSpeed: () -> Unit,
    onSaveTranscription: (String) -> Unit,
    onRetranscribe: () -> Unit,
    onEnsureWaveform: () -> Unit,
    pendingSubstate: PendingTranscriptionSubstate?,
    isManualTranscribing: Boolean,
    retranscribeEnabled: Boolean,
    onManualTranscribe: () -> Unit,
    onOpenModelDownloadSheet: () -> Unit,
    onRetryModelDownload: () -> Unit,
) {
    LaunchedEffect(recording.id) { onEnsureWaveform() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PilgrimSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.recording_pause else R.string.recording_play,
                ),
                tint = pilgrimColors.stone,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = if (isPlaying) onPause else onPlay)
                    .padding(2.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.recording_index_label, indexLabel),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = WalkFormat.duration(recording.durationMillis),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                    if (recording.isEnhanced) {
                        Text(text = "·", style = pilgrimType.caption, color = pilgrimColors.fog)
                        Text(
                            text = stringResource(R.string.recording_enhanced_label),
                            style = pilgrimType.caption,
                            color = pilgrimColors.stone,
                        )
                    }
                }
            }
            Text(
                text = remember(recording.startTimestamp) {
                    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                        .format(Date(recording.startTimestamp))
                },
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            SpeedPill(playbackSpeed = playbackSpeed, onCycle = onCycleSpeed)
        }
        if (waveformSamples != null && waveformSamples.isNotEmpty()) {
            org.walktalkmeditate.pilgrim.ui.walk.summary.WaveformBarView(
                samples = waveformSamples,
                playbackPositionMillisFlow = if (isActive) playbackPositionMillisFlow else null,
                durationMillis = recording.durationMillis,
                onSeek = { fraction ->
                    if (isActive) {
                        onSeek(fraction)
                    } else {
                        onPlay()
                        onSeek(fraction)
                    }
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(pilgrimColors.fog.copy(alpha = 0.15f)),
            )
        }
        when (val transcription = recording.transcription) {
            null -> if (isManualTranscribing) {
                TranscriptionPlaceholder(
                    text = stringResource(R.string.transcription_in_progress),
                )
            } else {
                pendingSubstate?.let { substate ->
                    PendingTranscriptionRow(
                        substate = substate,
                        onManualTranscribe = onManualTranscribe,
                        onOpenModelDownloadSheet = onOpenModelDownloadSheet,
                        onRetryModelDownload = onRetryModelDownload,
                    )
                }
            }
            TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> TranscriptionPlaceholder(
                text = transcription,
            )
            else -> EditableTranscription(
                recordingId = recording.id,
                text = transcription,
                onSave = onSaveTranscription,
                onRetranscribe = onRetranscribe,
                retranscribeEnabled = retranscribeEnabled,
            )
        }
    }
}

/**
 * Renders the U11 substate for a null-transcription row. Delivery-phase
 * rows tap through to [ModelDownloadSheet]; the pref-OFF states never
 * use waiting/download-promise language (spec section 3) — the
 * no-usable-model cell says "Preparing transcription model…" and taps
 * through to the same sheet for the delivery detail.
 */
@Composable
private fun PendingTranscriptionRow(
    substate: PendingTranscriptionSubstate,
    onManualTranscribe: () -> Unit,
    onOpenModelDownloadSheet: () -> Unit,
    onRetryModelDownload: () -> Unit,
) {
    when (substate) {
        is PendingTranscriptionSubstate.ManualPending -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            TranscriptionPlaceholder(
                text = stringResource(R.string.transcription_not_transcribed),
                modifier = Modifier.weight(1f),
            )
            StoneChip(
                text = stringResource(R.string.transcription_action_transcribe),
                onClick = onManualTranscribe,
                enabled = substate.transcribeEnabled,
            )
        }

        // U6: honest row state while the walk-level banner is showing —
        // never "Queued for transcription…" (nothing is queued), same
        // recovery affordance as ManualPending.
        is PendingTranscriptionSubstate.SkippedForBattery -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            TranscriptionPlaceholder(
                text = stringResource(R.string.transcription_not_transcribed),
                modifier = Modifier.weight(1f),
            )
            StoneChip(
                text = stringResource(R.string.transcription_action_transcribe),
                onClick = onManualTranscribe,
                enabled = substate.transcribeEnabled,
            )
        }

        is PendingTranscriptionSubstate.ManualPreparing -> TranscriptionPlaceholder(
            text = stringResource(R.string.transcription_preparing_model),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onOpenModelDownloadSheet,
                    onClickLabel = stringResource(R.string.model_sheet_open_cd),
                ),
        )

        is PendingTranscriptionSubstate.WaitingOnDownload -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    onClick = onOpenModelDownloadSheet,
                    onClickLabel = stringResource(R.string.model_sheet_open_cd),
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val downloading = substate.modelState as? WhisperModelState.Downloading
            TranscriptionPlaceholder(
                text = when {
                    substate.modelState == WhisperModelState.WaitingUnmetered ->
                        stringResource(R.string.transcription_waiting_model_wifi)
                    downloading != null -> stringResource(
                        R.string.transcription_waiting_model_downloading,
                        modelMegabytes(downloading.bytesDownloaded),
                        modelMegabytes(downloading.totalBytes),
                    )
                    substate.modelState == WhisperModelState.Verifying ->
                        stringResource(R.string.transcription_waiting_model_verifying)
                    else -> stringResource(R.string.transcription_waiting_model)
                },
            )
            if (downloading != null) {
                LinearProgressIndicator(
                    progress = { downloading.fraction },
                    color = pilgrimColors.stone,
                    trackColor = pilgrimColors.fog.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        PendingTranscriptionSubstate.QueuedForProcessing -> TranscriptionPlaceholder(
            text = stringResource(R.string.transcription_queued),
        )

        PendingTranscriptionSubstate.DownloadFailedChecksum -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            TranscriptionPlaceholder(
                text = stringResource(R.string.model_state_failed_checksum),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClick = onOpenModelDownloadSheet,
                        onClickLabel = stringResource(R.string.model_sheet_open_cd),
                    ),
            )
            StoneChip(
                text = stringResource(R.string.model_action_retry),
                onClick = onRetryModelDownload,
            )
        }

        PendingTranscriptionSubstate.DownloadFailedStorage -> TranscriptionPlaceholder(
            text = stringResource(R.string.model_state_failed_storage_hint),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClick = onOpenModelDownloadSheet,
                    onClickLabel = stringResource(R.string.model_sheet_open_cd),
                ),
        )
    }
}

@Composable
private fun SpeedPill(
    playbackSpeed: Float,
    onCycle: () -> Unit,
) {
    val active = playbackSpeed > 1.0f
    val label = if (playbackSpeed % 1.0f == 0.0f) {
        String.format(Locale.US, "%.0fx", playbackSpeed)
    } else {
        String.format(Locale.US, "%gx", playbackSpeed)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (active) pilgrimColors.stone
                else pilgrimColors.stone.copy(alpha = 0.12f),
            )
            .clickable(
                onClick = onCycle,
                onClickLabel = stringResource(R.string.recording_speed_cycle_cd),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = pilgrimType.caption,
            color = if (active) pilgrimColors.parchment else pilgrimColors.stone,
        )
    }
}

/**
 * [EditableTranscription]'s edit/copy/retranscribe icon cluster —
 * straight iOS parity, `VoiceRecordingRow.swift:184-217@2ee1185`:
 * small `.font(.caption)` glyphs floating in generous 44pt tap frames
 * (`.frame(minWidth: 44, minHeight: 44)` + `.contentShape`), stacked
 * with `VStack(spacing: 4)`. (Round-2 QA briefly tried a tighter
 * negative-overlap pitch; on-device comparison against iOS reversed
 * that — the airy look IS the iOS look, its icons are just smaller.)
 *
 * - Glyph: SwiftUI `.caption` is 12pt, and SF Symbols at that font
 *   size render ~12pt of ink. A Material icon's 24dp viewport carries
 *   ~2dp internal padding per side, so a 16dp viewport (~13dp ink) is
 *   the closest visual match.
 * - Touch target: [LocalMinimumInteractiveComponentSize] is scoped
 *   down from Material's 48dp default to iOS's own 44pt floor for
 *   just this Column — `minimumInteractiveComponentSize()` reserves
 *   `max(16dp, 44dp) = 44dp` per icon, the same real target iOS
 *   frames give.
 * - Gap: iOS's literal `spacing: 4` between the 44pt frames → 48pt
 *   pitch, mirrored exactly (44dp + 4dp).
 */
internal val ICON_CLUSTER_TOUCH_TARGET = 44.dp
internal val ICON_CLUSTER_VISUAL_SIZE = 16.dp
internal val ICON_CLUSTER_ARRANGEMENT_GAP = PilgrimSpacing.xs

@Composable
private fun EditableTranscription(
    recordingId: Long,
    text: String,
    onSave: (String) -> Unit,
    onRetranscribe: () -> Unit,
    retranscribeEnabled: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var isEditing by rememberSaveable(recordingId) { mutableStateOf(false) }
    // editText key intentionally drops `text` — re-keying on every
    // external transcription change (e.g., the retranscribe worker
    // committing a new value mid-edit) silently discards the in-progress
    // buffer. We seed the buffer when the user taps Edit and rely on the
    // user to Cancel/Done; rotation persists via rememberSaveable.
    var editText by rememberSaveable(recordingId) { mutableStateOf(text) }
    var expanded by rememberSaveable(recordingId, text) { mutableStateOf(false) }
    val needsExpansion = transcriptionNeedsExpansion(text)
    // iOS v1.6.0 — 7-line clamp matches the expansion threshold.
    val maxLines = if (!needsExpansion || expanded || isEditing) Int.MAX_VALUE else 7

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(pilgrimColors.parchmentTertiary),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isEditing) {
                BasicTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    textStyle = pilgrimType.body.copy(color = pilgrimColors.ink),
                    cursorBrush = SolidColor(pilgrimColors.stone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 200.dp)
                        .padding(8.dp),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 8.dp, bottom = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(pilgrimColors.stone.copy(alpha = 0.12f))
                            .clickable {
                                val trimmed = editText.trim()
                                if (trimmed.isNotEmpty() && trimmed != text) {
                                    onSave(trimmed)
                                }
                                isEditing = false
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.recording_action_edit_done),
                            style = pilgrimType.caption,
                            color = pilgrimColors.stone,
                        )
                    }
                }
            } else {
                Text(
                    text = text,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    maxLines = maxLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                )
                if (needsExpansion) {
                    val toggleLabel = stringResource(
                        if (expanded) R.string.recording_transcription_collapse
                        else R.string.recording_transcription_expand,
                    )
                    Text(
                        text = toggleLabel,
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (!isEditing) {
            // See [ICON_CLUSTER_TOUCH_TARGET]'s doc comment — scopes
            // Material's 48dp `minimumInteractiveComponentSize()` default
            // down to iOS's own 44pt tap-target floor
            // (`VoiceRecordingRow.swift:193@2ee1185`) for just this
            // cluster.
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides ICON_CLUSTER_TOUCH_TARGET,
            ) {
                Column(
                    // `VStack(spacing: 4)` between the 44pt frames —
                    // `VoiceRecordingRow.swift:185@2ee1185`.
                    verticalArrangement = Arrangement.spacedBy(ICON_CLUSTER_ARRANGEMENT_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // iOS v1.6.0 — pencil-icon Edit button replaces the
                    // hidden tap-to-edit gesture (the body-tap conflicted
                    // with the new Show more / Show less toggle).
                    Icon(
                        painter = painterResource(R.drawable.ic_sf_pencil),
                        contentDescription = stringResource(
                            R.string.recording_action_edit_cd,
                        ),
                        tint = pilgrimColors.fog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(ICON_CLUSTER_VISUAL_SIZE)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                editText = text
                                isEditing = true
                            },
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(
                            R.string.recordings_action_copy_transcription,
                        ),
                        tint = pilgrimColors.fog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(ICON_CLUSTER_VISUAL_SIZE)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { clipboard.setText(AnnotatedString(text)) },
                    )
                    // Disabled until the model is Ready (U11 spec section 5):
                    // retranscribe nulls the transcript BEFORE scheduling, so
                    // firing it pre-Ready is silent data loss while the work
                    // spins on a missing model.
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(
                            R.string.recording_action_retranscribe_cd,
                        ),
                        tint = pilgrimColors.fog,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(ICON_CLUSTER_VISUAL_SIZE)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = retranscribeEnabled, onClick = onRetranscribe)
                            .alpha(if (retranscribeEnabled) 1f else 0.38f),
                    )
                }
            }
        }
    }
}
