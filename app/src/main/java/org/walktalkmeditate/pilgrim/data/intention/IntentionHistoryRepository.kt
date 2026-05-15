// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.intention

import kotlinx.coroutines.flow.StateFlow

/**
 * Recent walk-intentions, most-recent-first. Port of iOS
 * `IntentionHistoryStore` (UserDefaults key `"IntentionHistory"`,
 * JSON `[String]`, cap 5). [add] trims, de-dupes (an existing equal
 * entry moves to the front), prepends, and caps at [MAX_INTENTIONS];
 * blank input is ignored.
 *
 * Storage key matches iOS verbatim so a `.pilgrim` ZIP round-trips.
 */
interface IntentionHistoryRepository {
    val intentions: StateFlow<List<String>>
    suspend fun add(intention: String)
    suspend fun clear()

    companion object {
        const val MAX_INTENTIONS = 5
    }
}
