// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * 1- to 3-column mini-stats below the duration hero. iOS reference:
 * `WalkSummaryView.statsRow` (`WalkSummaryView.swift:463-488@db4196e`).
 *
 * Renders Distance always; Steps when `steps != null && steps > 0`
 * (iOS gate `if let steps = walk.steps, steps > 0`); Elevation when
 * `ascendMeters > 1.0` (iOS gate `walk.ascend > 1`).
 */
@Composable
fun WalkStatsRow(
    distanceMeters: Double,
    ascendMeters: Double,
    steps: Int?,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
    ) {
        MiniStat(
            label = stringResource(R.string.summary_stat_distance),
            value = WalkFormat.distance(distanceMeters, units),
        )
        if (steps != null && steps > 0) {
            MiniStat(
                label = stringResource(R.string.summary_stat_steps),
                value = String.format(Locale.US, "%d", steps),
            )
        }
        if (ascendMeters > 1.0) {
            MiniStat(
                label = stringResource(R.string.summary_stat_elevation),
                value = WalkFormat.altitude(ascendMeters, units),
            )
        }
    }
}

@Composable
private fun RowScope.MiniStat(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}
