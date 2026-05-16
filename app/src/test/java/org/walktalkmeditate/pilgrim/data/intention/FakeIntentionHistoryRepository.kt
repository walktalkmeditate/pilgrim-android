// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.intention

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.walktalkmeditate.pilgrim.data.intention.IntentionHistoryRepository.Companion.MAX_INTENTIONS

/**
 * In-memory [IntentionHistoryRepository] for tests. Same trim /
 * dedupe-to-front / prepend / cap-5 / blank-guard semantics as
 * [DataStoreIntentionHistoryRepository] so callers behave realistically.
 */
class FakeIntentionHistoryRepository(
    initial: List<String> = emptyList(),
) : IntentionHistoryRepository {

    private val _intentions = MutableStateFlow(initial)
    override val intentions: StateFlow<List<String>> = _intentions.asStateFlow()

    override suspend fun add(intention: String) {
        val trimmed = intention.trim()
        if (trimmed.isEmpty()) return
        _intentions.update { current ->
            (listOf(trimmed) + current.filterNot { it == trimmed }).take(MAX_INTENTIONS)
        }
    }

    override suspend fun clear() {
        _intentions.update { emptyList() }
    }
}
