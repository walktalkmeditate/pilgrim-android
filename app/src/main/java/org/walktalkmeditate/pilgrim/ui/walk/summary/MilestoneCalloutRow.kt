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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestones
import org.walktalkmeditate.pilgrim.ui.goshuin.Season
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Sparkles-tagged dawn-tinted callout above the stats row when the
 * walk earned a milestone. iOS reference: `WalkSummaryView.milestoneCallout`
 * (`WalkSummaryView.swift:332-348`).
 *
 * Caller-side gate: render only when `summary.milestone != null`.
 *
 * iOS divergence: iOS computes "You've now walked N km total" inside
 * `computeMilestone`. Android has no equivalent variant; deferred to a
 * focused follow-up. All other iOS milestone variants are handled here.
 */
@Composable
fun MilestoneCalloutRow(
    milestone: GoshuinMilestone,
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
            text = milestoneSummaryProse(milestone),
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun milestoneSummaryProse(milestone: GoshuinMilestone): String = when (milestone) {
    GoshuinMilestone.FirstWalk ->
        stringResource(R.string.summary_milestone_first_walk)
    GoshuinMilestone.LongestWalk ->
        stringResource(R.string.summary_milestone_longest_walk)
    GoshuinMilestone.LongestMeditation ->
        stringResource(R.string.summary_milestone_longest_meditation)
    is GoshuinMilestone.NthWalk ->
        stringResource(R.string.summary_milestone_nth_walk, GoshuinMilestones.ordinal(milestone.n))
    is GoshuinMilestone.FirstOfSeason -> stringResource(
        when (milestone.season) {
            Season.Spring -> R.string.summary_milestone_first_of_spring
            Season.Summer -> R.string.summary_milestone_first_of_summer
            Season.Autumn -> R.string.summary_milestone_first_of_autumn
            Season.Winter -> R.string.summary_milestone_first_of_winter
        },
    )
}
