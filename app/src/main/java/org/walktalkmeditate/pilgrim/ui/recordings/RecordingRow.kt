// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * One [VoiceRecording] row inside the Recordings list.
 *
 * iOS parity: `pilgrim-ios/.../RecordingsListView.swift` lines 200-330.
 *
 * Three visual states:
 *  1. file-available — play/pause button, "Recording N", duration·size meta,
 *     speed pill, waveform; transcription block below.
 *  2. file-unavailable — replaces the player row with `[GraphicEqOff icon]
 *     "File unavailable"`. Transcription block still rendered if present.
 *  3. edit-mode — replaces the transcription view-block with an
 *     `OutlinedTextField` plus a `Done` button.
 *
 * The [fileSystem] dependency is plumbed in for the file-size lookup
 * (which we still resolve here so the meta-string doesn't need a parallel
 * VM-side map). File EXISTENCE is now resolved in the VM's combine block
 * and threaded down as [fileAvailable] — see Stage 10-D final-review
 * Fix 1: the previous per-row `remember(recording.fileRelativePath)` key
 * never changed when `onDeleteFile` succeeded (we keep the Room row), so
 * the player UI stayed visible for a deleted file.
 *
 * iOS unavailable-state icon is `waveform.slash`. Material's extended
 * icon set has no `GraphicEqOff` — `Icons.Filled.MusicOff` is the
 * closest single-glyph match (a music note with a slash, semantically
 * "audio is gone"). No need for a dual-overlay composition.
 */
@Composable
fun RecordingRow(
    recording: VoiceRecording,
    indexInSection: Int,
    fileSystem: VoiceRecordingFileSystem,
    waveformCache: WaveformCache,
    fileAvailable: Boolean,
    isPlayingThisRow: Boolean,
    playbackPositionFraction: Float,
    playbackSpeed: Float,
    isEditing: Boolean,
    onPlay: (Long) -> Unit,
    onPause: () -> Unit,
    onSeek: (Long, Float) -> Unit,
    onSpeedCycle: () -> Unit,
    onStartEditing: (Long) -> Unit,
    onStopEditing: () -> Unit,
    onTranscriptionEdit: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = pilgrimColors
    val sizeBytes = remember(recording.fileRelativePath, fileAvailable) {
        if (fileAvailable) fileSystem.fileSizeBytes(recording.fileRelativePath) else 0L
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (fileAvailable) {
            PlayerHeader(
                recording = recording,
                indexInSection = indexInSection,
                sizeBytes = sizeBytes,
                isPlayingThisRow = isPlayingThisRow,
                playbackSpeed = playbackSpeed,
                onPlay = onPlay,
                onPause = onPause,
                onSpeedCycle = onSpeedCycle,
            )
            WaveformLine(
                file = fileSystem.absolutePath(recording.fileRelativePath),
                recordingId = recording.id,
                cache = waveformCache,
                progress = if (isPlayingThisRow) playbackPositionFraction else 0f,
                onSeek = { fraction -> onSeek(recording.id, fraction) },
            )
            if (isPlayingThisRow) {
                PlaybackTimeLabels(
                    currentMs = (playbackPositionFraction * recording.durationMillis).toLong(),
                    totalMs = recording.durationMillis,
                    fogColor = colors.fog,
                )
            }
        } else {
            UnavailableRow(
                recording = recording,
                indexInSection = indexInSection,
            )
        }

        val transcription = recording.transcription
        if (!transcription.isNullOrBlank()) {
            if (isEditing) {
                TranscriptionEditor(
                    initial = transcription,
                    onCommit = { newText -> onTranscriptionEdit(recording.id, newText) },
                    onStop = onStopEditing,
                )
            } else {
                TranscriptionView(
                    text = transcription,
                    onTap = { onStartEditing(recording.id) },
                )
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    recording: VoiceRecording,
    indexInSection: Int,
    sizeBytes: Long,
    isPlayingThisRow: Boolean,
    playbackSpeed: Float,
    onPlay: (Long) -> Unit,
    onPause: () -> Unit,
    onSpeedCycle: () -> Unit,
) {
    val colors = pilgrimColors
    val type = pilgrimType

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Play/Pause button. iOS uses `.symbolEffect(.replace)`; Compose has
        // no native symbol crossfade, so AnimatedContent with quick fade is
        // the closest match. The contentDescription differentiates the two
        // states for screen readers.
        AnimatedContent(
            targetState = isPlayingThisRow,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "play-pause-icon",
        ) { playing ->
            val description = stringResource(
                if (playing) R.string.recording_pause else R.string.recording_play,
            )
            Icon(
                imageVector = if (playing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = description,
                tint = colors.stone,
                modifier = Modifier
                    .size(32.dp)
                    .clickable {
                        if (playing) onPause() else onPlay(recording.id)
                    },
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.recordings_row_index, indexInSection),
                style = type.body,
                color = colors.ink,
            )
            val durationStr = WalkFormat.duration(recording.durationMillis)
            val sizeStr = String.format(Locale.US, "%.1f", sizeBytes / 1_000_000.0)
            val metaText = if (recording.isEnhanced) {
                stringResource(R.string.recordings_row_meta_enhanced, durationStr, sizeStr)
            } else {
                stringResource(R.string.recordings_row_meta, durationStr, sizeStr)
            }
            Text(
                text = metaText,
                style = type.caption,
                color = colors.fog,
            )
        }

        SpeedPill(
            speed = playbackSpeed,
            onClick = onSpeedCycle,
        )
    }
}

@Composable
private fun SpeedPill(
    speed: Float,
    onClick: () -> Unit,
) {
    val colors = pilgrimColors
    val isElevated = speed > 1.0f
    val background = if (isElevated) colors.stone else colors.stone.copy(alpha = 0.12f)
    val foreground = if (isElevated) colors.parchment else colors.stone

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = formatSpeed(speed),
            style = pilgrimType.caption,
            color = foreground,
        )
    }
}

