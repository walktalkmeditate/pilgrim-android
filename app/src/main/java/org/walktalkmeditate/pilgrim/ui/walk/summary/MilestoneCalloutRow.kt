// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Sparkles-tagged dawn-tinted callout above the stats row when the
 * walk earned a milestone. iOS reference: `WalkSummaryView.milestoneCallout`
 * (`WalkSummaryView.swift:332-348`).
 *
 * Caller-side gate: render only when `walkSummaryCalloutProseDisplay`
 * resolves to non-null. Stage 13-Cel: prose comes from the
 * [WalkSummaryCalloutProse] helper which mirrors iOS's
 * `computeMilestone()` priority chain (SeasonalMarker →
 * LongestMeditation → LongestWalk → TotalDistance) — NO fallthrough
 * to FirstWalk / FirstOfSeason / NthWalk on the Walk Summary callout
 * (those only appear on the Goshuin grid).
 */
@Composable
fun MilestoneCalloutRow(
    prose: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.dawn.copy(alpha = 0.1f))
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = pilgrimColors.dawn,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = prose,
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
