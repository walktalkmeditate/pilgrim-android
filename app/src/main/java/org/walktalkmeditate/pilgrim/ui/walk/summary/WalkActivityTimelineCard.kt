// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

private const val BAR_HEIGHT_DP = 16
private const val TALK_HEIGHT_DP = 10
private const val PACE_HEIGHT_DP = 40

@Composable
fun WalkActivityTimelineCard(
    startTimestamp: Long,
    endTimestamp: Long,
    voiceRecordings: List<VoiceRecording>,
    activityIntervals: List<ActivityInterval>,
    routeSamples: List<RouteDataSample>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val segments = remember(startTimestamp, endTimestamp, activityIntervals, voiceRecordings) {
        computeTimelineSegments(startTimestamp, endTimestamp, activityIntervals, voiceRecordings)
    }
    val sparklinePoints = remember(routeSamples, startTimestamp, endTimestamp) {
        computePaceSparklinePoints(routeSamples, startTimestamp, endTimestamp)
    }
    val avgPaceLabel = remember(routeSamples, units) {
        averagePaceLabel(routeSamples, units)
    }
    var selectedId by remember(segments) { mutableStateOf<Int?>(null) }
    var showRelativeTime by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            TimelineBar(
                segments = segments,
                selectedId = selectedId,
                onSegmentTapped = { id ->
                    selectedId = if (selectedId == id) null else id
                },
            )
            selectedId?.let { id ->
                segments.firstOrNull { it.id == id }?.let { seg ->
                    SelectedTooltip(seg, showRelativeTime)
                }
            }
            TimeLabelsRow(
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                showRelativeTime = showRelativeTime,
                onToggle = { showRelativeTime = !showRelativeTime },
            )
            if (sparklinePoints.size >= 2) {
                if (avgPaceLabel != null) {
                    Text(
                        text = stringResource(R.string.summary_timeline_pace_label, avgPaceLabel),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
                PaceSparklineCanvas(sparklinePoints)
            }
            LegendRow()
        }
    }
}

@Composable
private fun TimelineBar(
    segments: List<TimelineSegment>,
    selectedId: Int?,
    onSegmentTapped: (Int) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(pilgrimColors.moss.copy(alpha = 0.4f))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    if (widthPx <= 0) return@detectTapGestures
                    val frac = offset.x / widthPx
                    segments.firstOrNull {
                        frac >= it.startFraction && frac <= it.startFraction + it.widthFraction
                    }?.let { onSegmentTapped(it.id) }
                }
            },
        // iOS centers shorter segments (talk = 10dp) vertically within the
        // 16dp bar via ZStack's default vertical alignment. Compose Box's
        // default is TopStart, which would push talk segments to the top of
        // the bar with 6dp empty below. CenterStart matches iOS.
        contentAlignment = Alignment.CenterStart,
    ) {
        // Meditation segments first (taller — drawn under talks).
        segments.filter { it.type == TimelineSegmentType.Meditating }.forEach { seg ->
            SegmentRect(seg, BAR_HEIGHT_DP, pilgrimColors.dawn, selectedId == seg.id, widthPx)
        }
        segments.filter { it.type == TimelineSegmentType.Talking }.forEach { seg ->
            SegmentRect(seg, TALK_HEIGHT_DP, pilgrimColors.rust, selectedId == seg.id, widthPx)
        }
    }
}

@Composable
private fun SegmentRect(
    segment: TimelineSegment,
    heightDp: Int,
    color: Color,
    isSelected: Boolean,
    parentWidthPx: Int,
) {
    if (parentWidthPx <= 0) return
    val density = LocalDensity.current
    val xOffsetPx = with(density) { (parentWidthPx * segment.startFraction).toDp() }
    val widthDp = with(density) {
        (parentWidthPx * segment.widthFraction).coerceAtLeast(2f).toDp()
    }
    Box(
        modifier = Modifier
            .offset(x = xOffsetPx)
            .width(widthDp)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = if (isSelected) 0.95f else 0.7f)),
    )
}

@Composable
private fun SelectedTooltip(seg: TimelineSegment, showRelativeTime: Boolean) {
    val labelRes = when (seg.type) {
        TimelineSegmentType.Talking -> R.string.summary_timeline_legend_talk
        TimelineSegmentType.Meditating -> R.string.summary_timeline_legend_meditate
    }
    val color = when (seg.type) {
        TimelineSegmentType.Talking -> pilgrimColors.rust
        TimelineSegmentType.Meditating -> pilgrimColors.dawn
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = stringResource(labelRes),
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
        Text(
            text = formatCompactDurationCaption(seg.endMillis - seg.startMillis),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        if (!showRelativeTime) {
            Text(
                text = formatAbsoluteTime(seg.startMillis),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun TimeLabelsRow(
    startTimestamp: Long,
    endTimestamp: Long,
    showRelativeTime: Boolean,
    onToggle: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null) { onToggle() },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val left =
            if (showRelativeTime) String.format(Locale.US, "%d:%02d", 0, 0)
            else formatAbsoluteTime(startTimestamp)
        val right =
            if (showRelativeTime) WalkFormat.duration(endTimestamp - startTimestamp)
            else formatAbsoluteTime(endTimestamp)
        Text(text = left, style = pilgrimType.caption, color = pilgrimColors.fog)
        Text(text = right, style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}

@Composable
private fun PaceSparklineCanvas(points: List<PaceSparklinePoint>) {
    val stoneFill = pilgrimColors.stone
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PACE_HEIGHT_DP.dp),
    ) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val fillPath = Path().apply {
            moveTo(points.first().xFraction * w, h)
            for (p in points) lineTo(p.xFraction * w, p.yFraction * h)
            lineTo(points.last().xFraction * w, h)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(stoneFill.copy(alpha = 0.12f), stoneFill.copy(alpha = 0.02f)),
                startY = 0f,
                endY = h,
            ),
        )
        val strokePath = Path().apply {
            moveTo(points.first().xFraction * w, points.first().yFraction * h)
            for (p in points.drop(1)) lineTo(p.xFraction * w, p.yFraction * h)
        }
        drawPath(
            path = strokePath,
            color = stoneFill.copy(alpha = 0.45f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(pilgrimColors.moss, R.string.summary_timeline_legend_walk)
        LegendDot(pilgrimColors.rust, R.string.summary_timeline_legend_talk)
        LegendDot(pilgrimColors.dawn, R.string.summary_timeline_legend_meditate)
    }
}

@Composable
private fun LegendDot(color: Color, labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(labelRes), style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}

private fun formatAbsoluteTime(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    return DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

private fun formatCompactDurationCaption(millis: Long): String {
    val total = (millis / 1_000L).coerceAtLeast(0L).toInt()
    return if (total < 60) "${total}s" else "${total / 60}m"
}

private fun averagePaceLabel(samples: List<RouteDataSample>, units: UnitSystem): String? {
    val speeds = samples.mapNotNull { it.speedMetersPerSecond }.filter { it > 0.3f }
    if (speeds.isEmpty()) return null
    val avgMps = speeds.average()
    if (avgMps <= 0.0) return null
    val secPerKm = 1000.0 / avgMps
    return WalkFormat.pace(secPerKm, units)
}
