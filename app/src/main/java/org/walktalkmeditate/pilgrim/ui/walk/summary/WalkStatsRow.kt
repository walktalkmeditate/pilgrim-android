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
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * 1- to 2-column mini-stats below the duration hero.
 *
 * Steps deferred to a future stage (Walk entity does not yet carry a
 * step counter column). Until then, the row shows Distance always +
 * Elevation when ascend > 1m. iOS reference:
 * `WalkSummaryView.statsRow` (`WalkSummaryView.swift:463-488`).
 */
@Composable
fun WalkStatsRow(
    distanceMeters: Double,
    ascendMeters: Double,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
    ) {
        miniStat(
            label = stringResource(R.string.summary_stat_distance),
            value = WalkFormat.distance(distanceMeters, units),
        )
        if (ascendMeters > 1.0) {
            miniStat(
                label = stringResource(R.string.summary_stat_elevation),
                value = WalkFormat.altitude(ascendMeters, units),
            )
        }
    }
}

@Composable
private fun RowScope.miniStat(label: String, value: String) {
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
