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

    /**
     * Single-flight gate on `fetchIfStale` so a slow boot fetch and
     * a post-finishWalk forceFetch can't race each other and have
     * the slower (and thus stale-r) response overwrite the fresher
     * one. Inside the lock we re-check the TTL — if a newer fetch
     * already landed while this caller was queued, the second
     * fetcher's TTL gate skips.
     */
    private val fetchMutex = Mutex()

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
        fetchInternal(now, bypassTtl = false)
    }

    /** Drop the TTL gate, fetch fresh. Used after a successful POST. */
    suspend fun forceFetch(now: () -> Long = System::currentTimeMillis) {
        // The TTL bypass MUST happen inside fetchMutex — otherwise a
        // slow in-flight fetch can `writeStats(fresh, nowMs)` AFTER
        // our invalidate but BEFORE we acquire the lock, causing our
        // re-check to see a freshly-written lastFetchedAt and skip
        // the bypass intent. The user's contribution would land on
        // the server but the local UI would stay stale until the
        // next 216s TTL expiry. Threading the bypass flag through
        // the locked path solves it atomically.
        fetchInternal(now, bypassTtl = true)
    }

    private suspend fun fetchInternal(now: () -> Long, bypassTtl: Boolean) {
        fetchMutex.withLock {
            val nowMs = now()
            if (!bypassTtl) {
                // Re-read inside the lock — a queued caller may find
                // that a parallel fetch already wrote fresher stats
                // while it was waiting, in which case the TTL gate
                // now skips.
                val lastFetched = cacheStore.lastFetchedAtFlow.first()
                if (lastFetched != null &&
                    (nowMs - lastFetched) < CollectiveConfig.FETCH_TTL.inWholeMilliseconds
                ) {
                    return@withLock
                }
            }
            try {
                val fresh = service.fetch()
                cacheStore.writeStats(fresh, nowMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "fetch failed", t)
            }
        }
    }

    /**
     * Fire-and-forget on the repo's long-lived scope. Returns
     * immediately. Iff opted-in, accumulates the snapshot into pending
     * and POSTs the running total. On Success, subtracts the merged
     * total back to (typically) zero and triggers a forceFetch so the
     * UI sees the increment without waiting for the 216s TTL.
     *
     * Known iOS-parity limitation: process death between POST send
     * and response read leaves pending non-zero. On next launch the
     * NEXT walk's POST sends pending+1, double-counting that one
     * walk on the backend. Server-side request_id dedup is the
     * proper fix (separate ticket); for now we accept the rare
     * double-count to match iOS's behavior. Do NOT add a "clear
     * pending before POST" optimization without server-side dedup —
     * that would silently lose contributions on transient network
     * failures.
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
                // Clamp against the backend's per-POST caps
                // (counter.ts:38-41 silently clamps + returns OK).
                // POST the clamped delta; subtract the SAME clamped
                // value from pending on Success so any overflow stays
                // in pending for the next walk. This unblocks heavy
                // walkers from silently losing contributions when
                // pending >10 walks accumulates (e.g., extended
                // offline period followed by reconnect).
                val payload = merged.clampToBackendCaps()
                val result = try {
                    service.post(payload)
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
                            val subtracted = current - payload
                            // Clear ONLY when every field has fully drained.
                            // The clamp path can leave walks==0 with
                            // residual distance/meditation/talk (e.g.,
                            // 9 walks × 30km posted as walks=9,
                            // distance=200km capped → subtract leaves
                            // walks=0, distance=70km). iOS's predicate
                            // (walks<=0) silently drops that residual;
                            // we keep it so the next walk's POST
                            // delivers it.
                            if (subtracted.isEmpty()) CollectiveCounterDelta()
                            // Also defend against any field going negative
                            // (only reachable if a future refactor loosens
                            // the mutex and concurrent recordWalks
                            // double-subtract). Coerce to zero floor.
                            else CollectiveCounterDelta(
                                walks = subtracted.walks.coerceAtLeast(0),
                                distanceKm = subtracted.distanceKm.coerceAtLeast(0.0),
                                meditationMin = subtracted.meditationMin.coerceAtLeast(0),
                                talkMin = subtracted.talkMin.coerceAtLeast(0),
                            )
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
