// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.annotation.StringRes
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.walktalkmeditate.pilgrim.R

/** True for the four cardinal turnings (equinox/solstice). False for cross-quarter. */
fun SeasonalMarker.isTurning(): Boolean = when (this) {
    SeasonalMarker.SpringEquinox,
    SeasonalMarker.SummerSolstice,
    SeasonalMarker.AutumnEquinox,
    SeasonalMarker.WinterSolstice -> true
    else -> false
}

/** Verbatim 春分 / 夏至 / 秋分 / 冬至 for the four cardinal turnings; null otherwise. */
fun SeasonalMarker.kanji(): String? = when (this) {
    SeasonalMarker.SpringEquinox -> "春分"
    SeasonalMarker.SummerSolstice -> "夏至"
    SeasonalMarker.AutumnEquinox -> "秋分"
    SeasonalMarker.WinterSolstice -> "冬至"
    else -> null
}

@StringRes
fun SeasonalMarker.bannerTextRes(): Int? = when (this) {
    SeasonalMarker.SpringEquinox, SeasonalMarker.AutumnEquinox ->
        R.string.turning_equinox_banner
    SeasonalMarker.SummerSolstice, SeasonalMarker.WinterSolstice ->
        R.string.turning_solstice_banner
    else -> null
}

/**
 * Compute the SeasonalMarker for a UTC instant. Shared by `WalkDotColor`
 * (per-walk dot color) and `TurningDayBanner` (today's banner).
 */
fun turningMarkerForEpochMillis(epochMillis: Long): SeasonalMarker? {
    val jd = SunCalc.julianDayFromEpochMillis(epochMillis)
    val T = SunCalc.julianCenturies(jd)
    val sunLongitude = SunCalc.solarLongitude(T)
    return SeasonalMarkerCalc.seasonalMarker(sunLongitude)
}

/** Convenience: today's marker (null when no turning is in effect). */
fun turningMarkerForToday(
    clock: Clock = Clock.systemDefaultZone(),
    zone: ZoneId = ZoneId.systemDefault(),
): SeasonalMarker? {
    val nowMs = ZonedDateTime.of(LocalDate.now(clock).atStartOfDay(), zone)
        .toInstant().toEpochMilli()
    return turningMarkerForEpochMillis(nowMs)
}
