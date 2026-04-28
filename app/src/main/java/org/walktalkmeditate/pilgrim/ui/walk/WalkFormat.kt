// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Formatters for walk-screen numeric displays. Deliberately spartan.
 *
 * Stage 10-C: distance / pace / altitude / temperature formatters take
 * an explicit [UnitSystem] parameter sourced from
 * [org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository].
 * Storage stays metric (Walk.distanceMeters, AltitudeSample.altitudeMeters,
 * paceSecondsPerKm); the conversion happens at format time only. Required
 * (not defaulted) so a missing caller surfaces as a compile error rather
 * than a silent default to Metric.
 */
object WalkFormat {

    /** km → mi. Standard rounding (matches iOS `MeasurementFormatter`). */
    private const val KM_PER_MI = 0.621371

    /** m → ft. */
    private const val FT_PER_M = 3.28084

    /** Display threshold below which Imperial distance falls back to feet. */
    private const val IMPERIAL_FOOT_THRESHOLD_MI = 0.1

    /** `H:MM:SS` for walks over an hour, `MM:SS` otherwise. */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Distance formatted in the user's preferred unit system.
     *
     * Metric: `0.00 km` for ≥100 m, `N m` otherwise.
     * Imperial: `0.00 mi` for ≥0.1 mi, `N ft` otherwise.
     *
     * `units` defaults to [UnitSystem.Metric] so legacy callers stay
     * green during the Stage 10-C caller migration. The default goes
     * away in a follow-up once every caller passes a value explicitly.
     */
    fun distance(meters: Double, units: UnitSystem = UnitSystem.Metric): String {
        val label = distanceLabel(meters, units)
        return "${label.value} ${label.unit}"
    }

    /**
     * Same formatting rules as [distance] but returns the numeric value
     * and unit in separate fields. Callers that render them in a
     * composed layout (e.g., the goshuin seal's center-text, where the
     * numeral is larger than the unit) avoid parsing a pre-formatted
     * string.
     */
    fun distanceLabel(meters: Double, units: UnitSystem = UnitSystem.Metric): DistanceLabel = when (units) {
        UnitSystem.Metric -> metricDistanceLabel(meters)
        UnitSystem.Imperial -> imperialDistanceLabel(meters)
    }

    private fun metricDistanceLabel(meters: Double): DistanceLabel {
        val km = meters / 1_000.0
        return if (meters >= 100.0) {
            DistanceLabel(value = String.format(Locale.US, "%.2f", km), unit = "km")
        } else {
            DistanceLabel(value = String.format(Locale.US, "%d", meters.roundToInt()), unit = "m")
        }
    }

    private fun imperialDistanceLabel(meters: Double): DistanceLabel {
        val km = meters / 1_000.0
        val mi = km * KM_PER_MI
        return if (mi >= IMPERIAL_FOOT_THRESHOLD_MI) {
            DistanceLabel(value = String.format(Locale.US, "%.2f", mi), unit = "mi")
        } else {
            val feet = (meters * FT_PER_M).roundToInt()
            DistanceLabel(value = String.format(Locale.US, "%d", feet), unit = "ft")
        }
    }

    /**
     * Pace formatted in the user's preferred unit system.
     *
     * Metric: `M:SS /km`.
     * Imperial: `M:SS /mi` (converted from secondsPerKm via `KM_PER_MI`).
     * `—` when pace is undefined (very short walks).
     */
    fun pace(secondsPerKm: Double?, units: UnitSystem = UnitSystem.Metric): String {
        if (secondsPerKm == null) return "—"
        val (totalSec, suffix) = when (units) {
            UnitSystem.Metric -> secondsPerKm.roundToInt().coerceAtLeast(0) to "/km"
            UnitSystem.Imperial -> {
                val secondsPerMi = secondsPerKm / KM_PER_MI
                secondsPerMi.roundToInt().coerceAtLeast(0) to "/mi"
            }
        }
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d:%02d %s", minutes, seconds, suffix)
    }

    /**
     * Altitude (or elevation gain / ascent) formatted in the user's
     * preferred unit system. Metric: `{N} m`. Imperial: `{N} ft`.
     * Both round to integer.
     */
    fun altitude(meters: Double, units: UnitSystem): String = when (units) {
        UnitSystem.Metric -> String.format(Locale.US, "%d m", meters.roundToInt())
        UnitSystem.Imperial -> String.format(
            Locale.US,
            "%d ft",
            (meters * FT_PER_M).roundToInt(),
        )
    }

    /**
     * Temperature formatted in the user's preferred unit system.
     * Metric: `{N}°C`. Imperial: `{N}°F` via `f = c * 9/5 + 32`.
     * Both round to integer.
     */
    fun temperature(celsius: Double, units: UnitSystem): String = when (units) {
        UnitSystem.Metric -> String.format(Locale.US, "%d°C", celsius.roundToInt())
        UnitSystem.Imperial -> {
            val f = celsius * 9.0 / 5.0 + 32.0
            String.format(Locale.US, "%d°F", f.roundToInt())
        }
    }

    /**
     * Compact duration for the time-chip pills. Returns "—" for ≤0,
     * "M:SS" below one hour, and "H:MM" at one hour or more so the
     * chip text fits the narrow pill width even on long walks.
     */
    fun shortDuration(millis: Long): String {
        if (millis <= 0) return "—"
        val totalSeconds = millis / 1_000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d", hours, minutes)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}

/**
 * Split representation of a formatted distance. Returned by
 * [WalkFormat.distanceLabel]. The goshuin seal's center-text layer
 * renders [value] with a large Cormorant Garamond face and [unit]
 * with a smaller Lato face beneath, so it needs the two fields
 * separately rather than a single pre-formatted string.
 */
data class DistanceLabel(
    val value: String,
    val unit: String,
)
