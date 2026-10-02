// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.text.BreakIterator
import java.util.Locale

/*
 * The two Swift string operations Honor's iOS code applies to sharer and
 * walker text, so Android cuts and trims the same characters (shared-walk
 * spec S1 §7.1–§7.2).
 */

/**
 * Swift's `trimmingCharacters(in: .whitespacesAndNewlines)`. Kotlin's
 * `trim()` differs in six code points: it also trims U+001C–U+001F, and
 * it keeps U+0085 and U+200B, which Swift trims (S1 §7.2, probed).
 */
internal fun String.trimmingWhitespacesAndNewlines(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isSwiftWhitespaceOrNewline()) start++
    while (end > start && this[end - 1].isSwiftWhitespaceOrNewline()) end--
    return substring(start, end)
}

/**
 * Foundation's `whitespacesAndNewlines`: the space separators (Zs), the
 * line and paragraph separators, U+0009–U+000D, U+0085, and U+200B.
 */
private fun Char.isSwiftWhitespaceOrNewline(): Boolean = when (code) {
    in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
    in 0x2000..0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    -> true
    else -> false
}

/** Characters as Swift counts them: grapheme clusters, not UTF-16 units. */
internal fun String.characterCount(): Int {
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
    characters.setText(this)
    var count = 0
    while (characters.next() != BreakIterator.DONE) count++
    return count
}

/** Swift's `String.prefix(_:)`: at most [maxCharacters] grapheme clusters. */
internal fun String.prefixCharacters(maxCharacters: Int): String {
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
    characters.setText(this)
    var end = 0
    repeat(maxCharacters) {
        val next = characters.next()
        if (next == BreakIterator.DONE) return this
        end = next
    }
    return substring(0, end)
}
