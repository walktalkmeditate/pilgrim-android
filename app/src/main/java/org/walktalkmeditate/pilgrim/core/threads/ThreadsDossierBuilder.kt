// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * The finished AI-prompt dossier text for one walk. [render] returns it
 * as a single-element list so a future caller that folds several
 * optional prompt sections through a uniform "render → join" step (the
 * way [org.walktalkmeditate.pilgrim.core.prompt.AttentionDirectives]'
 * bullet list already is) can treat a present dossier the same way,
 * without special-casing a bare String.
 */
data class DossierBlock(val text: String) {
    fun render(): List<String> = listOf(text)
}

/**
 * The six-field memo key (BEH-36/EDG-67): each field closes a specific
 * cache-miss gap a naive changeCount-only key would miss.
 * [moonState]/[lunationIndex] are U9's slots — this unit has no moon/
 * lunation source yet, so [ThreadsDossierBuilder.build] always passes
 * `null` for both; wiring a real source is that unit's job, not a
 * reason to omit the fields now (a later moon-line write would
 * otherwise never invalidate a dossier built before U9 lands).
 */
data class ThreadsDossierMemoKey(
    val changeCount: Long,
    val walkId: Long,
    val backfillComplete: Boolean,
    val moonState: Int?,
    val lunationIndex: Int?,
    val intention: String?,
)

/**
 * Merges the live (schema-current, non-orphan) stored contexts with the
 * ones freshly (re)computed during this build, sorted by uuid ascending
 * for determinism (matching [TranscriptContextStore.loadAll]'s own
 * convention). Two contexts sharing a uuid inside [live] is a data-
 * integrity bug this function fails LOUDLY on — the Kotlin analogue of
 * iOS's `Dictionary(uniqueKeysWithValues:)` trap (BEH-40/EDG-71): an
 * `associateBy`-style silent keep-last would mask exactly the bug class
 * this check exists to surface. `internal` (rather than `private`) so it
 * is directly unit-testable without constructing the whole builder.
 */
internal fun mergeLiveAndFreshContexts(
    live: List<TranscriptContext>,
    freshlySaved: Map<String, TranscriptContext>,
): List<TranscriptContext> {
    val byUuid = LinkedHashMap<String, TranscriptContext>()
    for (context in live) {
        check(byUuid.put(context.uuid, context) == null) {
            "ThreadsDossierBuilder found two stored contexts for recording uuid ${context.uuid} — " +
                "a duplicate-uuid context set is a data-integrity bug that must fail loudly, " +
                "never be silently resolved by keeping the last one."
        }
    }
    for ((uuid, context) in freshlySaved) {
        byUuid[uuid] = context
    }
    return byUuid.values.sortedBy { it.uuid }
}

/**
 * Builds the AI-prompt dossier for one walk, ported from
 * `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift` at the pinned iOS
 * commit (parity spec `docs/parity/2026-08-25-threads-engine-port.md`,
 * BEH-36..42/EDG-66..71). Android's version is self-contained — it
 * gathers its own recording→walk join and current-walk recordings via
 * [voiceRecordingDao]/[walkDao] rather than requiring a caller to
 * pre-fetch them on a specific actor/thread the way CoreStore's
 * MainActor constraint forces iOS to.
 *
 * Deliberately DOES NOT implement iOS's senses/`gatherSensesBundle`/
 * moon-line appendix (`DossierSenses`, the `**Noticed:**` block) — that
 * is U9's scope. [ThreadsDossierMemoKey.moonState]/[ThreadsDossierMemoKey.lunationIndex]
 * are carried as always-`null` placeholders so the memo key's SHAPE is
 * already correct for when U9 wires a real source; they must not be
 * silently dropped from the key in the meantime.
 */
