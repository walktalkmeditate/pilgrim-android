// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

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
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Section-17 row on Walk Summary that opens the AI Prompts sheet.
 * Mirrors iOS `WalkSummaryView.swift` section 17. The whole row is
 * clickable; subtitle reflects how many transcribed recordings the
 * walk has (or "Reflect on your walk" when none).
 */
@Composable
fun AIPromptsRow(
    transcribedRecordingsCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = if (transcribedRecordingsCount > 0) {
        pluralStringResource(
            id = R.plurals.prompts_button_subtitle_with_speech,
            count = transcribedRecordingsCount,
            transcribedRecordingsCount,
        )
    } else {
        stringResource(R.string.prompts_button_subtitle_no_speech)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Icon(
            imageVector = Icons.Outlined.FormatQuote,
            contentDescription = null,
            tint = pilgrimColors.ink,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.prompts_button_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = subtitle,
                style = pilgrimType.caption,
                color = pilgrimColors.ink.copy(alpha = 0.7f),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.NavigateNext,
            contentDescription = null,
            tint = pilgrimColors.ink.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
