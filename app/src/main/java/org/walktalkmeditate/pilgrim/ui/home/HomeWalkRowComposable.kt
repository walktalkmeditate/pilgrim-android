// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Single row in the Home walk list. All text fields are already
 * formatted by [HomeViewModel]; this composable is a pure pass-through.
 */
@Composable
fun HomeWalkRowCard(
    row: HomeWalkRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        Column(
            modifier = Modifier.padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.relativeDate,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            if (row.intention != null) {
                Text(
                    text = row.intention,
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    fontStyle = FontStyle.Italic,
                )
            }
            Text(
                text = "${row.durationText} · ${row.distanceText}",
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            if (row.recordingCountText != null) {
                Text(
                    text = row.recordingCountText,
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
        }
    }
}
