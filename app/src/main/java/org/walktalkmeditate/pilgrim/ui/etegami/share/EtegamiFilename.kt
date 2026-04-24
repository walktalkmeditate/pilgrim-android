// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stage 7-D: filename convention for shared/saved etegami PNGs.
 *
 * Byte-identical to iOS's `WalkSharingButtons.writeToTemp`, which
 * formats `walk.startDate` via the iOS `DateFormatter` pattern
 * `yyyy-MM-dd-HHmm` (24-hour). Cross-platform users recognize the
 * filename immediately.
 *
 * `Locale.ROOT` forces ASCII digits — important on Arabic / Persian /
 * Hindi locales where default decimal digits break filename parsing
 * and round-trip sharing (Stage 6-B lesson).
 */
internal object EtegamiFilename {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

    /** Returns `pilgrim-etegami-<yyyy-MM-dd-HHmm>.png`. */
    fun forWalk(startedAtEpochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val stamp = FORMATTER.format(Instant.ofEpochMilli(startedAtEpochMs).atZone(zoneId))
        return "pilgrim-etegami-$stamp.png"
    }
}
