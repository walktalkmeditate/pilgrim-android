// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import java.util.Locale
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Per-row stat previews shown beneath each toggle title in the Share
 * Walk modal. Mirrors iOS `WalkShareViewModel`'s `formatted*` computed
 * properties (`WalkShareViewModel.swift:86-122@v1.6.0`) verbatim:
 *
 *  - One decimal for distance (`%.1f km` / `%.1f mi`) — coarser than
 *    the summary's two-decimal [org.walktalkmeditate.pilgrim.ui.walk.WalkFormat]
 *    because the share card wants a glanceable headline, not precision.
 *  - Truncating integer math (`Int(x)`), not rounding, to match Swift.
 *  - `null` ⇒ the row renders its title only (no caption), exactly like
 *    iOS's `if let value` in `StatToggleRow`.
 *  - Grouped step count (`3,932`) via `%,d`, forced to `Locale.US`
 *    digits so non-Latin locales don't mix scripts (Stage 6-B lesson).
 */
internal object ShareStatFormat {

    private const val METERS_PER_MILE = 1_609.344
    private const val FEET_PER_METER = 3.28084

    fun distance(meters: Double, units: UnitSystem): String? {
        if (meters <= 0.0) return null
        return when (units) {
            UnitSystem.Metric -> String.format(Locale.US, "%.1f km", meters / 1_000.0)
            UnitSystem.Imperial -> String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
        }
    }

    fun duration(activeSeconds: Double): String? {
        if (activeSeconds <= 0.0) return null
        val total = activeSeconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    fun elevation(ascentMeters: Double, units: UnitSystem): String? {
        if (ascentMeters <= 1.0) return null
        return when (units) {
            UnitSystem.Metric -> "${ascentMeters.toInt()} m"
            UnitSystem.Imperial -> "${(ascentMeters * FEET_PER_METER).toInt()} ft"
        }
    }

    fun activityBreakdown(meditateSeconds: Double, talkSeconds: Double): String? {
        val parts = listOfNotNull(
            if (meditateSeconds > 0.0) "${(meditateSeconds / 60).toInt()}m meditation" else null,
            if (talkSeconds > 0.0) "${(talkSeconds / 60).toInt()}m reflection" else null,
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun steps(count: Int?): String? {
        if (count == null || count <= 0) return null
        return String.format(Locale.US, "%,d", count)
    }
}
