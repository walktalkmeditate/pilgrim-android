// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * 3-card breakdown of how time was spent during the walk: walking,
 * talking (voice recordings), meditating. Always renders all three
 * cards even at zero duration. iOS reference:
 * `WalkSummaryView.timeBreakdown` (`WalkSummaryView.swift:535-548`).
 */
@Composable
fun WalkTimeBreakdownGrid(
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        breakdownCard(
            icon = Icons.Rounded.DirectionsWalk,
            label = stringResource(R.string.summary_breakdown_walk),
            millis = walkMillis,
        )
        breakdownCard(
            icon = Icons.Rounded.GraphicEq,
            label = stringResource(R.string.summary_breakdown_talk),
            millis = talkMillis,
        )
        breakdownCard(
            icon = Icons.Rounded.SelfImprovement,
            label = stringResource(R.string.summary_breakdown_meditate),
            millis = meditateMillis,
        )
    }
}

@Composable
private fun RowScope.breakdownCard(
    icon: ImageVector,
    label: String,
    millis: Long,
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = WalkFormat.duration(millis),
                style = pilgrimType.statValue,
                color = pilgrimColors.ink,
                maxLines = 1,
            )
            Text(
                text = label,
                style = pilgrimType.statLabel,
                color = pilgrimColors.fog,
                maxLines = 1,
            )
        }
    }
}
