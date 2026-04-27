// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.walk.WalkController

@Composable
fun IntentionSettingDialog(
    initial: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    resetKey: Int = 0,
) {
    // Key on (initial, resetKey) so:
    //  (a) external mutation of `initial` between Cancel + reopen brings
    //      in the fresh value (overrides stale Saver),
    //  (b) parent-bumped `resetKey` discards the typed-but-cancelled
    //      draft on reopen (Cancel semantics — typing "abc" then Cancel
    //      then reopen must NOT restore "abc"). rememberSaveable saves
    //      to the screen-wide SaveableStateRegistry, which outlives the
    //      conditional `if (showPreWalkIntention) IntentionSettingDialog(…)`
    //      removal — so without this key bump, the Saver would resurrect
    //      the cancelled draft.
    // Rotation still preserves typed text within a single open session
    // because the (initial, resetKey) tuple round-trips through Bundle.
    var text by rememberSaveable(initial, resetKey) { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.walk_options_intention_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { incoming ->
                        text = incoming.take(WalkController.MAX_INTENTION_CHARS)
                    },
                    placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
                    singleLine = false,
                    maxLines = 3,
                )
                Text(
                    text = stringResource(
                        R.string.walk_waypoint_count_chars,
                        text.length,
                        WalkController.MAX_INTENTION_CHARS,
                    ),
                    style = pilgrimType.caption,
                    // iOS parity: `IntentionSettingView.swift:113` uses
                    // `.fog.opacity(0.5)` — a static fog tint, no gradient.
                    color = pilgrimColors.fog.copy(alpha = 0.5f),
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
