// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
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
 * Three sections (top-to-bottom):
 *  - Seasonal label "{Season} {Year}" with a faded glyph
 *  - Per-user stats whisper (tap to cycle distance/meditation/since)
 *  - Pilgrimage progress (when collective stats present, plus streak)
 *
 * Milestone overlay (iOS shows + bell + auto-dismiss after 8s) is
 * OUT OF SCOPE for this stage — tracked as a follow-up that needs
 * `lastSeenCollectiveWalks` DataStore persistence + BellPlayer wiring.
 */
@Composable
fun PracticeSummaryHeader(
    walkCount: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
    firstWalkInstant: Instant?,
    distanceUnits: UnitSystem,
    collectiveStats: CollectiveStats?,
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

private fun statsLine(walkCount: Int, totalDistanceMeters: Double, units: UnitSystem): String {
    val distKm = totalDistanceMeters / 1_000.0
    val (dist, unit) = when (units) {
        UnitSystem.Metric -> distKm to "km"
        UnitSystem.Imperial -> (distKm * 0.621371) to "mi"
    }
    val walkLabel = if (walkCount == 1) "walk" else "walks"
    return String.format(Locale.US, "%d %s · %.0f %s", walkCount, walkLabel, dist, unit)
}

private fun meditationStatLine(totalMeditationSeconds: Long): String {
    if (totalMeditationSeconds <= 0L) return "No meditation yet"
    val hours = totalMeditationSeconds / 3_600
    val minutes = (totalMeditationSeconds % 3_600) / 60
    return if (hours > 0) "${hours}h ${minutes}m in stillness" else "${minutes}m in stillness"
}

private fun walkingSinceLine(firstWalkInstant: Instant?): String? {
    val instant = firstWalkInstant ?: return null
    val formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    return "Walking since ${formatter.format(instant.atZone(ZoneId.systemDefault()))}"
}

private fun collectiveStatsLine(stats: CollectiveStats, units: UnitSystem): String {
    val (dist, unit) = when (units) {
        UnitSystem.Metric -> stats.totalDistanceKm to "km"
        UnitSystem.Imperial -> (stats.totalDistanceKm * 0.621371) to "mi"
    }
    val walkLabel = if (stats.totalWalks == 1) "walk" else "walks"
    return String.format(Locale.US, "%d %s · %.0f %s", stats.totalWalks, walkLabel, dist, unit)
}
