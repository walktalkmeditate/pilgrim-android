// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Duration
import java.time.Instant

/**
 * Walk-level projection joined against the recording→walk index —
 * [org.walktalkmeditate.pilgrim.data.dao.WalkDao]'s own projection query,
 * kept free of any Room/entity coupling so this file and
 * [ThreadsDossierFormatter] never import `data.entity.Walk`.
 */
data class WalkLite(
    val walkId: Long,
    val startedAt: Instant,
    val intention: String?,
    val weatherCondition: String?,
)

/**
 * One thread's appearance in a single recording — the Kotlin analogue of
 * iOS `ThreadAppearance` (`Pilgrim/Models/Threads/ThreadStore.swift:3-9@0172e2b`).
 */
data class ThreadAppearance(
    val recordingUuid: String,
    val walkId: Long,
    val date: Instant,
    val mentionCount: Int,
    val salience: Double,
)

/**
 * Port of iOS `ThreadStatus` — three outcomes, not two:
 * [FirstTime] (a full-history claim, backfill-gated), [Recurring] (how
 * many distinct walks fall in the trailing 30-day window), or `null`
 * (no status at all, when backfill is incomplete and no earlier
 * appearance exists — see [ThreadStore.status]). A sealed type modeling
 * only the two named cases and defaulting the "no status" case to
 * [FirstTime] would make false origin claims before the backfill sweep
 * finishes.
 */
sealed interface ThreadStatus {
    data object FirstTime : ThreadStatus
    data class Recurring(val walksInWindow: Int) : ThreadStatus
}

/** Dossier-only trend fit — a trend fitted to few noisy points never
 * reaches any other surface (spec principle 1). */
enum class SalienceDirection { RISING, STEADY, FADING }

/**
 * One recurring lemma across the walker's history. [distinctWalkIds] is
 * derived from [appearances] rather than stored directly: the
 * recording-uuid-keyed join in [ThreadStore.build] is what makes it
 * possible to attribute each appearance to the walk that produced it in
 * the first place — a bare `List<WalkLite>` has no such attribution.
 */
data class ActiveThread(
    val lemma: String,
    val displayTerm: String,
    val appearances: List<ThreadAppearance>,
) {
    val distinctWalkIds: List<Long> get() = appearances.map { it.walkId }.distinct()
}

/**
 * The result of one [ThreadStore.build] call. [firstTimeLemmas] is a
 * convenience precomputed AS OF [ThreadStore.build]'s own `anchor`
 * parameter — for a lemma whose latest appearance at or before that
 * anchor has no earlier appearance at all (see [ThreadStore.status]),
 * gated on `backfillComplete` the same way. The per-walk, per-thread
 * "recurring(N)" detail [ThreadsDossierFormatter] needs still comes from
 * calling [ThreadStore.status] directly with a specific walk id — this
 * set only answers "is this lemma's first appearance at or before the
 * anchor its ONLY one".
 */
data class Threads(
    val active: List<ActiveThread>,
    val firstTimeLemmas: Set<String>,
)

/**
 * Pure in-memory thread aggregation, ported from
 * `Pilgrim/Models/Threads/ThreadStore.swift` at the pinned iOS commit
 * (parity spec `docs/parity/2026-08-25-threads-engine-port.md`,
 * BEH-32..35/EDG-62..65). [build] is pure and UNMEMOIZED — memoization
 * lives above it, in [ThreadsDossierBuilder], matching iOS exactly.
 */
object ThreadStore {

    /**
     * 30-day trailing window. iOS redeclares this same NUMBER three times
     * (ThreadStore, ThreadsDossierFormatter's `absenceWindow`,
     * ThreadIntentionSuggestions' `recurrenceWindow`, BEH-87) — but the
     * senses slice (U9) is a DIFFERENT case: iOS's `DossierSensesTracks`/
     * `ThreadsDossierBuilder` read `ThreadStore.recurrenceWindow` directly
     * (`Pilgrim/Models/Threads/ThreadStore.swift:30@0172e2b`, cited by 5
     * call sites in `docs/parity/2026-08-26-threads-senses-port.md` —
     * placeResonance, intentionLineage, weatherWeave, the senses-bundle
     * gather step, and route-fix resolution). Promoted from `private` to
     * `internal` so those five senses call sites share this SAME instance
     * rather than hand-copying "30 days" a fourth time.
     */
    internal val RECURRENCE_WINDOW: Duration = Duration.ofDays(30)
    const val DIRECTION_FLOOR = 3
    const val DIRECTION_THRESHOLD = 0.25

