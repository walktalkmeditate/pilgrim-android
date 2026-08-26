// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.walk.AltitudeCalculator

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
 * cache-miss gap a naive changeCount-only key would miss. [moonState] is
 * [ThreadsPreferencesRepository.moonLineLastLunationIndex] read fresh
 * every build (absent = `null`, never 0-defaulted); [lunationIndex] is
 * the most-recently-CLOSED [Lunation]'s index as of this build's own
 * captured `now`. Either changing between two builds invalidates the
 * memo even when [changeCount] hasn't moved — a lunation closing, or an
 * external moon-line write, must not serve a stale dossier.
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
 * commit (parity spec `docs/parity/2026-08-25-threads-engine-port.md` +
 * `docs/parity/2026-08-26-threads-senses-port.md`, BEH-36..42/EDG-66..71).
 * Android's version is self-contained — it gathers its own recording→walk
 * join, current-walk recordings, and senses inputs via its own DAOs
 * rather than requiring a caller to pre-fetch them on a specific
 * actor/thread the way CoreStore's MainActor constraint forces iOS to.
 *
 * Includes the U9 senses assembly: gathers a [SenseInput] per build,
 * evaluates [DossierSenses.lines], and appends the result as a
 * `**Noticed:**` block after the base dossier text — all-or-nothing,
 * never an empty heading. The moon-line preference write is gated
 * strictly on [SenseOutput.reportedLunationIndex], never on mere
 * eligibility.
 */
