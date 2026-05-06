// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class DateDivider(
    val idTag: Int,
    val text: String,
    val xPx: Float,
    val yPx: Float,
)

/**
 * Pure helper extracting the inline `showMonth + monthText + monthXPx`
 * logic in `HomeScreen.kt:355-380`. UI behavior unchanged.
 *
 * Iterates snapshots; tracks the previous YearMonth; emits a divider
 * on every transition (and unconditionally for index 0). xPx flips
 * to the opposite side of the dot meander, matching iOS.
 */
fun computeDateDividers(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
    viewportWidthPx: Float,
    monthMarginPx: Float,
    locale: Locale,
    zone: ZoneId,
): List<DateDivider> {
    if (snapshots.isEmpty() || dotPositions.isEmpty()) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("MMM", locale).withZone(zone)
    val out = mutableListOf<DateDivider>()
    var lastYearMonth: YearMonth? = null
    val n = kotlin.math.min(snapshots.size, dotPositions.size)
    for (i in 0 until n) {
        val ms = snapshots[i].startMs
        val ym = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone))
        val emit = (i == 0) || (ym != lastYearMonth)
        if (emit) {
            val pos = dotPositions[i]
            val xPx = if (pos.centerXPx > viewportWidthPx / 2f) {
                monthMarginPx
            } else {
                viewportWidthPx - monthMarginPx
            }
            out += DateDivider(
                idTag = i,
                text = formatter.format(Instant.ofEpochMilli(ms)),
                xPx = xPx,
                yPx = pos.yPx,
            )
        }
        lastYearMonth = ym
    }
    return out
}
