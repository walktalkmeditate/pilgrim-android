// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * "Activities" card listing every voice recording + meditation interval
 * sorted by start timestamp. iOS reference:
 * `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityListView.swift`.
 *
 * Caller-side empty-state guard.
 */
@Composable
fun WalkActivityListCard(
    voiceRecordings: List<VoiceRecording>,
    meditationIntervals: List<ActivityInterval>,
    modifier: Modifier = Modifier,
) {
    val talkTint = pilgrimColors.rust
    val medTint = pilgrimColors.dawn
    val entries = remember(voiceRecordings, meditationIntervals, talkTint, medTint) {
        buildEntries(voiceRecordings, meditationIntervals, talkTint, medTint)
    }
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
                    imageVector = Icons.AutoMirrored.Rounded.FormatListBulleted,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.xs))
                Text(
                    text = stringResource(R.string.summary_activities_header),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
            }
            entries.forEach { entry -> ActivityRow(entry) }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityListEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(entry.tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(PilgrimSpacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(entry.nameRes),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = entry.timeRange,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Text(
            text = WalkFormat.duration(entry.durationMillis),
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * `@Immutable` because Compose can't infer stability from `ImageVector`
 * + `Color` field types. Without the annotation, the parent's
 * `forEach { ActivityRow(it) }` recomposes EVERY row on any unrelated
 * recomposition (theme flip, reveal-phase transition, etc.). Same
 * lesson as Stage 4-C `GoshuinSeal` — external-module field types
 * cascade Unstable unless the holding class declares the contract.
 */
@Immutable
private data class ActivityListEntry(
    val icon: ImageVector,
    val tint: Color,
    val nameRes: Int,
    val startMillis: Long,
    val timeRange: String,
    val durationMillis: Long,
)

private fun buildEntries(
    voiceRecordings: List<VoiceRecording>,
    meditationIntervals: List<ActivityInterval>,
    talkTint: Color,
    medTint: Color,
): List<ActivityListEntry> {
    val out = mutableListOf<ActivityListEntry>()
    for (rec in voiceRecordings) {
        out += ActivityListEntry(
            icon = Icons.Rounded.GraphicEq,
            tint = talkTint,
            nameRes = R.string.summary_activity_talk,
            startMillis = rec.startTimestamp,
            timeRange = formatTimeRange(rec.startTimestamp, rec.endTimestamp),
            durationMillis = rec.durationMillis,
        )
    }
    for (m in meditationIntervals) {
        out += ActivityListEntry(
            icon = Icons.Rounded.SelfImprovement,
            tint = medTint,
            nameRes = R.string.summary_activity_meditate,
            startMillis = m.startTimestamp,
            timeRange = formatTimeRange(m.startTimestamp, m.endTimestamp),
            durationMillis = m.endTimestamp - m.startTimestamp,
        )
    }
    return out.sortedBy { it.startMillis }
}

private fun formatTimeRange(startMs: Long, endMs: Long): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    val s = fmt.format(Instant.ofEpochMilli(startMs).atZone(zone))
    val e = fmt.format(Instant.ofEpochMilli(endMs).atZone(zone))
    return "$s – $e"
}
