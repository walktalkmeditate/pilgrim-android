// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * "Insights" card on Walk Summary. Two optional text rows (meditation
 * count/duration, talk percentage). iOS reference:
 * `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityInsightsView.swift`.
 *
 * Caller is responsible for the empty-state guard — this composable
 * always renders at least the header when invoked.
 */
@Composable
fun WalkActivityInsightsCard(
    talkMillis: Long,
    activeMillis: Long,
    meditationIntervals: List<ActivityInterval>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.xs))
                Text(
                    text = stringResource(R.string.summary_insights_header),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
            }
            meditationInsightText(meditationIntervals)?.let { msg ->
                Text(text = msg, style = pilgrimType.body, color = pilgrimColors.fog)
            }
            talkInsightText(talkMillis, activeMillis)?.let { msg ->
                Text(text = msg, style = pilgrimType.body, color = pilgrimColors.fog)
            }
        }
    }
}

@Composable
private fun meditationInsightText(intervals: List<ActivityInterval>): String? {
    if (intervals.isEmpty()) return null
    val longestMs = intervals.maxOf { it.endTimestamp - it.startTimestamp }
    val longestFmt = formatCompactDuration(longestMs)
    return if (intervals.size == 1) {
        stringResource(R.string.summary_insight_meditated_once, longestFmt)
    } else {
        stringResource(R.string.summary_insight_meditated_multiple, intervals.size, longestFmt)
    }
}

@Composable
private fun talkInsightText(talkMillis: Long, activeMillis: Long): String? {
    if (talkMillis <= 0L || activeMillis <= 0L) return null
    val pct = ((talkMillis * 100) / activeMillis).toInt()
    return stringResource(R.string.summary_insight_talked_pct, pct)
}

@Composable
private fun formatCompactDuration(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L).toInt()
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return when {
        totalSeconds < 60 ->
            stringResource(R.string.summary_compact_duration_seconds, totalSeconds)
        seconds == 0 ->
            stringResource(R.string.summary_compact_duration_minutes, minutes)
        else ->
            stringResource(R.string.summary_compact_duration_minutes_seconds, minutes, seconds)
    }
}
