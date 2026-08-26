// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc

/**
 * One synodic-month window. `end` is always `lunation(index + 1).start`
 * — never `start + length` computed a second time — so
 * `lunation(n).end == lunation(n + 1).start` holds exactly; chaining off
 * a previous instance's `start` would accumulate ULP drift and could
 * split that equality. `fullMoon` is the window's midpoint, the instant
 * [LunationCalendar.moonName] reads the calendar month from.
 */
data class Lunation(val index: Int, val start: Instant, val end: Instant, val fullMoon: Instant)

/**
 * Synodic-month windows minted from [MoonCalc]'s epoch, ported from
 * `Pilgrim/Models/Threads/LunationCalendar.swift` (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`). Every boundary is
 * derived from the SAME two source-of-truth values
 * ([MoonCalc.EPOCH], [MoonCalc.SYNODIC_DAYS]) — never redeclared here —
 * so a future retune of either constant can't desynchronize phase math
 * from lunation math with no compiler error.
 */
object LunationCalendar {

    /** Length of one synodic month, in seconds — derived, never redeclared. */
    private val LUNATION_LENGTH_SECONDS: Double = MoonCalc.SYNODIC_DAYS * 86_400.0

    /** Traditional full-moon month names, January through December, 0-indexed
     * by (calendar month − 1). Verbatim, including the "Corn Moon" (not the
     * folk-almanac "Harvest Moon") and the apostrophe in "Hunter's Moon". */
    private val MONTH_MOON_NAMES = listOf(
        "Wolf Moon", "Snow Moon", "Worm Moon", "Pink Moon",
        "Flower Moon", "Strawberry Moon", "Buck Moon", "Sturgeon Moon",
        "Corn Moon", "Hunter's Moon", "Beaver Moon", "Cold Moon",
    )

    /**
     * The one minting expression every boundary in this object goes
     * through: `epoch + index × length`. Never chain `start + length` off
     * a previously-minted instant — that accumulates floating-point
     * drift and would split `lunation(n).end` from `lunation(n+1).start`.
     */
    private fun newMoonInstant(index: Int): Instant =
        MoonCalc.EPOCH.plusSecondsPrecise(index.toDouble() * LUNATION_LENGTH_SECONDS)

    fun lunation(index: Int): Lunation {
        val start = newMoonInstant(index)
        val end = newMoonInstant(index + 1)
        val fullMoon = start.plusSecondsPrecise(LUNATION_LENGTH_SECONDS / 2.0)
        return Lunation(index = index, start = start, end = end, fullMoon = fullMoon)
    }

    /**
     * The lunation containing [date]. Floor division can land one index
     * off at instants within epsilon of a boundary (Double round-off) —
     * BOTH correction guards below are required; each closes a different
     * direction of misclassification.
     */
    fun lunationContaining(date: Instant): Lunation {
        var index = floor(secondsBetween(MoonCalc.EPOCH, date) / LUNATION_LENGTH_SECONDS).toInt()
        if (!date.isBefore(newMoonInstant(index + 1))) index += 1
        if (date.isBefore(newMoonInstant(index))) index -= 1
        return lunation(index)
    }

    /**
     * The lunation that most recently closed — the only one
     * [DossierSensesTracks.moonLine] may ever report. The lunation
     * CONTAINING [asOf] (the open one) is never eligible; must go
     * through [lunationContaining] so the correction guards apply here
     * too, not re-derive "closed" some other way.
     */
    fun mostRecentClosed(asOf: Instant): Lunation = lunation(lunationContaining(asOf).index - 1)

    /**
     * The walker's local calendar month names the moon — the SAME set
     * moon can honestly carry different names in Lisbon and Auckland,
     * because the walker's sky is the one that counts. [zone] defaults to
     * the device's current zone at read time, never UTC and never cached.
     */
    fun moonName(lunation: Lunation, zone: ZoneId = ZoneId.systemDefault()): String =
        MONTH_MOON_NAMES[ZonedDateTime.ofInstant(lunation.fullMoon, zone).monthValue - 1]

    /** Fractional-second duration between two instants (Swift `timeIntervalSince`
     * semantics — a signed Double, never truncated to milliseconds). */
    private fun secondsBetween(start: Instant, end: Instant): Double {
        val duration = java.time.Duration.between(start, end)
        return duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
    }
}

/**
 * Adds a (possibly negative, possibly fractional) second offset to this
 * instant without precision loss — the sole legal way to mint a derived
 * instant from [MoonCalc.EPOCH] in this file (see [LunationCalendar]'s
 * "never start + length" invariant).
 */
private fun Instant.plusSecondsPrecise(seconds: Double): Instant {
    val wholeSeconds = floor(seconds).toLong()
    val fractionalNanos = kotlin.math.round((seconds - floor(seconds)) * 1_000_000_000.0).toLong()
    return this.plusSeconds(wholeSeconds).plusNanos(fractionalNanos)
}
