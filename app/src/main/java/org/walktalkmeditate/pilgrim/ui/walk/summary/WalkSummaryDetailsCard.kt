// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * "Paused — H:MM:SS" details row when the walk had any paused time.
 * iOS reference: `WalkSummaryView.detailsSection` (`WalkSummaryView.swift:795-810`).
 *
 * Caller-side gate: render only when `pausedMillis > 0L`.
 */
@Composable
fun WalkSummaryDetailsCard(
    pausedMillis: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.summary_details_paused),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
            Text(
                text = WalkFormat.duration(pausedMillis),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
        }
    }
}
