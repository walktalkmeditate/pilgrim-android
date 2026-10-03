// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.content.res.Resources
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours

/** The stage surfaces' joiner, U+00B7 with a space each side. */
internal const val STAGE_SEPARATOR = " · "

/**
 * iOS `StatsHelper.string(for:unit:type:)` as the pilgrimage-stage surfaces
 * print it (pilgrimage-stage spec P4 §5.3, owner decision 7): a
 * `MeasurementFormatter` rounding to 0.01 with no trailing zeros, thousands
 * grouped, in the walker's unit. "764 km", "24.2 km", "1,419 m"; imperial
 * "474.73 mi", "4,655.51 ft", hundredths of a foot as iOS ships them
 * (pilgrim-ios #122 item 7, matched). Used on this stage's surfaces only;
 * Stage 21-1's keep `WalkFormat` (P4 A-9).
 *
 * The value's shortest decimal form is rounded half-even, as Foundation
 * rounds it: 24,235 m is "24.24 km", 1,005 m "1 km". Digits and grouping
 * are `Locale.US`'s, where iOS follows the phone's locale (P4 A-5).
 */
object StageFormat {

    /** Distances are kilometres or miles, never metres: 50 m is "0.05 km". */
    fun distance(meters: Double, units: UnitSystem): String = when (units) {
        UnitSystem.Metric -> measured(meters / METERS_PER_KILOMETER, "km")
        UnitSystem.Imperial -> measured(meters / METERS_PER_MILE, "mi")
    }

    /** Climbs follow the one unit setting, as iOS's altitude preference moves with its distance one. */
    fun altitude(meters: Double, units: UnitSystem): String = when (units) {
        UnitSystem.Metric -> measured(meters, "m")
        UnitSystem.Imperial -> measured(meters / METERS_PER_FOOT, "ft")
    }

    private fun measured(value: Double, symbol: String): String = "${number(value)} $symbol"

    /** Foundation spells a non-finite figure "NaN" and "∞" rather than failing. */
    private fun number(value: Double): String = when {
        value.isNaN() -> "NaN"
        value.isInfinite() -> if (value > 0) "∞" else "-∞"
        else -> DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
            .format(BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_EVEN))
    }

    /** Foundation's metre, mile and foot: the mile exactly 1,609.344 m, as `CollectiveRoute` pins it. */
    private const val METERS_PER_KILOMETER = 1_000.0
    private const val METERS_PER_MILE = 1_609.344
    private const val METERS_PER_FOOT = 0.3048
}

/**
 * iOS `WayStageFacts` (`PilgrimageRouteView.swift:6-33@7c200bf`, P4 §5.1):
 * "24.2 km · 1,419 m up · 7 to 9 hours · hard", the one facts line the
 * route page's stage rows and the morning card both print. Two copies
 * drifted apart once on iOS.
 */
object WayStageFacts {

    /** An empty difficulty adds no part and no trailing separator (Shikoku's stages are all `""`). */
    fun line(
        resources: Resources,
        distanceKm: Double,
        gainMeters: Double,
        hours: WayStageHours,
        difficulty: String,
        units: UnitSystem,
    ): String {
        val parts = mutableListOf(
            StageFormat.distance(distanceKm * 1000, units),
            resources.getString(R.string.pilgrimage_facts_gain, StageFormat.altitude(gainMeters, units)),
            hoursText(resources, hours),
        )
        if (difficulty.isNotEmpty()) parts += difficulty
        return parts.joinToString(STAGE_SEPARATOR)
    }

    /** Equal bounds after rounding read once, and never in the singular: "1 hours" (pilgrim-ios #122 item 7, matched). */
    private fun hoursText(resources: Resources, hours: WayStageHours): String {
        val low = boundedHours(hours.min)
        val high = boundedHours(hours.max)
        return if (low == high) {
            resources.getString(R.string.pilgrimage_facts_hours, digits(low))
        } else {
            resources.getString(R.string.pilgrimage_facts_hours_range, digits(low), digits(high))
        }
    }

    /**
     * A NaN takes the floor and an infinity the ceiling, before Swift's
     * `Int(_:)` could trap; the clamp leaves only non-negative values, where
     * [roundToInt] agrees with Swift's half-away-from-zero `rounded()`.
     */
    private fun boundedHours(value: Double): Int = if (value.isNaN()) 0 else value.coerceIn(0.0, MAX_HOURS).roundToInt()

    private const val MAX_HOURS = 100.0
}

/** A count as iOS interpolates an `Int`: plain ASCII digits, no grouping. */
internal fun digits(count: Int): String = String.format(Locale.US, "%d", count)