@Singleton
class ThreadsDossierBuilder @Inject constructor(
    private val store: TranscriptContextStore,
    private val analyzer: TranscriptContextAnalyzer,
    private val preferences: ThreadsPreferencesRepository,
    private val voiceRecordingDao: VoiceRecordingDao,
    private val walkDao: WalkDao,
    private val routeDataSampleDao: RouteDataSampleDao,
    private val walkPhotoDao: WalkPhotoDao,
    private val altitudeSampleDao: AltitudeSampleDao,
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
     * since the last call. [now] is this build's ONE wall-clock capture
     * (senses BEH-42/DAT-56) — threaded through to both the memo key's
     * [ThreadsDossierMemoKey.lunationIndex] and the senses gather step,
     * never re-read mid-build.
     */
    suspend fun build(walkId: Long, now: Instant = Instant.now()): DossierBlock? {
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
        val moonState = preferences.moonLineLastLunationIndex()
        val closedLunation = LunationCalendar.mostRecentClosed(asOf = now)
        val preBuildKey = ThreadsDossierMemoKey(
            changeCount = preBuildChangeCount,
            walkId = walkId,
            backfillComplete = backfillComplete,
            moonState = moonState,
            lunationIndex = closedLunation.index,
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

        // Senses evaluate only when the base dossier built — a walk with
        // no current recordings never reaches here (the `current.isEmpty()`
        // guard above already returned). All-or-nothing append; zero
        // lines never produces an empty "**Noticed:**" heading.
        val dossier = dossierText?.let { base ->
            val senseInput = gatherSenseInput(
                walkDao = walkDao,
                voiceRecordingDao = voiceRecordingDao,
                routeDataSampleDao = routeDataSampleDao,
                walkPhotoDao = walkPhotoDao,
                altitudeSampleDao = altitudeSampleDao,
                walkId = walkId,
                walkLite = walkLite,
                closedLunation = closedLunation,
                moonState = moonState,
                threads = threads,
                current = current,
                allContexts = allContexts,
                recordingWalkIndex = recordingWalkIndex,
                recordings = recordings,
                backfillComplete = backfillComplete,
                now = now,
            )
            val output = DossierSenses.lines(senseInput)
            val text = if (output.lines.isEmpty()) base else base + "\n\n**Noticed:**\n" + output.lines.joinToString("\n")
            output.reportedLunationIndex?.let { preferences.setMoonLineLastLunationIndex(it) }
            DossierBlock(text)
        }

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

/**
 * Gathers a [SenseInput] for [walkId] — the window-UNION fetch (30-day
 * recurrence window ∪ the closed lunation), lazy per-UUID route-fix
 * resolution, and the walk-collapsed worded-dates index. Module purity
 * is preserved on the OTHER side of this boundary: [DossierSenses] itself
 * fetches nothing — everything here is gathered before the pure engine
 * ever runs.
 *
 * `internal` TOP-LEVEL (not a [ThreadsDossierBuilder] member) so the
 * DEBUG-only field report (`ThreadsFieldReport`, `src/debug/kotlin/`)
 * shares this SAME implementation rather than a second, divergent copy —
 * the only difference between the two call sites is [moonState] (the
 * field report always passes `null`, per its own never-write-real-state
 * discipline).
 */
internal suspend fun gatherSenseInput(
    walkDao: WalkDao,
    voiceRecordingDao: VoiceRecordingDao,
    routeDataSampleDao: RouteDataSampleDao,
    walkPhotoDao: WalkPhotoDao,
    altitudeSampleDao: AltitudeSampleDao,
    walkId: Long,
    walkLite: WalkLite,
    closedLunation: Lunation,
    moonState: Int?,
    threads: Threads,
    current: List<Pair<TranscriptContext, Double?>>,
    allContexts: List<TranscriptContext>,
    recordingWalkIndex: Map<String, WalkLite>,
    recordings: List<VoiceRecording>,
    backfillComplete: Boolean,
    now: Instant,
): SenseInput {
    val walkEnd = walkDao.getById(walkId)?.endTimestamp?.let(Instant::ofEpochMilli) ?: now
    val recurrenceWindowStart = walkLite.startedAt.minus(ThreadStore.RECURRENCE_WINDOW)
    val fetchFrom = minOf(recurrenceWindowStart, closedLunation.start)
    val fetchTo = maxOf(walkEnd, closedLunation.end)

    val walkSnapshots = walkDao.walkSensesSnapshot(fetchFrom.toEpochMilli(), fetchTo.toEpochMilli())
        .map { it.toWalkSnapshotRow() }
    val recordingTimestamps = voiceRecordingDao.recordingTimestampIndex()
        .associate { it.uuid to Instant.ofEpochMilli(it.startTimestamp) }

    val altitudeSamples = altitudeSampleDao.getForWalk(walkId)
    val (totalAscent, _) = AltitudeCalculator.computeAscentDescent(altitudeSamples)
    val elevationSeries = altitudeSamples.map { ElevationSample(Instant.ofEpochMilli(it.timestamp), it.altitudeMeters) }

    val photos = walkPhotoDao.getForWalk(walkId).map { photo ->
        val coordinate = if (photo.capturedLat != null && photo.capturedLng != null) {
            Coordinate(photo.capturedLat, photo.capturedLng)
        } else {
            null
        }
        // Android's schema already models "no location" as nullable
        // columns — unlike iOS there is no (-1,-1) sentinel to
        // translate at this boundary.
        PhotoPin(capturedAt = Instant.ofEpochMilli(photo.takenAt ?: photo.pinnedAt), coordinate = coordinate)
    }

    val recordingsByUuid = recordings.associateBy { it.uuid }
    // A context whose recording no longer resolves participates in NO
    // sense — mirrors iOS's own drop rule (missing uuid/context).
    val currentRecordings = current.mapNotNull { (context, _) ->
        val recording = recordingsByUuid[context.uuid] ?: return@mapNotNull null
        CurrentRecording(
            uuid = context.uuid,
            start = Instant.ofEpochMilli(recording.startTimestamp),
            // Android's endTimestamp is non-nullable by schema
            // invariant — no `endTimestamp ?? start` fallback needed.
            end = Instant.ofEpochMilli(recording.endTimestamp),
            text = recording.transcription.orEmpty(),
            wordCount = context.wordCount,
            themes = context.themes,
        )
    }

    // wordedWalkDates iterates ALL resolved contexts (not just the
    // current walk's) and collapses to one date per WALK — moonLine
    // wants a walk count, not a per-recording count.
    val wordedWalkDates = linkedMapOf<Long, Instant>()
    for (context in allContexts) {
        if (context.wordCount <= 0) continue
        val walk = recordingWalkIndex[context.uuid] ?: continue
        wordedWalkDates[walk.walkId] = walk.startedAt
    }

    val fixes = resolveFixes(
        routeDataSampleDao = routeDataSampleDao,
        threads = threads.active,
        currentRecordings = currentRecordings,
        recordingTimestamps = recordingTimestamps,
        windowStart = recurrenceWindowStart,
        walkEnd = walkEnd,
    )

    val moon = MoonInput(
        lunationIndex = closedLunation.index,
        moonName = LunationCalendar.moonName(closedLunation),
        start = closedLunation.start,
        end = closedLunation.end,
        lastReportedIndex = moonState,
        currentWalkHasWords = currentRecordings.any { it.wordCount > 0 },
        allWalkDates = walkSnapshots.map { it.startDate },
        wordedWalkDates = wordedWalkDates.values.toList(),
    )

    return SenseInput(
        currentWalkId = walkId,
        walkStart = walkLite.startedAt,
        walkEnd = walkEnd,
        totalAscent = totalAscent,
        elevationSeries = elevationSeries,
        photos = photos,
        currentRecordings = currentRecordings,
        threads = threads.active,
        backfillComplete = backfillComplete,
        walkSnapshots = walkSnapshots,
        recordingTimestamps = recordingTimestamps,
        fixes = fixes,
        moon = moon,
    )
}

/**
 * The needed-UUID set has two sources — in-window mention recordings
 * across ALL threads (the baseline needs them all) plus the current
 * walk's themed recordings — resolved lazily, one bounded query per
 * UUID, in uuid-string-sorted order (determinism). The [currentRecordings]-
 * start fallback covers a themed recording made during the CURRENT walk
 * that hasn't landed in [recordingTimestamps] yet.
 */
internal suspend fun resolveFixes(
    routeDataSampleDao: RouteDataSampleDao,
    threads: List<ActiveThread>,
    currentRecordings: List<CurrentRecording>,
    recordingTimestamps: Map<String, Instant>,
    windowStart: Instant,
    walkEnd: Instant,
): Map<String, RouteFix> {
    val needed = sortedSetOf<String>()
    for (thread in threads) {
        for (appearance in thread.appearances) {
            val instant = recordingTimestamps[appearance.recordingUuid] ?: continue
            if (instant.isBefore(windowStart) || instant.isAfter(walkEnd)) continue
            needed += appearance.recordingUuid
        }
    }
    for (recording in currentRecordings) {
        if (recording.themes.isNotEmpty()) needed += recording.uuid
    }

    val fixes = mutableMapOf<String, RouteFix>()
    for (uuid in needed) {
        val timestamp = recordingTimestamps[uuid] ?: currentRecordings.firstOrNull { it.uuid == uuid }?.start
        if (timestamp != null) {
            resolveRouteFix(routeDataSampleDao, timestamp)?.let { fixes[uuid] = it }
        }
    }
    return fixes
}

/**
 * Bounded ±[DossierSenses.HYGIENE_MAX_GAP_SECONDS] fetch, `(gap,
 * accuracy)`-lexicographic min pick. `gapSeconds` is frozen at resolution
 * time here — never recomputed later against a different reference
 * instant. A null stored accuracy (an Android-only possibility) is
 * treated as maximally inaccurate so it can never win over a real
 * reading and always fails [DossierSenses.qualifies].
 */
internal suspend fun resolveRouteFix(routeDataSampleDao: RouteDataSampleDao, referenceInstant: Instant): RouteFix? {
    val windowMillis = (DossierSenses.HYGIENE_MAX_GAP_SECONDS * 1_000.0).toLong()
    val referenceMillis = referenceInstant.toEpochMilli()
    val rows = routeDataSampleDao.routeSamplesNear(
        windowStart = referenceMillis - windowMillis,
        windowEnd = referenceMillis + windowMillis,
    )
    return rows
        .map { row ->
            RouteFix(
                coordinate = Coordinate(row.latitude, row.longitude),
                horizontalAccuracy = row.horizontalAccuracyMeters?.toDouble() ?: Double.MAX_VALUE,
                gapSeconds = kotlin.math.abs(row.timestamp - referenceMillis) / 1_000.0,
            )
        }
        .minWithOrNull(compareBy({ it.gapSeconds }, { it.horizontalAccuracy }))
}
