// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.collective.CollectiveMilestone
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PilgrimageProgress
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.settings.about.AboutSeasonHelpers
import org.walktalkmeditate.pilgrim.ui.settings.about.Season
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-faithful summary header at the top of Settings. Mirrors
 * `pilgrim-ios/Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift`.
 *
 * Four sections (top-to-bottom):
 *  - Seasonal label "{Season} {Year}" with a faded glyph
 *  - Per-user stats whisper (tap to cycle distance/meditation/since)
 *  - Pilgrimage progress (when collective stats present, plus streak)
 *  - Optional milestone overlay (italic moss banner, bell + auto-dismiss
 *    after 8s) when [milestone] is non-null. The bell fires exactly once
 *    per milestone *number* — re-keyed `LaunchedEffect` plus a
 *    [rememberSaveable] number latch survives recomposition AND rotation.
 */
@Composable
fun PracticeSummaryHeader(
    walkCount: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
    firstWalkInstant: Instant?,
    distanceUnits: UnitSystem,
    collectiveStats: CollectiveStats?,
    milestone: CollectiveMilestone? = null,
    onMilestoneShown: (CollectiveMilestone) -> Unit = {},
    onMilestoneDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var statPhase by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SeasonLabel()

        if (walkCount > 0) {
            AnimatedContent(
                targetState = statPhase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "practice-summary-stats",
            ) { phase ->
                Text(
                    text = currentStatLine(
                        phase = phase,
                        walkCount = walkCount,
                        totalDistanceMeters = totalDistanceMeters,
                        totalMeditationSeconds = totalMeditationSeconds,
                        firstWalkInstant = firstWalkInstant,
                        distanceUnits = distanceUnits,
                    ),
                    style = pilgrimType.body,
                    color = pilgrimColors.stone,
                    modifier = Modifier.clickable { statPhase = (statPhase + 1) % 3 },
                )
            }
        }

        val stats = collectiveStats
        if (stats != null && stats.totalWalks > 0) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = PilgrimageProgress.from(stats.totalDistanceKm).message,
                    style = pilgrimType.caption.copy(fontStyle = FontStyle.Italic),
                    color = pilgrimColors.stone,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = collectiveStatsLine(stats, distanceUnits),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
                val streakDays = stats.streakDays ?: 0
                if (streakDays > 1) {
                    StreakFlame(days = streakDays, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        // Cache the most recent non-null milestone so AnimatedVisibility's
        // 500ms fadeOut has content to fade. If we read `milestone` directly
        // inside the content lambda, the dismiss-driven null transition makes
        // the lambda return zero composables and the banner snaps out
        // instantly. Holding the previous value through the fade keeps the
        // crossfade visible. The LaunchedEffect inside still keys on
        // ms.number so the bell-fired latch remains correct.
        //
        // Seed `displayedMilestone` with the current `milestone` value during
        // the initial composition (rather than null + an after-the-fact
        // LaunchedEffect) so the inner LaunchedEffect that drives the 8s
        // auto-dismiss starts on frame 0 instead of frame 1 — otherwise the
        // dismiss timer is offset by a frame, which would break tests that
        // assert exact 8000ms thresholds via `mainClock.advanceTimeBy`.
        val displayedMilestone = remember { mutableStateOf<CollectiveMilestone?>(milestone) }
        if (milestone != null && milestone != displayedMilestone.value) {
            displayedMilestone.value = milestone
        }

        AnimatedVisibility(
            visible = milestone != null,
            enter = fadeIn(animationSpec = tween(500, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(500, easing = FastOutSlowInEasing)),
        ) {
            displayedMilestone.value?.let { ms ->
                val firedFor = rememberSaveable { mutableStateOf<Int?>(null) }
                LaunchedEffect(ms.number) {
                    if (firedFor.value != ms.number) {
                        firedFor.value = ms.number
                        onMilestoneShown(ms)
                    }
                    delay(8_000L)
                    onMilestoneDismiss()
                }
                Text(
                    text = ms.message,
                    style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
                    color = pilgrimColors.stone,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            color = pilgrimColors.moss.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SeasonLabel() {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    // iOS reads `hemisphereOverride` (default 1 = northern). Android
    // doesn't yet expose a hemisphere preference; pass latitude=0.0 so
    // [AboutSeasonHelpers.season] picks the northern branch (its
    // `latitude >= 0` check). Acceptable port-time degradation.
    val season = AboutSeasonHelpers.season(now, latitude = 0.0, zone)
    val year = now.atZone(zone).year
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(seasonLabel(season), year),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Icon(
            imageVector = seasonIcon(season),
            contentDescription = null,
            tint = pilgrimColors.fog.copy(alpha = 0.3f),
            modifier = Modifier
                .padding(start = 2.dp)
                .size(12.dp),
        )
    }
}

private fun seasonLabel(season: Season): Int = when (season) {
    Season.Spring -> R.string.practice_summary_season_spring
    Season.Summer -> R.string.practice_summary_season_summer
    Season.Autumn -> R.string.practice_summary_season_autumn
    Season.Winter -> R.string.practice_summary_season_winter
}

private fun seasonIcon(season: Season): ImageVector = when (season) {
    Season.Spring -> Icons.Filled.Spa
    Season.Summer -> Icons.Filled.WbSunny
    Season.Autumn -> Icons.Filled.Air
    Season.Winter -> Icons.Filled.AcUnit
}

@Composable
private fun currentStatLine(
    phase: Int,
    walkCount: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
    firstWalkInstant: Instant?,
    distanceUnits: UnitSystem,
): String = when (phase) {
    1 -> meditationStatLine(totalMeditationSeconds)
    2 -> walkingSinceLine(firstWalkInstant) ?: statsLine(walkCount, totalDistanceMeters, distanceUnits)
    else -> statsLine(walkCount, totalDistanceMeters, distanceUnits)
}

@Composable
private fun statsLine(walkCount: Int, totalDistanceMeters: Double, units: UnitSystem): String {
    val distKm = totalDistanceMeters / 1_000.0
    return when (units) {
        UnitSystem.Metric -> {
            val distFmt = String.format(Locale.US, "%.0f", distKm)
            pluralStringResource(
                R.plurals.practice_summary_walks_distance_metric,
                walkCount,
                walkCount,
                distFmt,
            )
        }
        UnitSystem.Imperial -> {
            val distFmt = String.format(Locale.US, "%.0f", distKm * 0.621371)
            pluralStringResource(
                R.plurals.practice_summary_walks_distance_imperial,
                walkCount,
                walkCount,
                distFmt,
            )
        }
    }
}

@Composable
private fun meditationStatLine(totalMeditationSeconds: Long): String {
    if (totalMeditationSeconds <= 0L) {
        return stringResource(R.string.practice_summary_meditation_no_meditation)
    }
    val hours = totalMeditationSeconds / 3_600
    val minutes = ((totalMeditationSeconds % 3_600) / 60).toInt()
    return if (hours > 0) {
        stringResource(R.string.practice_summary_meditation_hours_minutes, hours.toInt(), minutes)
    } else {
        stringResource(R.string.practice_summary_meditation_minutes, minutes)
    }
}

@Composable
private fun walkingSinceLine(firstWalkInstant: Instant?): String? {
    val instant = firstWalkInstant ?: return null
    val formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    val dateText = formatter.format(instant.atZone(ZoneId.systemDefault()))
    return stringResource(R.string.practice_summary_walking_since, dateText)
}

@Composable
private fun collectiveStatsLine(stats: CollectiveStats, units: UnitSystem): String {
    return when (units) {
        UnitSystem.Metric -> {
            val distFmt = String.format(Locale.US, "%.0f", stats.totalDistanceKm)
            pluralStringResource(
                R.plurals.practice_summary_walks_distance_metric,
                stats.totalWalks,
                stats.totalWalks,
                distFmt,
            )
        }
        UnitSystem.Imperial -> {
            val distFmt = String.format(Locale.US, "%.0f", stats.totalDistanceKm * 0.621371)
            pluralStringResource(
                R.plurals.practice_summary_walks_distance_imperial,
                stats.totalWalks,
                stats.totalWalks,
                distFmt,
            )
        }
    }
}
