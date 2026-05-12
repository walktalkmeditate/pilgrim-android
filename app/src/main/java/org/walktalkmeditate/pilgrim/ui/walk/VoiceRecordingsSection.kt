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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.TranscriptionRunner
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionDisplay
import org.walktalkmeditate.pilgrim.ui.recordings.TranscriptionPlaceholder
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
 * transcription text in a `parchmentTertiary` container with a copy
 * affordance on the side (see [TranscriptionDisplay]).
 */
@Composable
fun VoiceRecordingsSection(
    walkStartTimestamp: Long,
    recordings: List<VoiceRecording>,
    playbackUiState: PlaybackUiState,
    playbackSpeed: Float,
    onPlay: (VoiceRecording) -> Unit,
    onPause: () -> Unit,
    onCycleSpeed: () -> Unit,
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
            val isThisRowPlaying = playbackUiState.playingRecordingId == recording.id &&
                playbackUiState.isPlaying
            VoiceRecordingRow(
                indexLabel = index + 1,
                recording = recording,
                walkStartTimestamp = walkStartTimestamp,
                isPlaying = isThisRowPlaying,
                playbackSpeed = playbackSpeed,
                onPlay = { onPlay(recording) },
                onPause = onPause,
                onCycleSpeed = onCycleSpeed,
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
    playbackSpeed: Float,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onCycleSpeed: () -> Unit,
) {
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(pilgrimColors.fog.copy(alpha = 0.15f)),
        )
        when (val transcription = recording.transcription) {
            null -> TranscriptionPlaceholder(
                text = stringResource(R.string.transcription_pending),
            )
            TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> TranscriptionPlaceholder(
                text = transcription,
            )
            else -> TranscriptionDisplay(
                text = transcription,
                onTap = null,
                showCopyAffordance = true,
            )
        }
        recording.wordsPerMinute?.let { wpm ->
            Text(
                text = stringResource(R.string.recording_wpm_caption, wpm.toInt()),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
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
