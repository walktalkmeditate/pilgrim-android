// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.util.Locale
import kotlin.math.roundToInt

/** Formatters for walk-screen numeric displays. Deliberately spartan. */
object WalkFormat {

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

    /** `0.00 km` for walks over 100m, `N m` otherwise. */
    fun distance(meters: Double): String {
        val km = meters / 1_000.0
        return if (meters >= 100.0) {
            String.format(Locale.US, "%.2f km", km)
        } else {
            String.format(Locale.US, "%d m", meters.roundToInt())
        }
    }

    /**
     * Same formatting rules as [distance] but returns the numeric value
     * and unit in separate fields. Callers that render them in a
     * composed layout (e.g., the goshuin seal's center-text, where the
     * numeral is larger than the unit) avoid parsing a pre-formatted
     * string.
     */
    fun distanceLabel(meters: Double): DistanceLabel {
        val km = meters / 1_000.0
        return if (meters >= 100.0) {
            DistanceLabel(value = String.format(Locale.US, "%.2f", km), unit = "km")
        } else {
            DistanceLabel(value = String.format(Locale.US, "%d", meters.roundToInt()), unit = "m")
        }
    }

    /** `M:SS /km` pace, or `—` when pace is undefined (very short walks). */
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null) return "—"
        val totalSec = secondsPerKm.roundToInt().coerceAtLeast(0)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%d:%02d /km", minutes, seconds)
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
