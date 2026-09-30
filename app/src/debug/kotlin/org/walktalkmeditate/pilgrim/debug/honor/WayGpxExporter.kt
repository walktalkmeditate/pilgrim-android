// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import org.walktalkmeditate.pilgrim.domain.honor.Way

/**
 * A Way as GPX for simulated playback: iOS's `WayGPXExporter`
 * (`Pilgrim/Models/Honor/WayGPXExporter.swift@7c200bf`, parity spec A §24),
 * text for text. Only `<wpt>` elements, one per route point in order, each
 * timed at `departedAt + t` so a player paces them as walked; moment ids
 * ride on the nearest route point's `<name>` so nothing hops the player
 * off the Way.
 *
 * iOS tuned this for Xcode's simulator. The Android emulator's GPX parser
 * reads root `<wpt>` elements and their `<time>` the same way (confirmed
 * from its source, not yet on an emulator run), but it sorts the points
 * by whole-second time with an unstable sort, so points that share a
 * second can play out of order.
 */
object WayGpxExporter {

    fun gpx(way: Way): String {
        val lastIndex = max(way.route.size - 1, 0)
        val names = mutableMapOf<Long, MutableList<String>>()
        for (moment in way.moments) {
            names.getOrPut(roundHalfAwayFromZero(moment.frac * lastIndex)) { mutableListOf() } += moment.id
        }
        val lines = mutableListOf(XML_DECLARATION, GPX_OPEN)
        way.route.forEachIndexed { index, point ->
            lines += "  <wpt lat=\"${swiftDescription(point.lat)}\" lon=\"${swiftDescription(point.lon)}\">"
            point.alt?.let { lines += "    <ele>${roundHalfAwayFromZero(it)}</ele>" }
            lines += "    <time>${timeText(way.departedAt, point.t)}</time>"
            names[index.toLong()]?.let { lines += "    <name>${it.joinToString(" ")}</name>" }
            lines += "  </wpt>"
        }
        lines += "</gpx>"
        return lines.joinToString("\n")
    }

    /** Swift's `rounded()` default, `.toNearestOrAwayFromZero`; Kotlin's `round` ties to even. */
    private fun roundHalfAwayFromZero(value: Double): Long =
        BigDecimal(value).setScale(0, RoundingMode.HALF_UP).toLong()

    /** `ISO8601DateFormatter` with `.withInternetDateTime` drops the fraction of a second. */
    private fun timeText(departedAt: Instant, t: Double): String {
        val at = departedAt.plusNanos(Math.round(t * NANOS_PER_SECOND))
        return DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS))
    }

    /**
     * Swift's `Double.description` for a coordinate. Both languages write
     * decimal digits for ordinary values, but Kotlin switches to `5.0E-4`
     * below 1e-3 in magnitude where Swift keeps `0.0005` down to 1e-4 and
     * then writes `1e-05`. A coordinate never reaches the large magnitudes
     * where Swift switches to an exponent again.
     */
    private fun swiftDescription(value: Double): String {
        if (value == 0.0) return if (1 / value < 0) "-0.0" else "0.0"
        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().abs().toString()
        val exponent = decimal.precision() - decimal.scale() - 1
        val sign = if (value < 0) "-" else ""
        val body = when {
            exponent < SWIFT_LOWEST_DECIMAL_EXPONENT -> {
                val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
                "${mantissa}e-${(-exponent).toString().padStart(2, '0')}"
            }
            exponent < 0 -> "0." + "0".repeat(-exponent - 1) + digits
            digits.length <= exponent + 1 -> digits + "0".repeat(exponent + 1 - digits.length) + ".0"
            else -> digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
        }
        return sign + body
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val GPX_OPEN =
        "<gpx version=\"1.1\" creator=\"Pilgrim\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
    private const val NANOS_PER_SECOND = 1_000_000_000.0

    /** The first digit's power of ten at 1e-4, the smallest Swift still writes in decimal. */
    private const val SWIFT_LOWEST_DECIMAL_EXPONENT = -4
}
