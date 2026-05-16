// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Intention-setting surface. iOS parity: a bottom **sheet**
 * (`IntentionSettingView` presented via `.sheet` + medium/large
 * detents) — not a centered dialog. Shows the text field + char
 * counter, plus a "Suggested" (celestial) chip row and a "Recent"
 * chip row, each hidden once the user starts typing. Voice dictation
 * is intentionally deferred on Android (iOS-only `IntentionVoiceRecorder`;
 * dated re-justify in the parity ledger).
 *
 * The ModalBottomSheet shell is split from [IntentionSheetContent] so
 * the content (the load-bearing logic: char clamp, resetKey draft
 * discard, chip taps) is unit-testable without the sheet window layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentionSettingSheet(
    initial: String?,
    recents: List<String>,
    suggestions: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    resetKey: Int = 0,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        IntentionSheetContent(
            initial = initial,
            recents = recents,
            suggestions = suggestions,
            onSave = onSave,
            onDismiss = onDismiss,
            resetKey = resetKey,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntentionSheetContent(
    initial: String?,
    recents: List<String>,
    suggestions: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    resetKey: Int = 0,
) {
    // Key on (initial, resetKey): (a) external `initial` change on
    // reopen overrides a stale Saver; (b) parent-bumped resetKey
    // discards a typed-but-cancelled draft (the screen-wide
    // SaveableStateRegistry outlives the conditional render).
    // Rotation within one open session still round-trips via Bundle.
    var text by rememberSaveable(initial, resetKey) { mutableStateOf(initial.orEmpty()) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Text(
            text = stringResource(R.string.walk_options_intention_dialog_title),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(WalkController.MAX_INTENTION_CHARS) },
            placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // Locale.US digits so non-ASCII-numeral locales still render
            // ASCII (Stage 5-A regression pattern).
            text = stringResource(
                R.string.walk_waypoint_count_chars,
                String.format(Locale.US, "%d", text.length),
                String.format(Locale.US, "%d", WalkController.MAX_INTENTION_CHARS),
            ),
            style = pilgrimType.caption,
            // iOS parity `IntentionSettingView.swift:113` — static fog 0.5.
            color = pilgrimColors.fog.copy(alpha = 0.5f),
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )

        // iOS shows Suggested/Recent only while the field is empty.
        if (text.isEmpty()) {
            if (suggestions.isNotEmpty()) {
                ChipSection(
                    header = stringResource(R.string.walk_options_intention_suggested),
                    items = suggestions,
                    chipColor = pilgrimColors.dawn.copy(alpha = 0.15f),
                    onPick = { text = it },
                )
            }
            if (recents.isNotEmpty()) {
                ChipSection(
                    header = stringResource(R.string.walk_options_intention_recent),
                    items = recents,
                    chipColor = pilgrimColors.parchmentSecondary.copy(alpha = 0.4f),
                    onPick = { text = it },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.walk_options_intention_cancel))
            }
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.walk_options_intention_save))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(
    header: String,
    items: List<String>,
    chipColor: Color,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        Text(
            text = header,
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.5f),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            items.forEach { item ->
                Text(
                    text = item,
                    style = pilgrimType.caption,
                    color = pilgrimColors.ink,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .clip(RoundedCornerShape(50))
                        .background(chipColor)
                        .clickable { onPick(item) }
                        .padding(
                            horizontal = PilgrimSpacing.normal,
                            vertical = PilgrimSpacing.small,
                        ),
                )
            }
        }
    }
}
