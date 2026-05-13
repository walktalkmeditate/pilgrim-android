// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

/**
 * iOS parity `KanjiExpiryPicker.swift:7@db4196e` — three durations the
 * user chooses from when placing a whisper. `apiValue` is the verbatim
 * string the server expects ("1d" / "7d" / "1m"). `kanji` renders as a
 * watermark in the picker pill; `days` is consumer-side metadata if a
 * future "expires in N days" UI ever surfaces.
 *
 * Default selection in the placement sheet: [SevenDays].
 */
enum class ExpiryDuration(val apiValue: String, val days: Int, val kanji: String) {
    OneDay("1d", 1, "日"),
    SevenDays("7d", 7, "週"),
    OneMonth("1m", 30, "月");

    companion object {
        val DEFAULT: ExpiryDuration = SevenDays
    }
}
