// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stage 8-B: orchestrator for collective-counter state.
 *
 * Opt-in is a true data-transmission gate: a user with opt-in OFF
 * never POSTs. First-time opt-in is forward-looking only; prior walks
 * are not backfilled.
 */
@Singleton
class CollectiveRepository @Inject constructor(
    private val cacheStore: CollectiveCacheStore,
    private val service: CollectiveCounterService,
    @CollectiveRepoScope private val scope: CoroutineScope,
) {
    private val recordMutex = Mutex()

    val stats: StateFlow<CollectiveStats?> =
        cacheStore.statsFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val optIn: StateFlow<Boolean> =
        cacheStore.optInFlow.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    suspend fun setOptIn(value: Boolean) = cacheStore.setOptIn(value)

    /**
     * GET aggregates if the in-memory TTL has expired. Failures are
     * logged + swallowed — cached stats keep rendering and we'll try
     * again on the next eligible call.
     */
    suspend fun fetchIfStale(now: () -> Long = System::currentTimeMillis) {
        val lastFetched = cacheStore.lastFetchedAtFlow.first()
        val nowMs = now()
        if (lastFetched != null &&
            (nowMs - lastFetched) < CollectiveConfig.FETCH_TTL.inWholeMilliseconds
        ) {
            return
        }
        try {
            val fresh = service.fetch()
            cacheStore.writeStats(fresh, nowMs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "fetchIfStale failed", t)
        }
    }

    /** Drop the TTL gate, fetch fresh. Used after a successful POST. */
    suspend fun forceFetch(now: () -> Long = System::currentTimeMillis) {
        cacheStore.invalidateLastFetched()
        fetchIfStale(now)
    }

    /**
     * Fire-and-forget on the repo's long-lived scope. Returns
     * immediately. Iff opted-in, accumulates the snapshot into pending
     * and POSTs the running total. On Success, subtracts the merged
     * total back to (typically) zero and triggers a forceFetch so the
     * UI sees the increment without waiting for the 216s TTL.
     */
    fun recordWalk(snapshot: CollectiveWalkSnapshot) {
        scope.launch {
            recordMutex.withLock {
                if (!optIn.value) return@withLock
                val newDelta = CollectiveCounterDelta(
                    walks = 1,
                    distanceKm = snapshot.distanceKm,
                    meditationMin = snapshot.meditationMin,
                    talkMin = snapshot.talkMin,
                )
                val merged = cacheStore.mutatePending { it + newDelta }
                if (merged.isEmpty()) {
                    // Defensive: backend rejects an all-zero payload
                    // (counter.ts:43-45). Never POST an empty delta.
                    return@withLock
                }
                val result = try {
                    service.post(merged)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    PostResult.Failed(t)
                }
                when (result) {
                    PostResult.Success -> {
                        // iOS clamps walks <= 0 → clear (parity with
                        // CollectiveCounterService.swift:114).
                        cacheStore.mutatePending { current ->
                            val subtracted = current - merged
                            if (subtracted.walks <= 0) CollectiveCounterDelta()
                            else subtracted
                        }
                        forceFetch()
                    }
                    PostResult.RateLimited -> {
                        Log.i(TAG, "POST rate-limited; pending preserved for next walk")
                    }
                    is PostResult.Failed -> {
                        Log.w(TAG, "POST failed; pending preserved for next walk", result.cause)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CollectiveRepo"
    }
}
