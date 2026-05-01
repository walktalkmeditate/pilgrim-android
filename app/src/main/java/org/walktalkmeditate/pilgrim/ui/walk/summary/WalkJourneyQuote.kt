// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Contextual quote below the elevation profile (when present) and
 * above the duration hero. Seven cases driven by [classifyJourneyQuote];
 * mirrors iOS `WalkSummaryView.journeyQuote` (`WalkSummaryView.swift:314-322,
 * 396-417`).
 *
 * Distance formatting goes through the user's preferred [UnitSystem] —
 * iOS does the same via `formatDistance(walk.distance)` which checks
 * `UserPreferences.distanceMeasurementType`.
 */
@Composable
fun WalkJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
    distanceUnits: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val text = when (val case = classifyJourneyQuote(
        talkMillis = talkMillis,
        meditateMillis = meditateMillis,
        distanceMeters = distanceMeters,
    )) {
        JourneyQuoteCase.WalkTalkMeditate ->
            stringResource(R.string.summary_quote_walk_talk_meditate)
        JourneyQuoteCase.MeditateShort ->
            stringResource(R.string.summary_quote_meditate_short_distance)
        is JourneyQuoteCase.MeditateWithDistance ->
            stringResource(
                R.string.summary_quote_meditate_with_distance,
                WalkFormat.distance(case.distanceMeters, distanceUnits),
            )
        JourneyQuoteCase.TalkOnly ->
            stringResource(R.string.summary_quote_talk_only)
        JourneyQuoteCase.LongRoad ->
            stringResource(R.string.summary_quote_long_road)
        JourneyQuoteCase.SmallArrival ->
            stringResource(R.string.summary_quote_small_arrival)
        JourneyQuoteCase.QuietWalk ->
            stringResource(R.string.summary_quote_quiet_walk)
    }
    Text(
        text = text,
        style = pilgrimType.body,
        color = pilgrimColors.fog,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.big),
    )
}
