// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.JourneySummary
import org.walktalkmeditate.pilgrim.ui.home.StatMode
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Two-line header sitting between the top-app-bar and the calligraphy
 * scroll body. Tapping cycles between three stat modes — WALKS / TALKS /
 * MEDITATIONS — verbatim port of iOS `InkScrollView.swift:134-183`.
 *
 *   line 1 (body, dawn-tinted)   : "27.2 mi walked" / "1h 14m talked"
 *   line 2 (caption, fog @ 0.7) : "5 walks · 1 months" / "3 walks with talk"
 *
 * State:
 *  - `rememberSaveable` for the selected mode so a rotation mid-tap
 *    doesn't snap back to WALKS (Stage 5-A `rememberSaveable` lesson —
 *    cycle flags whose reset puts the user in a stuck state warrant
 *    saveable rather than `remember`).
 *
 * Numeric formatting via `Locale.US` (Stage 5-A lesson — default-locale
 * `%d.format(…)` produces non-ASCII digits on Arabic/Persian/Hindi
 * locales, breaking parity with iOS); pluralization via
 * `pluralStringResource` keyed on the device locale so a French user
 * sees "5 marches" not "5 walks".
 */
@Composable
fun JourneySummaryHeader(
    summary: JourneySummary,
    units: UnitSystem,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(StatMode.WALKS) }
    val interactionSource = remember { MutableInteractionSource() }

    val (line1, line2) = when (mode) {
        StatMode.WALKS -> {
            val months = monthsSince(summary.firstWalkStartMs, nowMs)
            stringResource(
                R.string.home_journey_distance,
                formatDistanceLabel(summary.totalDistanceM, units),
            ) to subtitleWalks(summary.walkCount, months)
        }
        StatMode.TALKS -> stringResource(
            R.string.home_journey_talked,
            formatDuration(summary.totalTalkSec),
        ) to pluralStringResource(
            R.plurals.home_journey_walks_with_talk,
            summary.talkerCount,
            summary.talkerCount,
        )
        StatMode.MEDITATIONS -> stringResource(
            R.string.home_journey_meditated,
            formatDuration(summary.totalMeditateSec),
        ) to pluralStringResource(
            R.plurals.home_journey_walks_with_meditate,
            summary.meditatorCount,
            summary.meditatorCount,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { mode = nextMode(mode) },
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line1,
            style = pilgrimType.body,
            color = pilgrimColors.dawn,
            textAlign = TextAlign.Center,
        )
        Text(
            text = line2,
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

private fun nextMode(current: StatMode): StatMode {
    val all = StatMode.entries
    return all[(current.ordinal + 1) % all.size]
}

/**
 * "27.2 mi walked" / "5.4 km walked". Uses single decimal — iOS
 * `InkScrollView.swift:185-199` matches that with `%.1f mi walked`.
 * Below the unit threshold (sub-mile / sub-km), falls back to whole
 * feet / meters.
 */
private fun formatDistanceLabel(meters: Double, units: UnitSystem): String = when (units) {
    UnitSystem.Imperial -> {
        val miles = meters / 1609.344
        if (miles >= 1.0) {
            String.format(Locale.US, "%.1f mi", miles)
        } else {
            val feet = meters * 3.28084
            String.format(Locale.US, "%.0f ft", feet)
        }
    }
    UnitSystem.Metric -> {
        if (meters >= 1000.0) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f m", meters)
        }
    }
}

/**
 * "Xh Ym" or "Xm" — verbatim port of iOS
 * `WalkDotView.swift:178-184`. Seconds are intentionally dropped so a
 * roll-up over many short talks reads cleanly.
 */
private fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) {
        String.format(Locale.US, "%dh %dm", hours, minutes)
    } else {
        String.format(Locale.US, "%dm", minutes)
    }
}

/**
 * iOS uses `Calendar.dateComponents([.month], …).month` floored at 1.
 * Approximated here with a 30-day month — exact-calendar accuracy is
 * not load-bearing for the journal's "N months" caption, and the
 * approximation tolerates DST + month-length variation cleanly.
 */
private fun monthsSince(firstWalkStartMs: Long, nowMs: Long): Int {
    if (firstWalkStartMs <= 0L || nowMs <= firstWalkStartMs) return 1
    val deltaMs = nowMs - firstWalkStartMs
    val months = (deltaMs / (30L * 24L * 60L * 60L * 1000L)).toInt()
    return months.coerceAtLeast(1)
}

@Composable
private fun subtitleWalks(walkCount: Int, months: Int): String {
    val walksLabel = pluralStringResource(
        R.plurals.home_journey_walks_count,
        walkCount,
        walkCount,
    )
    val monthsLabel = pluralStringResource(
        R.plurals.home_journey_months,
        months,
        months,
    )
    return stringResource(R.string.home_journey_subtitle_walks, walksLabel, monthsLabel)
}
