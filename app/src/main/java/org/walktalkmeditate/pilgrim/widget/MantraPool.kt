// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import java.time.LocalDate

/**
 * Daily-rotating walking mantras. iOS-parity (PilgrimHomeWidget.swift:84-96).
 * Indexed by day-of-year so the phrase changes at midnight; the system's
 * declarative `updatePeriodMillis="86400000"` widget descriptor refresh
 * triggers Glance to re-render and pick up the new phrase.
 *
 * Phrases are loaded from a single delimited string resource
 * (`R.string.widget_mantras`) split on `|`. One string keeps the
 * contemplative tone visible to translators as a unit (vs 10 separate
 * keys with no semantic context) and shrinks the resource footprint.
 */
object MantraPool {
    const val DELIMITER = "|"

    /**
     * Returns the phrase for the given date. Defensive: if the joined
     * string is empty or whitespace-only the result is an empty string,
     * which the composable can render as a no-op rather than crashing.
     */
    fun phraseFor(date: LocalDate, allMantrasJoined: String): String {
        val phrases = allMantrasJoined
            .split(DELIMITER)
            .filter { it.isNotBlank() }
        if (phrases.isEmpty()) return ""
        return phrases[(date.dayOfYear - 1).mod(phrases.size)]
    }
}
