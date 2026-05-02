// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.Context
import java.util.Locale
import kotlin.math.floor
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Stage 13-Cel: pure prose helper for the Walk Summary milestone callout.
 * Mirrors iOS `WalkSummaryView.computeMilestone()` priority chain
 * (`WalkSummaryView.swift:421-461`):
 *   1. SeasonalMarker (only when celestialEnabled)
 *   2. LongestMeditation (strict-improvement-over-nonzero)
 *   3. LongestWalk (strict-improvement-over-nonzero)
 *   4. TotalDistance (threshold crossed on this walk)
 *   5. null  (NO fallthrough to FirstWalk/FirstOfSeason/NthWalk —
 *            those only appear on the Goshuin grid, not Walk Summary)
 *
 * Note: rules 2 & 3 differ from the Goshuin grid detector. The grid
 * fires on max-wins (no nonzero gate) so the FIRST walk with any
 * meditation still earns the seal, while the Walk Summary callout
 * requires strict improvement over a non-zero prior.
 */
data class WalkSummaryCalloutInputs(
    val currentDistanceMeters: Double,
    val currentMeditationSeconds: Long,
    val pastWalksMaxDistance: Double,
    val pastWalksMaxMeditation: Long,
    val pastWalksDistanceSum: Double,
    val units: UnitSystem,
    val seasonalMarker: SeasonalMarker?,
)

object WalkSummaryCalloutProse {
    private val THRESHOLDS = listOf(10, 25, 50, 100, 250, 500, 1000)

    fun compute(
        inputs: WalkSummaryCalloutInputs,
        celestialEnabled: Boolean,
        context: Context,
    ): String? {
        // 1. SeasonalMarker
        if (celestialEnabled && inputs.seasonalMarker != null) {
            return context.getString(seasonalMarkerStringRes(inputs.seasonalMarker))
        }
        // 2. LongestMeditation (strict over nonzero)
        if (inputs.currentMeditationSeconds > inputs.pastWalksMaxMeditation && inputs.pastWalksMaxMeditation > 0L) {
            return context.getString(R.string.summary_milestone_longest_meditation)
        }
        // 3. LongestWalk (strict over nonzero)
        if (inputs.currentDistanceMeters > inputs.pastWalksMaxDistance && inputs.pastWalksMaxDistance > 0.0) {
            return context.getString(R.string.summary_milestone_longest_walk)
        }
        // 4. TotalDistance
        val unitFactor = if (inputs.units == UnitSystem.Imperial) 1_609.344 else 1_000.0
        val totalDistance = inputs.pastWalksDistanceSum + inputs.currentDistanceMeters
        val pastUnits = floor((totalDistance - inputs.currentDistanceMeters) / unitFactor).toInt()
        val totalUnits = floor(totalDistance / unitFactor).toInt()
        for (m in THRESHOLDS) {
            if (totalUnits >= m && pastUnits < m) {
                val template = if (inputs.units == UnitSystem.Imperial) {
                    R.string.summary_milestone_total_distance_mi
                } else {
                    R.string.summary_milestone_total_distance_km
                }
                return context.getString(template, String.format(Locale.US, "%d", m))
            }
        }
        return null
    }

    private fun seasonalMarkerStringRes(marker: SeasonalMarker): Int = when (marker) {
        SeasonalMarker.SpringEquinox -> R.string.summary_milestone_seasonal_spring_equinox
        SeasonalMarker.SummerSolstice -> R.string.summary_milestone_seasonal_summer_solstice
        SeasonalMarker.AutumnEquinox -> R.string.summary_milestone_seasonal_autumn_equinox
        SeasonalMarker.WinterSolstice -> R.string.summary_milestone_seasonal_winter_solstice
        SeasonalMarker.Imbolc -> R.string.summary_milestone_seasonal_imbolc
        SeasonalMarker.Beltane -> R.string.summary_milestone_seasonal_beltane
        SeasonalMarker.Lughnasadh -> R.string.summary_milestone_seasonal_lughnasadh
        SeasonalMarker.Samhain -> R.string.summary_milestone_seasonal_samhain
    }
}
