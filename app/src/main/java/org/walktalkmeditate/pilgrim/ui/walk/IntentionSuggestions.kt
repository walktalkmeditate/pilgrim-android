// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshotCalc
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacSign
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Contemplative intention suggestions derived from the current sky.
 * Verbatim port of iOS `IntentionSettingView.celestialIntentionSuggestions()`
 * (`IntentionSettingView.swift:153-197`): priority order is seasonal
 * marker → retrograde (mercury > venus > mars) → lunar extreme →
 * dominant element, capped at the first 3.
 *
 * Pure + time-injectable for deterministic tests. Strings are the
 * exact English literals iOS uses (iOS does not localize these); the
 * caller gates on `celestialAwarenessEnabled` + empty draft, mirroring
 * iOS — this function only computes.
 */
object IntentionSuggestions {

    const val MAX = 3

    fun celestial(
        atEpochMillis: Long,
        system: ZodiacSystem = ZodiacSystem.Tropical,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val snapshot = CelestialSnapshotCalc.snapshot(atEpochMillis, zoneId, system)
        val out = mutableListOf<String>()

        snapshot.seasonalMarker?.let { marker ->
            out += when (marker) {
                SeasonalMarker.SpringEquinox -> "Cross a threshold"
                SeasonalMarker.SummerSolstice -> "Walk in fullness"
                SeasonalMarker.AutumnEquinox -> "Find balance"
                SeasonalMarker.WinterSolstice -> "Honor the stillness"
                SeasonalMarker.Imbolc -> "Notice what's stirring"
                SeasonalMarker.Beltane -> "Celebrate what's alive"
                SeasonalMarker.Lughnasadh -> "Gather what you've grown"
                SeasonalMarker.Samhain -> "Remember what matters"
            }
        }

        val retrogrades = snapshot.positions.filter { it.isRetrograde }.map { it.planet }
        when {
            Planet.Mercury in retrogrades -> out += "Revisit something left unsaid"
            Planet.Venus in retrogrades -> out += "Reconsider what you value"
            Planet.Mars in retrogrades -> out += "Slow down, redirect energy"
        }

        val illumination = MoonCalc.moonPhase(Instant.ofEpochMilli(atEpochMillis)).illumination
        when {
            illumination > 0.97 -> out += "Release what no longer serves"
            illumination < 0.03 -> out += "Plant a seed of beginning"
        }

        snapshot.elementBalance.dominant?.let { dominant ->
            out += when (dominant) {
                ZodiacSign.Element.Water -> "Follow what flows"
                ZodiacSign.Element.Fire -> "Walk with purpose"
                ZodiacSign.Element.Earth -> "Feel the ground beneath you"
                ZodiacSign.Element.Air -> "Let thoughts move freely"
            }
        }

        return out.take(MAX)
    }
}
