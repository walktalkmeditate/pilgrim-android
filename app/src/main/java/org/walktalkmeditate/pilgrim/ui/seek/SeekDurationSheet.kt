// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** iOS `SeekDurationView.presetMinutes` (`SeekDurationView.swift:13@c1745e8`). */
internal val SEEK_DURATION_PRESETS = listOf(30, 60, 120, 180)

/**
 * Snaps a stored value that no longer matches a preset to the closest
 * one instead of leaving nothing selected. Deterministic on ties:
 * `minByOrNull` returns the FIRST minimum (same as Swift `min(by:)`
 * with strict `<`), so a legacy 45 snaps to 30
 * (`SeekDurationView.swift:31-33@c1745e8`).
 */
internal fun preselectedSeekMinutes(lastUsed: Int): Int =
    SEEK_DURATION_PRESETS.minByOrNull { abs(it - lastUsed) } ?: 60

@StringRes
internal fun seekDurationLabelRes(minutes: Int): Int = when (minutes) {
    30 -> R.string.seek_duration_30min
    60 -> R.string.seek_duration_1hour
    120 -> R.string.seek_duration_2hours
    else -> R.string.seek_duration_3hours
}

/**
 * The one seek setup question: "How long do you have?" Four presets,
 * last choice preselected, first-seek-only safety caption. iOS
 * `SeekDurationView` (`SeekDurationView.swift:5-115@c1745e8`).
 *
 * A swipe-down while still on the question is a cancel; the
 * Begin-driven dismissal has already advanced the stage by the time
 * the host stops rendering the sheet (mirrors
 * `SeekSetupFlowModifier.swift:17-29@c1745e8`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekDurationSheet(
    lastUsedMinutes: Int,
    showsSafetyCaption: Boolean,
    onSelect: (Int) -> Unit,
    onBegin: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    // iOS presents at `.medium` first (`SeekSetupFlowModifier.swift:41`),
    // where the content fits. M3's PartiallyExpanded detent clips
    // Cancel/Begin below the fold on phones, so skip straight to the
    // fully-expanded (content-height) state — device QA 2026-07-15.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedMinutes by rememberSaveable {
        mutableStateOf(preselectedSeekMinutes(lastUsedMinutes))
    }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment.copy(alpha = 0.95f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.big)
                .padding(bottom = PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.seek_duration_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PilgrimSpacing.small),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
                modifier = Modifier.padding(top = PilgrimSpacing.big),
            ) {
                SEEK_DURATION_PRESETS.forEach { minutes ->
                    PresetRow(
                        minutes = minutes,
                        isSelected = minutes == selectedMinutes,
                        onClick = {
                            selectedMinutes = minutes
                            // iOS persists the selection on tap, before
                            // Begin (`SeekDurationView.swift:76-79`).
                            onSelect(minutes)
                        },
                    )
                }
            }

            if (showsSafetyCaption) {
                Text(
                    text = stringResource(R.string.seek_safety_caption),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = PilgrimSpacing.normal),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PilgrimSpacing.big),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.seek_setup_cancel),
                        style = pilgrimType.button,
                        color = pilgrimColors.fog,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onBegin(selectedMinutes) }) {
                    Text(
                        text = stringResource(R.string.seek_begin),
                        style = pilgrimType.button,
                        color = pilgrimColors.stone,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    minutes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                pilgrimColors.parchmentSecondary.copy(alpha = if (isSelected) 0.7f else 0.4f),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(seekDurationLabelRes(minutes)),
            style = pilgrimType.body,
            color = pilgrimColors.ink.copy(alpha = if (isSelected) 1f else 0.7f),
        )
        Spacer(Modifier.weight(1f))
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