@Singleton
class ThreadsDossierBuilder @Inject constructor(
    private val store: TranscriptContextStore,
    private val analyzer: TranscriptContextAnalyzer,
    private val preferences: ThreadsPreferencesRepository,
    private val voiceRecordingDao: VoiceRecordingDao,
    private val walkDao: WalkDao,
) {

    private data class CachedDossier(val key: ThreadsDossierMemoKey, val dossier: DossierBlock?)

    /**
     * Models iOS's `String??` cache value (BEH-37/EDG-68): [memo] being
     * `null` means "nothing cached yet"; a non-null [CachedDossier] whose
     * own [CachedDossier.dossier] is `null` means "cached, and the answer
     * IS no dossier" — a legitimate, distinct hit. Guarded by [memoMutex]
     * exactly like iOS's `NSLock`-guarded static var — `build` itself is
     * NOT actor/dispatcher-confined (a plain `suspend fun`), so concurrent
     * callers genuinely need the lock, unlike single-actor-confined state
     * elsewhere in this package.
     */
    @Volatile
    private var memo: CachedDossier? = null
    private val memoMutex = Mutex()

    /**
     * Returns `null` when the toggle is off, the walk doesn't exist, or
     * it has no recordings with real transcribed text — otherwise the
     * dossier text for [walkId], from the memo when nothing has changed
     * since the last call.
     */
    suspend fun build(walkId: Long): DossierBlock? {
        if (!preferences.threadsAfterWalks.value) return null

        val walkLite = walkDao.getWalkLite(walkId)?.toWalkLite() ?: return null
        val recordings = voiceRecordingDao.getForWalk(walkId).filter { !it.transcription.isNullOrEmpty() }
        if (recordings.isEmpty()) return null

        // One consistent read each, captured BEFORE any store mutation this
        // call might make: a mid-build external mutation must leave the
        // memoized tokens stale (so the NEXT call rebuilds), never get
        // silently absorbed as if this build had already accounted for it.
        val backfillComplete = isBackfillComplete()
        val preBuildChangeCount = store.changeCount.value
        // U9 slots — see class KDoc.
        val moonState: Int? = null
        val lunationIndex: Int? = null
        val preBuildKey = ThreadsDossierMemoKey(
            changeCount = preBuildChangeCount,
            walkId = walkId,
            backfillComplete = backfillComplete,
            moonState = moonState,
            lunationIndex = lunationIndex,
            intention = walkLite.intention,
        )

        when (val lookup = cachedDossier(preBuildKey)) {
            is MemoLookup.Hit -> return lookup.dossier
            MemoLookup.Miss -> Unit
        }

        // Single directory decode per build: orphans come from the same
        // load that feeds the dossier, and fresh analyses are merged in by
        // hand instead of re-reading the directory afterwards.
        val all = store.loadAll()
        val recordingWalkIndex = voiceRecordingDao.recordingWalkLiteIndex().associate { it.uuid to it.toWalkLite() }
        // An empty index alongside non-empty stored contexts is a failed/
        // empty read, not proof of orphanhood — pruning then would delete
        // every stored linguistic analysis on a transient read failure.
        val orphans = if (recordingWalkIndex.isEmpty() && all.isNotEmpty()) {
            emptySet()
        } else {
            all.map { it.uuid }.filterNot { it in recordingWalkIndex }.toSet()
        }
        if (orphans.isNotEmpty()) {
            store.delete(orphans.toList())
        }
        // delete() bumps changeCount exactly once per CALL regardless of
        // batch size — counted here so the memo baseline below can add
        // exactly this build's own confirmed writes instead of
        // re-sampling store.changeCount after the fact.
        val ownDeleteWrite = if (orphans.isEmpty()) 0 else 1
        val live = all.filterNot { it.uuid in orphans }

        val (current, freshlySaved) = resolveCurrentContexts(recordings)
        if (current.isEmpty()) return null

        val allContexts = mergeLiveAndFreshContexts(live, freshlySaved)
        val walkIdByRecordingUuid = recordingWalkIndex.mapValues { it.value.walkId }
        val threads = ThreadStore.build(
            contexts = allContexts,
            recordingToWalk = recordingWalkIndex,
            anchor = walkLite.startedAt,
            backfillComplete = backfillComplete,
        )

        val dossierText = ThreadsDossierFormatter.dossier(
            currentRecordings = current,
            allContexts = allContexts,
            threads = threads,
            currentWalkId = walkId,
            backfillComplete = backfillComplete,
            walkIdByRecordingUuid = walkIdByRecordingUuid,
        )
        val dossier = dossierText?.let(::DossierBlock)

        // Post-build changeCount = preBuild + OWN confirmed writes — NEVER
        // a fresh re-read (BEH-38/EDG-70). A concurrent external writer
        // (the backfill sweep, another recording's transcription analysis)
        // landing a save inside this same call would otherwise get folded
        // into the memo as if it were this build's own mutation.
        val ownWriteCount = ownDeleteWrite + freshlySaved.size
        val postBuildKey = preBuildKey.copy(changeCount = preBuildChangeCount + ownWriteCount)
        memoMutex.withLock { memo = CachedDossier(postBuildKey, dossier) }
        return dossier
    }

    /**
     * A plain `DossierBlock?` return here could not distinguish "nothing
     * cached for this key" from "cached, and the answer IS no dossier" —
     * exactly the `String??` double-optional iOS's own cache value
     * models (BEH-37/EDG-68). [MemoLookup.Miss] means rebuild;
     * [MemoLookup.Hit] (even carrying a `null` dossier) means return
     * immediately without touching the store again.
     */
    private sealed interface MemoLookup {
        data object Miss : MemoLookup
        data class Hit(val dossier: DossierBlock?) : MemoLookup
    }

    private suspend fun cachedDossier(key: ThreadsDossierMemoKey): MemoLookup {
        val cached = memoMutex.withLock { memo }
        return if (cached != null && cached.key == key) MemoLookup.Hit(cached.dossier) else MemoLookup.Miss
    }

    /**
     * Resolves each current recording's context — hash-matched cache hit,
     * or a lazy inline self-heal via [analyzer] (empty flagged ranges:
     * segment-level ASR-quality signals aren't persisted, so a self-heal
     * can't recover them — BEH-40). `freshlySaved` counts only writes that
     * actually reached disk (a tombstone-blocked save is not a write),
     * keeping the memo's own-write accounting honest.
     */
    private suspend fun resolveCurrentContexts(
        recordings: List<VoiceRecording>,
    ): Pair<List<Pair<TranscriptContext, Double?>>, Map<String, TranscriptContext>> {
        val freshlySaved = LinkedHashMap<String, TranscriptContext>()
        val current = mutableListOf<Pair<TranscriptContext, Double?>>()
        for (recording in recordings) {
            val text = recording.transcription ?: continue
            val hash = TranscriptContext.hashTranscript(text)
            val stored = store.read(recording.uuid, hash)
            if (stored != null) {
                current += stored to recording.wordsPerMinute
                continue
            }
            val healed = analyzer.analyzeAndStore(recording.uuid, text)
            if (healed != null) {
                if (store.hasContext(recording.uuid)) {
                    freshlySaved[recording.uuid] = healed
                }
                current += healed to recording.wordsPerMinute
            }
            // healed == null (non-English, or a genuine write failure):
            // this recording contributes nothing to `current` — Android's
            // own tightening (TranscriptContextAnalyzer writes nothing at
            // all rather than a themes-only context, unlike iOS).
        }
        return current to freshlySaved
    }

    private suspend fun isBackfillComplete(): Boolean =
        preferences.backfillCompletedAtVersion() == TranscriptContext.ANALYSIS_VERSION &&
            preferences.backfillCompletedAtImportGeneration() == preferences.importGeneration.value
}