/** iOS truncation — `1.0 → "1x"`, `1.5 → "1.5x"`, `2.0 → "2x"`. */
private fun formatSpeed(speed: Float): String =
    if (speed % 1.0f == 0f) String.format(Locale.US, "%.0fx", speed)
    else String.format(Locale.US, "%.1fx", speed)

@Composable
private fun WaveformLine(
    file: File,
    recordingId: Long,
    cache: WaveformCache,
    progress: Float,
    onSeek: (Float) -> Unit,
) {
    val colors = pilgrimColors
    // Seed from cache (synchronous) so a re-scrolled row paints
    // immediately. Cache miss falls through to a one-shot IO load
    // followed by `cache.put`, mirroring iOS's WaveformCache.shared.
    var samples by remember(recordingId) {
        mutableStateOf<FloatArray?>(cache.get(recordingId))
    }
    LaunchedEffect(recordingId) {
        if (samples != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            WaveformLoader.load(file, barCount = 64)
        }
        cache.put(recordingId, loaded)
        samples = loaded
    }
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp, max = 32.dp)) {
        val current = samples
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp, max = 32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.fog.copy(alpha = 0.15f)),
            )
        } else {
            WaveformBar(
                samples = current,
                progress = progress,
                inactiveColor = colors.fog.copy(alpha = 0.4f),
                activeColor = colors.stone,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp, max = 32.dp),
            )
        }
    }
}

@Composable
private fun PlaybackTimeLabels(
    currentMs: Long,
    totalMs: Long,
    fogColor: Color,
) {
    val type = pilgrimType
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = WalkFormat.duration(currentMs),
            style = type.caption,
            color = fogColor,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = WalkFormat.duration(totalMs),
            style = type.caption,
            color = fogColor,
        )
    }
}

@Composable
private fun UnavailableRow(
    recording: VoiceRecording,
    indexInSection: Int,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.recordings_row_index, indexInSection),
            style = type.body,
            color = colors.ink,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MusicOff,
                contentDescription = null,
                tint = colors.fog,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.recordings_row_unavailable),
                style = type.caption,
                color = colors.fog,
            )
        }
    }
}

@Composable
private fun TranscriptionView(
    text: String,
    onTap: () -> Unit,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    val clipboard = LocalClipboardManager.current
    val copyDescription = stringResource(R.string.recordings_action_copy_transcription)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.parchmentTertiary),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = text,
            style = type.body,
            color = colors.ink,
            modifier = Modifier
                .weight(1f)
                .clickable { onTap() }
                .padding(8.dp),
        )
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = copyDescription,
            tint = colors.fog,
            modifier = Modifier
                .size(36.dp)
                .clickable { clipboard.setText(AnnotatedString(text)) }
                .padding(8.dp),
        )
    }
}

@Composable
private fun TranscriptionEditor(
    initial: String,
    onCommit: (String) -> Unit,
    onStop: () -> Unit,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    // `rememberSaveable` so typed-but-unsaved edits survive configuration
    // change (rotation, dark-mode toggle, system font scale change). The
    // [initial] key still resets the saveable when the user enters edit
    // mode for a different recording.
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    val latestText by rememberUpdatedState(text)
    val editorDescription = stringResource(R.string.recordings_transcription_editor_description)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 200.dp)
                .semantics { contentDescription = editorDescription },
            textStyle = type.body.copy(color = colors.ink),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.parchmentTertiary,
                unfocusedContainerColor = colors.parchmentTertiary,
                disabledContainerColor = colors.parchmentTertiary,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = colors.stone,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onCommit(latestText.trim())
            }),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(colors.stone.copy(alpha = 0.12f))
                .clickable { onCommit(latestText.trim()) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.recordings_action_done),
                style = type.caption,
                color = colors.stone,
                textAlign = TextAlign.Center,
            )
        }
    }
}
