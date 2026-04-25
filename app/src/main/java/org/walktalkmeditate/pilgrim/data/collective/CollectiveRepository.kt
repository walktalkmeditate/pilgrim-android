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
            // Suspend-read DataStore (vs `optIn.value`) so a process
            // cold-start race — Eagerly stateIn collector hasn't
            // received its first DataStore emission yet — can't drop
            // a contribution from a previously opted-in user. The read
            // sits outside the mutex; an opt-in flip racing the read
            // is a no-op or a one-walk-late contribution, both
            // acceptable for a user-driven setting that changes rarely.
            if (!cacheStore.optInFlow.first()) return@launch
            val postOk = recordMutex.withLock {
                val newDelta = CollectiveCounterDelta(
                    walks = 1,
                    distanceKm = snapshot.distanceKm,
                    meditationMin = snapshot.meditationMin,
                    talkMin = snapshot.talkMin,
                )
                val merged = cacheStore.mutatePending { it + newDelta }
                if (merged.isEmpty()) {
                    // Defensive: backend rejects an all-zero payload
                    // (counter.ts:43-45). With newDelta.walks hardcoded
                    // to 1 this branch is structurally unreachable
                    // today, but the guard preserves correctness if
                    // recordWalk ever takes a parameterized walk count.
                    return@withLock false
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
                        // CollectiveCounterService.swift:114). The
                        // Mutex makes the iOS race (concurrent
                        // recordWalks adding to pending mid-POST)
                        // impossible on Android, so this is purely
                        // defensive — but keep it so a future refactor
                        // that loosens the lock doesn't silently drift
                        // pending negative.
                        cacheStore.mutatePending { current ->
                            val subtracted = current - merged
                            if (subtracted.walks <= 0) CollectiveCounterDelta()
                            else subtracted
                        }
                        true
                    }
                    PostResult.RateLimited -> {
                        Log.i(TAG, "POST rate-limited; pending preserved for next walk")
                        false
                    }
                    is PostResult.Failed -> {
                        Log.w(TAG, "POST failed; pending preserved for next walk", result.cause)
                        false
                    }
                }
            }
            // forceFetch outside the mutex so a slow GET (up to 10s
            // call timeout) doesn't block the next finishWalk's
            // recordWalk. fetchIfStale is internally TTL-gated so
            // concurrent callers are safe.
            if (postOk) forceFetch()
        }
    }

    private companion object {
        const val TAG = "CollectiveRepo"
    }
}
