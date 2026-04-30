// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import android.util.Log
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepoScope
import org.walktalkmeditate.pilgrim.ui.settings.data.WalksSource

/**
 * Stage 11-A backfill driver. Walks finalized BEFORE the cache columns
 * shipped (legacy rows seeded by `MIGRATION_4_5` with NULL aggregates) and
 * walks where [WalkFinalizationObserver] crashed before invoking the cache
 * never had their `distance_meters` / `meditation_seconds` populated. This
 * coordinator observes the walks Flow at app start, finds the first stale
 * row (`endTimestamp != null` AND either cache column is NULL), and drains
 * one walk at a time via [WalkMetricsCaching.computeAndPersist].
 *
 * One stale walk per emission keeps the backfill OFF the hot UI path: when
 * a finished walk's row updates (via [WalkDao.updateAggregates]), the Room
 * Flow re-emits, the next stale walk surfaces as the firstOrNull, the
 * coordinator drains it, and so on until the predicate finds nothing.
 *
 * Dedup invariants:
 * - [start] is idempotent via [AtomicBoolean.compareAndSet] — multiple
 *   calls launch only one collector.
 * - [distinctUntilChanged] dedupes Room re-emissions where the
 *   first-stale-walk-id didn't actually change (e.g. an unrelated row
 *   updated; the same id resurfaces).
 * - [inflight] guards the sub-frame race where the Room Flow emits the
 *   SAME id twice in flight while `computeAndPersist` is still suspended
 *   on Dispatchers.IO — distinctUntilChanged alone wouldn't catch that
 *   because the value technically didn't change.
 */
@Singleton
class WalkMetricsBackfillCoordinator @Inject constructor(
    private val walksSource: WalksSource,
    private val cache: WalkMetricsCaching,
    @CollectiveRepoScope private val scope: CoroutineScope,
) {

    private val inflight = Collections.synchronizedSet(HashSet<Long>())
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // The injected `@CollectiveRepoScope` already carries
        // Dispatchers.IO (see `CollectiveModule.provideCollectiveRepoScope`),
        // so launching without an explicit dispatcher inherits IO in
        // production AND lets `runTest`-supplied test scopes drive the
        // collector via the test scheduler (cleaner than `Dispatchers.IO`
        // which escapes the test dispatcher). Cache writes inside
        // `computeAndPersist` already hop to IO at the Room boundary.
        scope.launch {
            walksSource.observeAllWalks()
                .map { walks ->
                    walks.firstOrNull { walk ->
                        walk.endTimestamp != null &&
                            (walk.distanceMeters == null || walk.meditationSeconds == null)
                    }?.id
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { walkId ->
                    // [added] starts false so the finally branch only
                    // removes ids we actually inserted. If a
                    // CancellationException tears the collector down
                    // BETWEEN `inflight.add(walkId)` returning true and
                    // the `try { ... }` opening (a sub-instruction window
                    // the JVM doesn't atomically protect), the id would
                    // stay in [inflight] forever and future emissions
                    // would silently dedup-skip — breaking the
                    // convergence invariant. Folding the add into the
                    // try makes the cleanup unconditional: any path out
                    // of the body removes the id we added.
                    var added = false
                    try {
                        added = inflight.add(walkId)
                        if (!added) return@collect
                        cache.computeAndPersist(walkId)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "backfill failed walk=$walkId", t)
                    } finally {
                        if (added) inflight.remove(walkId)
                    }
                }
        }
    }

    private companion object {
        const val TAG = "WalkMetricsBackfill"
    }
}
