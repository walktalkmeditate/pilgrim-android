// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Walk-dot + connecting-thread color, ported from iOS
 * `WalkDotView.swift:166-180` (dot) and `InkScrollView.swift:287-303,
 * 657-674` (thread) @fcd2255.
 *
 * Rule for either surface:
 *  - **Turning day** (solstice/equinox): the cardinal accent
 *    (jade / gold / claret / indigo), used RAW — iOS does not seasonally
 *    shift turning colors. The thread dims it to 0.85 opacity; the dot
 *    keeps it opaque.
 *  - **Any other day**: the season base by calendar month
 *    (moss / rust / dawn / ink), then a hemisphere-aware HSB seasonal
 *    shift — Full intensity for the dot, Moderate for the thread.
 *
 * IMPORTANT: callers must pass the RAW (un-shifted) palette here — e.g.
 * `pilgrimLightColors()` / `pilgrimDarkColors()`, NOT `LocalPilgrimColors`
 * which is already seasonally shifted at "today". The shift is applied
 * once, at the WALK's date, exactly as iOS resolves the asset color and
 * shifts `on: snapshot.startDate`.
 */

/** iOS `InkScrollView.swift:659` — `turningColor.opacity(0.85)` for the thread. */
private const val THREAD_TURNING_OPACITY = 0.85f

/** Dot keeps the turning accent fully opaque. */
private const val DOT_TURNING_OPACITY = 1.0f

/**
 * Raw cardinal accent for the four turnings; null for cross-quarter
 * markers and non-turning days (iOS only colors the four cardinals).
 */
internal fun turningAccentColor(marker: SeasonalMarker?, base: PilgrimColors): Color? =
    when (marker) {
        SeasonalMarker.SpringEquinox -> base.turningJade
        SeasonalMarker.SummerSolstice -> base.turningGold
        SeasonalMarker.AutumnEquinox -> base.turningClaret
        SeasonalMarker.WinterSolstice -> base.turningIndigo
        else -> null
    }

/**
 * Season base token by calendar month. NOT hemisphere-adjusted — iOS
 * buckets on the raw `Calendar.component(.month)` and only the HSB shift
 * is hemisphere-aware.
 */
internal fun seasonBaseColor(month: Int, base: PilgrimColors): Color =
    when (month) {
        in 3..5 -> base.moss
        in 6..8 -> base.rust
        in 9..11 -> base.dawn
        else -> base.ink
    }

internal fun walkInkColor(
    marker: SeasonalMarker?,
    date: LocalDate,
    base: PilgrimColors,
    hemisphere: Hemisphere,
    intensity: SeasonalColorEngine.Intensity,
    turningOpacity: Float,
): Color {
    turningAccentColor(marker, base)?.let { return it.copy(alpha = turningOpacity) }
    return SeasonalColorEngine.applySeasonalShift(
        base = seasonBaseColor(date.monthValue, base),
        intensity = intensity,
        date = date,
        hemisphere = hemisphere,
    )
}

private fun walkLocalDate(walkStartMs: Long): LocalDate =
    Instant.ofEpochMilli(walkStartMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** Dot color — turning accent (raw) or season base + Full shift. */
fun walkDotColor(walkStartMs: Long, base: PilgrimColors, hemisphere: Hemisphere): Color =
    walkInkColor(
        marker = turningMarkerForEpochMillis(walkStartMs),
        date = walkLocalDate(walkStartMs),
        base = base,
        hemisphere = hemisphere,
        intensity = SeasonalColorEngine.Intensity.Full,
        turningOpacity = DOT_TURNING_OPACITY,
    )

/** Connecting-thread color — turning accent × 0.85 or season base + Moderate shift. */
fun walkThreadColor(walkStartMs: Long, base: PilgrimColors, hemisphere: Hemisphere): Color =
    walkInkColor(
        marker = turningMarkerForEpochMillis(walkStartMs),
        date = walkLocalDate(walkStartMs),
        base = base,
        hemisphere = hemisphere,
        intensity = SeasonalColorEngine.Intensity.Moderate,
        turningOpacity = THREAD_TURNING_OPACITY,
    )
