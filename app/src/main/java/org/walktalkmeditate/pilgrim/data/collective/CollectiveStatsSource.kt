// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only StateFlow facet of [CollectiveRepository]. Exposed so
 * consumers (e.g., `WalkViewModel`) can depend on a tiny interface
 * instead of the full repository — keeps tests trivial: a fake just
 * returns `MutableStateFlow(null)` instead of standing up the entire
 * cache + service + scope graph.
 */
interface CollectiveStatsSource {
    val stats: StateFlow<CollectiveStats?>

    companion object {
        /**
         * Test/preview helper — returns a [CollectiveStatsSource]
         * whose [stats] flow holds the supplied [initial] value
         * (default null). Avoids standing up the full
         * [CollectiveRepository] graph in unit tests.
         */
        fun of(initial: CollectiveStats? = null): CollectiveStatsSource = object : CollectiveStatsSource {
            override val stats: StateFlow<CollectiveStats?> =
                kotlinx.coroutines.flow.MutableStateFlow(initial)
        }
    }
}

/**
 * Binds the production [CollectiveRepository] as a
 * [CollectiveStatsSource]. The repository is `@Singleton`, so the
 * binding here is a no-op forwarder rather than an additional
 * lifecycle holder.
 */
class CollectiveRepositoryStatsSource @Inject constructor(
    private val repository: CollectiveRepository,
) : CollectiveStatsSource {
    override val stats: StateFlow<CollectiveStats?> = repository.stats
}
