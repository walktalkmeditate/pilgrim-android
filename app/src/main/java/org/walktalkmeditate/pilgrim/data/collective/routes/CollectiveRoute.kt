// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * One entry in the shared collective-route artifact: either a real pilgrimage
 * route or a cosmic horizon. Ports iOS `CollectiveRoute.swift@9a418e4`; parity
 * spec `docs/parity/2026-07-23-port-route-catalog-u2.md`.
 *
 * [km] is metric throughout — the artifact ships kilometres, converted to the
 * pilgrim's unit at render time. [companyLine] is a complete, unit-free
 * sentence naming who has walked this entry, baked upstream so a curator can
 * edit it without an app release.
 */
data class CollectiveRoute(
    val id: String,
    val kind: Kind,
    val km: Double,
    val companyLine: String,
    val bestMonths: List<Int> = emptyList(),
    val peakMonths: List<Int> = emptyList(),
) {

    /**
     * A horizon has no name a pilgrim would recognise, only a preposition and
     * an object: "around the Earth", "to the Sun".
     */
    sealed interface Kind {
        data class Route(val nameEn: String) : Kind
        data class Cosmic(val preposition: String, val body: String) : Kind
    }

    val isCosmic: Boolean get() = kind is Kind.Cosmic

    /**
     * How many slots this entry takes in the day's selection pool.
     *
     * Peak is an intensifier on being in season, never a boost of its own: a
     * route whose peak months fall outside its best months stays at base
     * weight.
     */
    fun weight(inMonth: Int): Int {
        if (isCosmic) return BASE_WEIGHT
        if (inMonth !in bestMonths) return BASE_WEIGHT

        val inSeasonWeight = BASE_WEIGHT + IN_SEASON_BONUS
        if (inMonth !in peakMonths) return inSeasonWeight
        return inSeasonWeight + PEAK_BONUS
    }

    /**
     * The Settings phrasing: the collective's total measured against this
     * entry. Null when the total is merely unknown (no counter fetch has
     * landed), because the beginning-of-path line would claim the collective
     * has walked nothing while it is hundreds of kilometres in. A genuinely
     * zero total does get it.
     */
    fun dailyLine(collectiveKm: Double?, units: UnitSystem): String? {
        if (collectiveKm == null) return null
        // Deliberate iOS divergence from the web, ported as shipped: the web
        // guards only `> 0` and prints "Infinity times".
        if (!(collectiveKm > 0.0 && collectiveKm.isFinite())) return BEGINNING_LINE

        val times = collectiveKm / km
        return when (kind) {
            is Kind.Route -> routeLine(times, kind.nameEn)
            is Kind.Cosmic -> horizonLine(
                times = times,
                remainingKm = km - collectiveKm,
                preposition = kind.preposition,
                body = kind.body,
                units = units,
            )
        }
    }

    /**
     * The walk-summary phrasing: this walk's distance against the day's entry,
     * then the entry's own sentence about who has walked it. Needs no
     * collective total, so it renders on a fresh offline install.
     */
    fun contributionLine(walkKm: Double, units: UnitSystem): String {
        val walk = formatted(walkKm, Rounding.ONE_DIGIT, units)
        return when (kind) {
            is Kind.Route -> "Your $walk against the ${kind.nameEn}. $companyLine"
            is Kind.Cosmic -> {
                // Nameless, so its magnitude carries the contrast instead.
                val magnitude = formatted(km, Rounding.WHOLE_NUMBERS, units)
                "Your $walk against $magnitude ${kind.preposition} ${kind.body}. $companyLine"
            }
        }
    }

    private fun routeLine(times: Double, nameEn: String): String {
        val completed = wholeCompletions(times)
        if (completed >= 2) return "Together, we've walked the $nameEn $completed times."
        if (completed == 1L) return "Together, one $nameEn complete."

        val rawPercent = times * 100
        val roundedPercent = rawPercent.roundToInt()
        // Reading 100% before the route is actually complete would be a lie.
        val percent = min(99, roundedPercent)
        return "We are $percent% of the way to one $nameEn."
    }

    private fun horizonLine(
        times: Double,
        remainingKm: Double,
        preposition: String,
        body: String,
        units: UnitSystem,
    ): String {
        if (times >= 1) {
            val completed = wholeCompletions(times)
            if (completed >= 2) return "Together, $completed times $preposition $body."
            return "Together, once $preposition $body."
        }

        val percent = times * 100
        if (percent >= HORIZON_PERCENT_FLOOR) {
            val formattedPercent = decimalFormat("0.0").format(percent)
            return "We are $formattedPercent% of the way $preposition $body."
        }

        // The one branch of the daily line that states a raw distance, so the
        // one that must honour the pilgrim's unit.
        val remaining = formatted(remainingKm, Rounding.WHOLE_NUMBERS, units)
        return "$remaining $preposition $body."
    }

    /** Mirrors iOS `CustomMeasurementFormatting.FormattingRoundingType`'s two used cases. */
    private enum class Rounding { WHOLE_NUMBERS, ONE_DIGIT }

    companion object {
        const val BASE_WEIGHT = 1
        const val IN_SEASON_BONUS = 2
        const val PEAK_BONUS = 3

        const val BEGINNING_LINE = "The path is beginning."

        /**
         * Below this a horizon's percentage rounds to something meaningless,
         * so the remaining distance is stated instead.
         */
        private const val HORIZON_PERCENT_FLOOR = 1.0

        /**
         * A nonsense total from a bad API response should misprint, not
         * overflow the Long conversion (iOS: "misprint, not crash").
         */
        private const val COMPLETIONS_CEILING = 1_000_000_000_000.0

        /**
         * Exact statute-mile definition, matching iOS `MeasurementFormatter`
         * conversion. NOT `WalkFormat.KM_PER_MI` (0.621371): the reciprocal's
         * rounding error is user-visible at horizon magnitudes — 383,705.5 km
         * renders 238,423 mi under the reciprocal vs iOS's pinned 238,424 mi
         * (parity spec B5/D4).
         */
        private const val KM_PER_MILE = 1.609344

        private fun wholeCompletions(times: Double): Long =
            min(floor(times), COMPLETIONS_CEILING).toLong()

        private fun formatted(km: Double, rounding: Rounding, units: UnitSystem): String {
            val value = when (units) {
                UnitSystem.Metric -> km
                UnitSystem.Imperial -> km / KM_PER_MILE
            }
            val symbol = when (units) {
                UnitSystem.Metric -> "km"
                UnitSystem.Imperial -> "mi"
            }
            val pattern = when (rounding) {
                Rounding.WHOLE_NUMBERS -> "#,##0"
                Rounding.ONE_DIGIT -> "#,##0.#"
            }
            return "${decimalFormat(pattern).format(value)} $symbol"
        }

        /**
         * Per-call instance: `DecimalFormat` is not thread-safe, and phrasing
         * runs a handful of times per screen. HALF_EVEN default matches
         * `NumberFormatter`'s; `Locale.US` symbols match the pinned en-US
         * grouping ("383,706 km").
         */
        private fun decimalFormat(pattern: String): DecimalFormat =
            DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
    }
}
