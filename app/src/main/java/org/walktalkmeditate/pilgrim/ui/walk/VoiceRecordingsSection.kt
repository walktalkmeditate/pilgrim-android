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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.TranscriptionRunner
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionPlaceholder
import org.walktalkmeditate.pilgrim.ui.recordings.transcriptionNeedsExpansion
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

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
            null -> TranscriptionPlaceholder(
                text = stringResource(R.string.transcription_pending),
            )
            TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> TranscriptionPlaceholder(
                text = transcription,
            )
            else -> EditableTranscription(
                recordingId = recording.id,
                text = transcription,
                onSave = onSaveTranscription,
                onRetranscribe = onRetranscribe,
            )
        }
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

@Composable
private fun EditableTranscription(
    recordingId: Long,
    text: String,
    onSave: (String) -> Unit,
    onRetranscribe: () -> Unit,
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
            Column(
                // iOS v1.6.0 ups the icon-cluster spacing from
                // 8 to 12 dp so the three buttons don't visually
                // collide on small screens.
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // iOS v1.6.0 — pencil-icon Edit button replaces the
                // hidden tap-to-edit gesture (the body-tap conflicted
                // with the new Show more / Show less toggle). 32x32
                // tap targets matching iOS sizing.
                Icon(
                    painter = painterResource(R.drawable.ic_sf_pencil),
                    contentDescription = stringResource(
                        R.string.recording_action_edit_cd,
                    ),
                    tint = pilgrimColors.fog,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            editText = text
                            isEditing = true
                        }
                        .padding(8.dp),
                )
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(
                        R.string.recordings_action_copy_transcription,
                    ),
                    tint = pilgrimColors.fog,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { clipboard.setText(AnnotatedString(text)) }
                        .padding(8.dp),
                )
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(
                        R.string.recording_action_retranscribe_cd,
                    ),
                    tint = pilgrimColors.fog,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onRetranscribe)
                        .padding(8.dp),
                )
            }
        }
    }
}
