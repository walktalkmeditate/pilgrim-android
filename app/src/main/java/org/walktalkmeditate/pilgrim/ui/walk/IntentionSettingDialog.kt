// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

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
            OutlinedTextField(
                value = text,
                onValueChange = { incoming ->
                    text = incoming.take(MAX_INTENTION_CHARS)
                },
                placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
                singleLine = false,
                maxLines = 3,
            )
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
