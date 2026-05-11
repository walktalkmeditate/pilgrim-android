// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Threshold from iOS `VoiceRecordingRow.swift:27-36@db4196e`:
 * `text.count > 280 || text.split(separator: "\n").count > 7`.
 * Kotlin uses `String.length` (UTF-16 code units) — minor grapheme
 * divergence on multi-codepoint emoji is acceptable for transcribed
 * speech content. The newline split-count is 1-based on iOS (`8 lines = 8`),
 * so the boundary `> 7` becomes ≥ 8 newlines.
 */
internal const val TRANSCRIPTION_CHAR_LIMIT = 280
internal const val TRANSCRIPTION_NEWLINE_LIMIT = 7

internal fun transcriptionNeedsExpansion(text: String): Boolean =
    text.length > TRANSCRIPTION_CHAR_LIMIT ||
        text.count { it == '\n' } > TRANSCRIPTION_NEWLINE_LIMIT

/**
 * Shared transcription presenter used by both the standalone Recordings
 * List ([RecordingRow]) and the Walk Summary surface
 * ([VoiceRecordingsSection]). Expansion toggle appears only when the
 * threshold from iOS `VoiceRecordingRow.swift:27-36@db4196e` is hit.
 *
 * @param text the transcription text to display (already non-null and
 *   non-blank; callers gate empty/NO_SPEECH).
 * @param onTap optional callback when the body text is tapped — used by
 *   the standalone Recordings List to enter edit mode; pass null on the
 *   Walk Summary surface (read-only).
 * @param showCopyAffordance true on Recordings List, false on Walk Summary.
 */
@Composable
internal fun TranscriptionDisplay(
    text: String,
    onTap: (() -> Unit)?,
    showCopyAffordance: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    val clipboard = LocalClipboardManager.current
    val copyDescription = stringResource(R.string.recordings_action_copy_transcription)

    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val needsExpansion = transcriptionNeedsExpansion(text)
    val maxLines = if (!needsExpansion || expanded) Int.MAX_VALUE else 4

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.parchmentTertiary),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = text,
                style = type.body,
                color = colors.ink,
                maxLines = maxLines,
                modifier = Modifier
                    .weight(1f)
                    .let { if (onTap != null) it.clickable { onTap() } else it }
                    .padding(8.dp),
            )
            if (showCopyAffordance) {
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
        if (needsExpansion) {
            val toggleLabel = stringResource(
                if (expanded) R.string.recording_transcription_collapse
                else R.string.recording_transcription_expand,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = colors.fog,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = toggleLabel,
                    style = type.caption,
                    color = colors.fog,
                )
            }
        }
    }
}

/**
 * Read-only italic-muted variant for pending / no-speech states on
 * the Walk Summary surface. Does NOT show the expand toggle (text is
 * always short).
 */
@Composable
internal fun TranscriptionPlaceholder(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = pilgrimType.body,
        color = pilgrimColors.fog,
        fontStyle = FontStyle.Italic,
        modifier = modifier,
    )
}
