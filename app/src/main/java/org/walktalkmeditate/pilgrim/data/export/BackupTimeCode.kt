// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `yyyyMMdd-HHmmss` time code used in backup filenames. Mirrors iOS
 * `CustomDateFormatting.backupTimeCode(forDate:)` so a `.pilgrim` or
 * recordings ZIP file produced on Android sorts side-by-side with
 * iOS exports in a Files browser.
 *
 * Pinned to `Locale.ROOT` so non-Gregorian / non-ASCII locales never
 * leak into a filename byte sequence.
 */
object BackupTimeCode {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        FORMATTER.format(instant.atZone(zone))
}
