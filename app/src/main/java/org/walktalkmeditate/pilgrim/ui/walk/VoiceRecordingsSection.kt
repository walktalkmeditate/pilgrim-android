// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.TranscriptionRunner
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Read-only voice-recording list shown under the Walk Summary stats.
 * Each row displays the recording's offset into the walk + duration,
 * the on-device transcription (or a "Transcribing…" placeholder while
 * Stage 2-D's worker hasn't finished), an italic-muted treatment for
 * NO_SPEECH_PLACEHOLDER, an optional words-per-minute caption, and a
 * play/pause button delegating to the host VM.
 */
@Composable
fun VoiceRecordingsSection(
    walkStartTimestamp: Long,
    recordings: List<VoiceRecording>,
    playbackUiState: PlaybackUiState,
    onPlay: (VoiceRecording) -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recordings.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Text(
            text = stringResource(R.string.summary_recordings_header),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        recordings.forEach { recording ->
            val isThisRowPlaying = playbackUiState.playingRecordingId == recording.id &&
                playbackUiState.isPlaying
            VoiceRecordingRow(
                recording = recording,
                walkStartTimestamp = walkStartTimestamp,
                isPlaying = isThisRowPlaying,
                onPlay = { onPlay(recording) },
                onPause = onPause,
            )
        }
    }
}

@Composable
private fun VoiceRecordingRow(
    recording: VoiceRecording,
    walkStartTimestamp: Long,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val offsetMs = (recording.startTimestamp - walkStartTimestamp).coerceAtLeast(0L)
                Text(
                    text = "+${WalkFormat.duration(offsetMs)} · ${WalkFormat.duration(recording.durationMillis)}",
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
                TranscriptionDisplay(recording = recording)
                recording.wordsPerMinute?.let { wpm ->
                    Text(
                        text = stringResource(R.string.recording_wpm_caption, wpm.toInt()),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
            }
            IconButton(onClick = if (isPlaying) onPause else onPlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.recording_pause else R.string.recording_play,
                    ),
                    tint = pilgrimColors.ink,
                )
            }
        }
    }
}

@Composable
private fun TranscriptionDisplay(recording: VoiceRecording) {
    val text = recording.transcription
    when {
        text == null -> Text(
            text = stringResource(R.string.transcription_pending),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
            fontStyle = FontStyle.Italic,
        )
        text == TranscriptionRunner.NO_SPEECH_PLACEHOLDER -> Text(
            text = text,
            style = pilgrimType.body,
            color = pilgrimColors.fog,
            fontStyle = FontStyle.Italic,
        )
        else -> Text(
            text = text,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
    }
}
