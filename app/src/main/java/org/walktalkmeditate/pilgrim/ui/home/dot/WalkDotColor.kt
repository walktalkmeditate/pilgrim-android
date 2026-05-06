// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors

/**
 * Walk-dot color rule. Default: moss (the walk color). On equinoxes
 * and solstices, the dot + thread take the turning's accent color
 * (jade / gold / claret / indigo). Cross-quarter markers
 * (Beltane / Lughnasadh / Samhain / Imbolc) fall through to moss —
 * iOS GoshuinFAB only assigns colors to the four cardinal turnings.
 *
 * Stage 14-BCD task 2: the per-walk turning lookup is now shared with
 * [TurningDayBanner] via `turningMarkerForEpochMillis` in
 * `SeasonalMarkerTurnings.kt`.
 */
fun walkDotBaseColor(
    walkStartMs: Long,
    colors: PilgrimColors,
): Color {
    val turning = turningMarkerForEpochMillis(walkStartMs)
    return when (turning) {
        SeasonalMarker.SpringEquinox -> colors.turningJade
        SeasonalMarker.SummerSolstice -> colors.turningGold
        SeasonalMarker.AutumnEquinox -> colors.turningClaret
        SeasonalMarker.WinterSolstice -> colors.turningIndigo
        else -> colors.moss
    }
}
