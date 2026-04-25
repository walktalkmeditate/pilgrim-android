// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: expiry options for a shared walk page. Values mirror iOS
 * `WalkShareViewModel.ExpiryOption` verbatim — same days, labels,
 * kanji glyphs, and cache keys — so a future `.pilgrim` export/import
 * between platforms sees identical semantics.
 */
enum class ExpiryOption(
    val days: Int,
    val label: String,
    val kanji: String,
    val cacheKey: String,
) {
    Moon(days = 30, label = "1 moon", kanji = "月", cacheKey = "moon"),
    Season(days = 90, label = "1 season", kanji = "季", cacheKey = "season"),
    Cycle(days = 365, label = "1 cycle", kanji = "巡", cacheKey = "cycle"),
    ;

    companion object {
        fun fromCacheKey(key: String?): ExpiryOption? =
            entries.firstOrNull { it.cacheKey == key }
    }
}
