// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker

/**
 * The raw cardinal accent for a turning marker — jade (spring equinox),
 * gold (summer solstice), claret (autumn equinox), indigo (winter
 * solstice). Null for cross-quarter markers and non-turning days; iOS
 * only assigns colors to the four cardinals
 * (`SeasonalMarker+Turnings.swift` `colorAssetName` @fcd2255).
 *
 * These tokens are intentionally NOT seasonally shifted (the seasonal
 * engine leaves `turning*` untouched), so callers may pass either the
 * raw or the shifted [PilgrimColors] — the turning slots are identical
 * in both.
 */
fun turningAccentColor(marker: SeasonalMarker?, colors: PilgrimColors): Color? =
    when (marker) {
        SeasonalMarker.SpringEquinox -> colors.turningJade
        SeasonalMarker.SummerSolstice -> colors.turningGold
        SeasonalMarker.AutumnEquinox -> colors.turningClaret
        SeasonalMarker.WinterSolstice -> colors.turningIndigo
        else -> null
    }