    /**
     * Aggregates [contexts] into [Threads]. [recordingToWalk] is keyed by
     * RECORDING uuid (iOS parity: `walks[context.recordingUUID]` is the
     * join) — a context whose uuid has no entry is silently excluded
     * (the orphan prune; a caller-level concern, e.g. a stale-schema file
     * for a deleted recording, not an error here).
     *
     * Duplicate uuids inside [contexts] fail loudly: two contexts sharing
     * a recording uuid is a data-integrity bug the store must never mask
     * by silently keeping the last one (iOS's `Dictionary(uniqueKeysWithValues:)`
     * traps for the same reason at the dossier-builder layer; this layer
     * checks it too since [contexts] is this function's own direct input).
     */
    fun build(
        contexts: List<TranscriptContext>,
        recordingToWalk: Map<String, WalkLite>,
        anchor: Instant,
        backfillComplete: Boolean,
    ): Threads {
        val seenUuids = HashSet<String>(contexts.size)
        for (context in contexts) {
            check(seenUuids.add(context.uuid)) {
                "ThreadStore.build received two contexts for recording uuid ${context.uuid} — " +
                    "a duplicate-uuid context set is a data-integrity bug that must fail loudly, " +
                    "never be silently resolved by keeping the last one."
            }
        }

        val appearancesByLemma = LinkedHashMap<String, MutableList<ThreadAppearance>>()
        val displayCounts = HashMap<String, MutableMap<String, Int>>()

        for (context in contexts) {
            val walk = recordingToWalk[context.uuid] ?: continue
            for (theme in context.themes) {
                appearancesByLemma.getOrPut(theme.lemma) { mutableListOf() } += ThreadAppearance(
                    recordingUuid = context.uuid,
                    walkId = walk.walkId,
                    date = walk.startedAt,
                    mentionCount = theme.mentionCount,
                    salience = theme.salience,
                )
                val counts = displayCounts.getOrPut(theme.lemma) { mutableMapOf() }
                counts[theme.displayTerm] = (counts[theme.displayTerm] ?: 0) + theme.mentionCount
            }
        }

        val active = appearancesByLemma.map { (lemma, appearances) ->
            ActiveThread(
                lemma = lemma,
                displayTerm = displayTerm(lemma, displayCounts),
                appearances = appearances.sortedWith(compareBy({ it.date }, { it.recordingUuid })),
            )
        }.sortedBy { it.lemma }

        val firstTimeLemmas = active.mapNotNull { thread ->
            val current = thread.appearances.lastOrNull { it.date <= anchor } ?: return@mapNotNull null
            val currentStatus = status(thread, current.walkId, backfillComplete)
            thread.lemma.takeIf { currentStatus is ThreadStatus.FirstTime }
        }.toSet()

        return Threads(active = active, firstTimeLemmas = firstTimeLemmas)
    }

    /**
     * Max mention-weighted surface count, tie-broken lexicographically
     * smallest — mirrors iOS's `.min { ($0.value, $1.key) > ($1.value, $0.key) }?.key ?? lemma`
     * tuple-swap exactly (descending by count, ascending by key on ties),
     * WITH the lemma fallback iOS's ThreadStore carries (unlike
     * ThemeExtractor's force-unwrap, EDG-63): an empty `displayCounts`
     * entry — which cannot happen from [build]'s own bookkeeping, but
     * this mirrors iOS's defensive shape rather than assuming it.
     */
    private fun displayTerm(lemma: String, displayCounts: Map<String, Map<String, Int>>): String =
        displayCounts[lemma]
            ?.minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
            ?: lemma

    /**
     * Resolves [thread]'s status AT the walk identified by [atWalkId].
     * `null` when that walk has no appearance for this thread at all, OR
     * when it is the thread's earliest appearance and [backfillComplete]
     * is false (origin suppression — BEH-32). The window bound is NEVER
     * applied to the "does an earlier appearance exist at all" check —
     * only to the [ThreadStatus.Recurring] walk COUNT — so a 31-day-old
     * appearance still rules out [ThreadStatus.FirstTime] even though it
     * falls outside the 30-day window it would otherwise be counted in.
     */
    fun status(thread: ActiveThread, atWalkId: Long, backfillComplete: Boolean): ThreadStatus? {
        val current = thread.appearances.firstOrNull { it.walkId == atWalkId } ?: return null
        val earlier = thread.appearances.filter { it.date < current.date && it.walkId != atWalkId }
        if (earlier.isEmpty()) {
            return if (backfillComplete) ThreadStatus.FirstTime else null
        }
        val windowStart = current.date.minus(RECURRENCE_WINDOW)
        val walksInWindow = thread.appearances
            .filter { it.date >= windowStart && it.date <= current.date }
            .map { it.walkId }
            .toSet()
            .size
        return ThreadStatus.Recurring(walksInWindow)
    }

    /**
     * Trend direction over [thread]'s full salience history — floor of
     * [DIRECTION_FLOOR] appearances, thirds floored at 1 (never a
     * 0-sized third / divide-by-zero for 3-4 appearances), early-third
     * average must be positive or the result defaults to [SalienceDirection.STEADY]
     * rather than a nonsensical ratio (BEH-34/EDG-65).
     */
    fun salienceDirection(thread: ActiveThread): SalienceDirection? {
        val saliences = thread.appearances.map { it.salience }
        if (saliences.size < DIRECTION_FLOOR) return null
        val third = maxOf(1, saliences.size / 3)
        val early = saliences.take(third).sum() / third
        val late = saliences.takeLast(third).sum() / third
        if (early <= 0.0) return SalienceDirection.STEADY
        val change = (late - early) / early
        return when {
            change >= DIRECTION_THRESHOLD -> SalienceDirection.RISING
            change <= -DIRECTION_THRESHOLD -> SalienceDirection.FADING
            else -> SalienceDirection.STEADY
        }
    }
}
