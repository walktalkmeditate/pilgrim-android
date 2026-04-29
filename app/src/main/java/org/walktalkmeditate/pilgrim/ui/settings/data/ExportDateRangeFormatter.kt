// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats earliest+latest walk dates as "March 2024 – April 2026" or
 * "April 2026" when both fall in the same month. Mirrors iOS
 * `ExportDateRangeFormatter` for the export confirmation sheet.
 *
 * `Locale.getDefault()` for user-facing month names; the en-dash
 * separator is hard-coded ("–", U+2013) to match iOS verbatim.
 */
object ExportDateRangeFormatter {

    fun format(
        earliest: Instant,
        latest: Instant,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val earliestText = formatter.format(earliest.atZone(zone))
        val latestText = formatter.format(latest.atZone(zone))
        return if (earliestText == latestText) earliestText else "$earliestText – $latestText"
    }
}
