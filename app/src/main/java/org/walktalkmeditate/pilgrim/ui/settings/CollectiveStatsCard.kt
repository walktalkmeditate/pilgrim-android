// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 8-B: collective-counter aggregate card. Renders a single
 * line: "{walks} walks · {distance} km" with locale-independent
 * ASCII digits (Stage 8-A `Locale.ROOT` lesson — Arabic/Persian/Hindi
 * default-locale `NumberFormat` would mix non-ASCII digits with the
 * surrounding Latin copy).
 *
 * Three states:
 *  - `stats == null` → loading copy (cache hasn't loaded yet, or the
 *    very first install pre-fetch).
 *  - `stats.totalWalks == 0` → resting copy (fresh community / repo).
 *  - else → walks · km line.
 */
@Composable
fun CollectiveStatsCard(
    stats: CollectiveStats?,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val text = when {
                stats == null -> stringResource(R.string.collective_stats_loading)
                stats.totalWalks == 0 -> stringResource(R.string.collective_stats_unavailable)
                else -> {
                    // Locale.ROOT for both: ASCII digits + grouping
                    // commas, no shared mutable NumberFormat instance.
                    val walksFmt = String.format(Locale.ROOT, "%,d", stats.totalWalks)
                    // Stage 10-C: convert backend's metric km to the
                    // user's preferred unit at display time. Backend
                    // contract stays metric (Stage 8-B); the conversion
                    // is purely cosmetic. The string resources still
                    // hard-code "km" — choose between two distinct
                    // labels per unit so we don't post-hoc replace
                    // the unit suffix in a localized string.
                    val (distanceFmt, distanceRes) = when (units) {
                        UnitSystem.Metric -> {
                            val km = stats.totalDistanceKm
                            String.format(Locale.ROOT, "%.0f", km) to
                                if (stats.totalWalks == 1) {
                                    R.string.collective_stats_one_walk
                                } else {
                                    R.string.collective_stats_walks_distance
                                }
                        }
                        UnitSystem.Imperial -> {
                            // 1 km = 0.621371 mi.
                            val mi = stats.totalDistanceKm * 0.621371
                            String.format(Locale.ROOT, "%.0f", mi) to
                                if (stats.totalWalks == 1) {
                                    R.string.collective_stats_one_walk_mi
                                } else {
                                    R.string.collective_stats_walks_distance_mi
                                }
                        }
                    }
                    stringResource(distanceRes, walksFmt, distanceFmt)
                }
            }
            Text(text = text, style = pilgrimType.body)
        }
    }
}
