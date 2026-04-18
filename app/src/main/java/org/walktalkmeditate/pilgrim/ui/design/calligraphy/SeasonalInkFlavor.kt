// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Northern-hemisphere month → base color bucket. Stage 3-C shipped
 * the flat version; Stage 3-D wraps the returned color with an HSB
 * shift computed from the walk's date + the device hemisphere via
 * [toSeasonalColor].
 */
enum class SeasonalInkFlavor {
    Ink, Moss, Rust, Dawn;

    companion object {
        fun forMonth(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SeasonalInkFlavor {
            val month = Instant.ofEpochMilli(startMillis).atZone(zone).monthValue
            return when (month) {
                in 3..5 -> Moss
                in 6..8 -> Rust
                in 9..11 -> Dawn
                else -> Ink
            }
        }
    }
}

/**
 * Resolve this flavor into the base PilgrimColors token, without
 * seasonal shifting. Prefer [toSeasonalColor] for anything rendered
 * to the user; this is an escape hatch for callers that intentionally
 * want the flat token (e.g., a legend swatch).
 */
@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toBaseColor(): Color = when (this) {
    SeasonalInkFlavor.Ink -> pilgrimColors.ink
    SeasonalInkFlavor.Moss -> pilgrimColors.moss
    SeasonalInkFlavor.Rust -> pilgrimColors.rust
    SeasonalInkFlavor.Dawn -> pilgrimColors.dawn
}

/**
 * Resolve this flavor into a seasonally-shifted color for rendering.
 * Callers supply the walk's [date] and the device [hemisphere]
 * (typically from
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository]).
 *
 * Default [intensity] is [SeasonalColorEngine.Intensity.Moderate],
 * matching iOS's `pathSegmentColor` call site. Walk-dot renderings
 * should pass `Full`; map backgrounds `Minimal`.
 */
@Composable
fun SeasonalInkFlavor.toSeasonalColor(
    date: LocalDate,
    hemisphere: Hemisphere,
    intensity: SeasonalColorEngine.Intensity = SeasonalColorEngine.Intensity.Moderate,
): Color {
    val base = toBaseColor()
    return SeasonalColorEngine.applySeasonalShift(base, intensity, date, hemisphere)
}

/**
 * Legacy flat accessor. Preserved as a deprecated shim so any in-
 * flight branch keeps compiling; new code should use
 * [toSeasonalColor]. Removed when Stage 3-E lands and the preview
 * screen is deleted.
 */
@Deprecated(
    message = "Use toSeasonalColor(date, hemisphere) — Stage 3-D introduces date-driven HSB shifts.",
    replaceWith = ReplaceWith("toSeasonalColor(date, hemisphere)"),
    level = DeprecationLevel.WARNING,
)
@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toColor(): Color = toBaseColor()
