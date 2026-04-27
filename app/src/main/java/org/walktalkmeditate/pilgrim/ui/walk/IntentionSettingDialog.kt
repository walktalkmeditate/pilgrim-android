// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val MAX_INTENTION_CHARS = 140

@Composable
fun IntentionSettingDialog(
    initial: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Key on `initial` so a Cancel + external-mutation + reopen sequence
    // doesn't restore a stale draft over the freshly-set value. Today no
    // surface mutates intention while ActiveWalkScreen is foregrounded,
    // but this keeps the dialog defensible if a notification action or
    // automation surface is added later.
    var text by rememberSaveable(initial) { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.walk_options_intention_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { incoming ->
                        text = incoming.take(MAX_INTENTION_CHARS)
                    },
                    placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
                    singleLine = false,
                    maxLines = 3,
                )
                val countColor by animateColorAsState(
                    targetValue = lerp(
                        pilgrimColors.fog,
                        pilgrimColors.moss,
                        fraction = (text.length.toFloat() / MAX_INTENTION_CHARS).coerceIn(0f, 1f),
                    ),
                    label = "intention-count-color",
                )
                Text(
                    text = stringResource(
                        R.string.walk_waypoint_count_chars,
                        text.length,
                        MAX_INTENTION_CHARS,
                    ),
                    style = pilgrimType.caption,
                    color = countColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.walk_options_intention_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.walk_options_intention_cancel))
            }
        },
        containerColor = pilgrimColors.parchment,
    )
}
