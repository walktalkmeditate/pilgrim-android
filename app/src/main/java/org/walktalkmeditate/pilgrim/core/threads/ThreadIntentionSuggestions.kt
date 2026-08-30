// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao

/**
 * "Recurring" intention chips: ported from
 * `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift` at the pinned
 * iOS commit (parity spec `docs/parity/2026-08-25-threads-engine-port.md`,
 * BEH-49..52/UI-28..31/EDG-79..81). Offers up to [MAX_SUGGESTIONS] phrases
 * for lemmas the walker has returned to across at least
 * [MINIMUM_DISTINCT_WALKS] distinct walks within the trailing
 * [ThreadStore.RECURRENCE_WINDOW].
 */
@Singleton
class ThreadIntentionSuggestions @Inject constructor(
    private val store: TranscriptContextStore,
    private val preferences: ThreadsPreferencesRepository,
    private val voiceRecordingDao: VoiceRecordingDao,
) {

    private data class Memo(val changeCount: Long, val day: LocalDate, val suggestions: List<String>)

    /** Guarded exactly like [ThreadsDossierBuilder]'s own memo — `build` is
     * a plain `suspend fun`, not actor/dispatcher-confined. */
    @Volatile
    private var memo: Memo? = null
    private val memoMutex = Mutex()

    /**
     * Returns up to [MAX_SUGGESTIONS] phrases, or `emptyList()` when the
     * kill switch is set, the toggle is off, or there is no recording→walk
     * data at all. [now] is the sheet-open moment the 30-day recurrence
     * window is anchored to.
     *
     * @param walkIndex Test seam mirroring iOS's own `walkIndex` parameter
     *   (`Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:59-64@0172e2b`
     *   — "walkIndex is injectable for Task 8's wiring test; production
     *   callers pass nil and read the live CoreStore index"): `null` (the
     *   production default) reads the live recording→walk join via
     *   [VoiceRecordingDao.recordingWalkLiteIndex]; tests substitute a
     *   fixed map and never touch the DAO.
     */
    suspend fun current(now: Instant = Instant.now(), walkIndex: Map<String, WalkLite>? = null): List<String> {
        if (PENDING_FIELD_GATE) return emptyList()
        if (!preferences.threadsAfterWalks.value) return emptyList()
        val index = walkIndex ?: voiceRecordingDao.recordingWalkLiteIndex().associate { it.uuid to it.toWalkLite() }
        if (index.isEmpty()) return emptyList()

        val day = now.atZone(ZoneId.systemDefault()).toLocalDate()
        // Captured BEFORE loadAll — same ordering discipline as
        // ThreadsDossierBuilder, same reason: a mutation landing after this
        // read must leave THIS call's memo write stale for the next call,
        // never get silently absorbed as if this call had already
        // accounted for it (BEH-51/BEH-52).
        val preLoadChangeCount = store.changeCount.value
        val cached = memoMutex.withLock { memo }
        if (cached != null && cached.changeCount == preLoadChangeCount && cached.day == day) {
            return cached.suggestions
        }

        return withContext(Dispatchers.Default) {
            val contexts = store.loadAll()
            // backfillComplete is irrelevant here: this call reads only
            // `.active` from the result, never `.firstTimeLemmas` (the one
            // field backfillComplete feeds) — see ThreadStore.build.
            val threads = ThreadStore.build(
                contexts = contexts,
                recordingToWalk = index,
                anchor = now,
                backfillComplete = false,
            )
            val suggestions = select(threads.active, now)
            memoMutex.withLock { memo = Memo(preLoadChangeCount, day, suggestions) }
            suggestions
        }
    }

    /**
     * Sort (distinct-walk count desc, tie -> displayTerm asc) -> template
     * -> dedup by rendered phrase -> cap, IN THAT ORDER (BEH-50/EDG-80):
     * two lemmas can share a displayTerm ("move"/"moving" -> "the move"),
     * so deduping AFTER templating and BEFORE capping is what lets a
     * distinct suggestion behind a duplicate still get its chance.
     * Deduping earlier, or capping before deduping, can return fewer than
     * [MAX_SUGGESTIONS] chips when that many distinct ones exist.
     */
    private fun select(active: List<ActiveThread>, asOf: Instant): List<String> {
        val windowStart = asOf.minus(ThreadStore.RECURRENCE_WINDOW)
        val seen = mutableSetOf<String>()
        return active
            .mapNotNull { thread ->
                val distinctWalks = thread.appearances
                    .filter { it.date >= windowStart && it.date <= asOf }
                    .map { it.walkId }
                    .distinct()
                if (distinctWalks.size < MINIMUM_DISTINCT_WALKS) return@mapNotNull null
                thread.displayTerm to distinctWalks.size
            }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map { (displayTerm, _) -> "walk with '$displayTerm'" }
            .filter { seen.add(it) }
            .take(MAX_SUGGESTIONS)
    }

    companion object {
        /**
         * Ship gate cleared 2026-08-24 (parity spec EDG-79/UI-28): false =
         * ENABLED. Kept as a mutable `internal var` rather than a `const
         * val` — the whole reason this constant exists is so a staged
         * rollout (or a unit test verifying the guard itself) can flip it
         * without needing every call site touched; a `const val` would
         * still require a recompile for a real staged rollout anyway,
         * so nothing is gained by making it compile-time-inlined.
         */
        internal var PENDING_FIELD_GATE = false

        const val MINIMUM_DISTINCT_WALKS = 2
        const val MAX_SUGGESTIONS = 2
    }
}
