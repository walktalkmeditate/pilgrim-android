// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RecordingWalkLiteRow
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * [ThreadsDossierBuilder] against REAL collaborators (Robolectric filesDir
 * store, real WordNet/VADER analysis, an in-memory Room DB) — parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md` BEH-36..41/EDG-67..71.
 * Only the language client is faked (always reports English), matching
 * [TranscriptContextAnalyzerTest]'s established pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsDossierBuilderTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var realVoiceRecordingDao: VoiceRecordingDao
    private lateinit var voiceRecordingDao: CountingVoiceRecordingDao
    private lateinit var walkDao: WalkDao
    private lateinit var routeDataSampleDao: RouteDataSampleDao
    private lateinit var walkPhotoDao: WalkPhotoDao
    private lateinit var altitudeSampleDao: AltitudeSampleDao
    private lateinit var store: TranscriptContextStore
    private lateinit var analyzer: TranscriptContextAnalyzer
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var builder: ThreadsDossierBuilder

    /**
     * [recordingWalkLiteIndex] runs exactly once per real rebuild inside
     * [ThreadsDossierBuilder.build] and never on a memo hit — the most
     * direct observable signal for "did this call actually recompute" a
     * test can get without an `open`/spyable hook on [TranscriptContextStore]
     * itself (whose `loadAll` is intentionally not `open`).
     */
    private class CountingVoiceRecordingDao(private val delegate: VoiceRecordingDao) : VoiceRecordingDao by delegate {
        var recordingWalkLiteIndexCallCount = 0
            private set

        override suspend fun recordingWalkLiteIndex(): List<RecordingWalkLiteRow> {
            recordingWalkLiteIndexCallCount++
            return delegate.recordingWalkLiteIndex()
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        val environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
        preferences = FakeThreadsPreferencesRepository()
        val languageClient = MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(LanguageGuess("en", 0.99f))
            },
        )
        analyzer = TranscriptContextAnalyzer(store, environment, languageClient, preferences)

        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        realVoiceRecordingDao = db.voiceRecordingDao()
        voiceRecordingDao = CountingVoiceRecordingDao(realVoiceRecordingDao)
        walkDao = db.walkDao()
        routeDataSampleDao = db.routeDataSampleDao()
        walkPhotoDao = db.walkPhotoDao()
        altitudeSampleDao = db.altitudeSampleDao()

        builder = ThreadsDossierBuilder(
            store, analyzer, preferences, voiceRecordingDao, walkDao,
            routeDataSampleDao, walkPhotoDao, altitudeSampleDao,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val longText = "I was walking and I have to say I think about music because I can think about " +
        "music too and I will think about music again since I have so many things I want and need. " +
        "The river was wide and calm and I noticed the river every single time I walked beside the river " +
        "today, tracing its edge with my eyes."

    private suspend fun newWalk(startTimestamp: Long = 1_000L, intention: String? = null): Long =
        walkDao.insert(Walk(startTimestamp = startTimestamp, intention = intention))

    private suspend fun newRecording(
        uuid: String,
        walkId: Long,
        transcription: String?,
        wordsPerMinute: Double? = null,
        startTimestamp: Long = 1_000L,
    ): Long = voiceRecordingDao.insert(
        VoiceRecording(
            uuid = uuid,
            walkId = walkId,
            startTimestamp = startTimestamp,
            endTimestamp = startTimestamp + 1_000L,
            durationMillis = 1_000L,
            fileRelativePath = "recordings/$uuid.wav",
            transcription = transcription,
            wordsPerMinute = wordsPerMinute,
        ),
    )

    // --- toggle off ------------------------------------------------------------

    @Test
    fun `build returns null when the toggle is off`() = runTest {
        preferences.setThreadsAfterWalks(false)
        val walkId = newWalk()
        newRecording("r1", walkId, longText)

        assertNull(builder.build(walkId))
    }

    // --- nothing analyzed --------------------------------------------------------

    @Test
    fun `build returns null when the walk has no recordings`() = runTest {
        val walkId = newWalk()
        assertNull(builder.build(walkId))
    }

    @Test
    fun `build returns null for an unknown walk id`() = runTest {
        assertNull(builder.build(99_999L))
    }

    // --- basic build -------------------------------------------------------------

    @Test
    fun `build produces a dossier for a walk with a real transcribed recording`() = runTest {
        val walkId = newWalk(intention = "presence")
        newRecording("r1", walkId, longText, wordsPerMinute = 120.0)

        val block = builder.build(walkId)

        assertNotNull(block)
        assertTrue(block!!.text.startsWith("**Thought threads (on-device linguistic analysis):**"))
        assertEquals(listOf(block.text), block.render())
    }

    // --- memo: cache hit on identical key, miss when changeCount moves --------

    @Test
    fun `build memoizes — a second call with nothing changed does not re-read the store`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)

        val first = builder.build(walkId)
        val countAfterFirst = voiceRecordingDao.recordingWalkLiteIndexCallCount
        val second = builder.build(walkId)

        assertEquals("identical inputs must serve the memoized dossier text", first!!.text, second!!.text)
        assertEquals(
            "a cache hit must not re-run the recording-walk join a second time",
            countAfterFirst,
            voiceRecordingDao.recordingWalkLiteIndexCallCount,
        )
    }

    @Test
    fun `an external store write invalidates the memo for the next call`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)
        builder.build(walkId)
        val countAfterFirst = voiceRecordingDao.recordingWalkLiteIndexCallCount

        // Simulate a write from an entirely different actor (e.g. the
        // backfill sweep or another recording's analysis) landing BETWEEN
        // two build() calls — the postBuildKey's changeCount arithmetic
        // (preBuild + own writes only, never a fresh re-read) must leave
        // this invisible to the memo, so the NEXT call sees a live
        // changeCount it doesn't recognize and rebuilds rather than
        // silently serving a dossier that predates the external write.
        analyzer.analyzeAndStore("external-uuid", longText)

        builder.build(walkId)

        assertTrue(
            "an external change count bump must force a real rebuild, not a stale cache hit",
            voiceRecordingDao.recordingWalkLiteIndexCallCount > countAfterFirst,
        )
    }

    @Test
    fun `changing the walk's own intention invalidates the memo even with an unchanged changeCount`() = runTest {
        val walkId = newWalk(intention = "presence")
        newRecording("r1", walkId, longText)
        builder.build(walkId)
        val countAfterFirst = voiceRecordingDao.recordingWalkLiteIndexCallCount

        walkDao.updateIntention(walkId, "a different intention")

        builder.build(walkId)

        assertTrue(
            "MemoKey.intention must be part of the cache key, not just changeCount",
            voiceRecordingDao.recordingWalkLiteIndexCallCount > countAfterFirst,
        )
    }

    @Test
    fun `changing backfillComplete invalidates the memo`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)
        builder.build(walkId)
        val countAfterFirst = voiceRecordingDao.recordingWalkLiteIndexCallCount

        preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, preferences.importGeneration.value)

        builder.build(walkId)

        assertTrue(
            "MemoKey.backfillComplete must be part of the cache key",
            voiceRecordingDao.recordingWalkLiteIndexCallCount > countAfterFirst,
        )
    }

    // --- self-heal on hash-stale --------------------------------------------------

    @Test
    fun `an edited transcript is re-analyzed inline instead of serving the stale stored context`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, "river river river original words about the river and nothing else at all")
        // First build seeds a stored context matching the ORIGINAL text.
        builder.build(walkId)
        val originalHash = store.readRaw("r1")!!.transcriptHash

        // The transcript changes underneath the store (a manual edit that
        // did NOT go through the analyzer, and — deliberately — did not
        // bump store.changeCount at all, since it never touches the file
        // store) — the stored context is now stale-by-hash, not
        // stale-by-schema-version.
        voiceRecordingDao.updateTranscription(
            voiceRecordingDao.getForWalk(walkId).single().id,
            longText,
        )

        // A second call on the SAME builder instance would legitimately
        // memo-hit here (nothing in the six-field key moved) and never
        // reach the self-heal step at all — exactly like iOS's own memo,
        // which only gets a chance to notice a hash mismatch on a
        // genuine cache MISS. A fresh builder instance (same on-disk
        // store, no shared memo) isolates "does self-heal work" from
        // "does the memo invalidate correctly" (covered separately above).
        val freshBuilder = ThreadsDossierBuilder(
            store, analyzer, preferences, voiceRecordingDao, walkDao,
            routeDataSampleDao, walkPhotoDao, altitudeSampleDao,
        )
        val block = freshBuilder.build(walkId)

        val healedContext = store.readRaw("r1")!!
        assertTrue(
            "the self-heal must overwrite the stale hash with the current transcript's hash",
            healedContext.transcriptHash != originalHash,
        )
        assertEquals(TranscriptContext.hashTranscript(longText), healedContext.transcriptHash)
        assertNotNull(block)
    }

    // --- duplicate-uuid contexts fail loudly ---------------------------------------

    /**
     * [TranscriptContextStore]'s one-file-per-uuid naming makes a genuine
     * duplicate unreachable through the public store API (BEH-40's
     * `contextsByUUID` trap exists for a real iOS data-integrity scenario
     * that Android's file layout can't organically reproduce in a test).
     * The merge helper itself is tested directly instead — mirroring how
     * [ThreadStoreTest] tests the same invariant at its own layer.
     */
    @Test
    fun `mergeLiveAndFreshContexts fails loudly on a duplicate uuid instead of silently keeping the last`() {
        val a = TranscriptContext(
            uuid = "dup", languageCode = "en", wordCount = 10, themes = emptyList(),
            markers = TranscriptMarkers(10, 0, 0, 0, 0, 0, TemporalLean.PRESENT), transcriptHash = "hash-a",
        )
        val b = a.copy(transcriptHash = "hash-b")

        assertThrows(IllegalStateException::class.java) {
            mergeLiveAndFreshContexts(live = listOf(a, b), freshlySaved = emptyMap())
        }
    }

    @Test
    fun `mergeLiveAndFreshContexts lets a freshlySaved entry override its live counterpart`() {
        val live = TranscriptContext(
            uuid = "r1", languageCode = "en", wordCount = 10, themes = emptyList(),
            markers = TranscriptMarkers(10, 0, 0, 0, 0, 0, TemporalLean.PRESENT), transcriptHash = "stale",
        )
        val fresh = live.copy(transcriptHash = "fresh")

        val merged = mergeLiveAndFreshContexts(live = listOf(live), freshlySaved = mapOf("r1" to fresh))

        assertEquals("fresh", merged.single().transcriptHash)
    }

    @Test
    fun `mergeLiveAndFreshContexts sorts by uuid ascending for determinism`() {
        val zebra = TranscriptContext(
            uuid = "zebra", languageCode = "en", wordCount = 10, themes = emptyList(),
            markers = TranscriptMarkers(10, 0, 0, 0, 0, 0, TemporalLean.PRESENT), transcriptHash = "h",
        )
        val apple = zebra.copy(uuid = "apple")

        val merged = mergeLiveAndFreshContexts(live = listOf(zebra, apple), freshlySaved = emptyMap())

        assertEquals(listOf("apple", "zebra"), merged.map { it.uuid })
    }

    // --- orphan guard: both branches ------------------------------------------------

    @Test
    fun `orphan guard prunes a stale context whose recording no longer exists`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)
        builder.build(walkId)
        assertTrue(store.hasContext("r1"))

        // A leftover context with no owning recording at all (e.g. from a
        // deleted recording that bypassed the normal delete path).
        analyzer.analyzeAndStore("orphan-uuid", longText)
        assertTrue(store.hasContext("orphan-uuid"))

        builder.build(walkId)

        assertTrue(
            "a true orphan (no walk-index entry anywhere) must be pruned by the next build",
            !store.hasContext("orphan-uuid"),
        )
        assertTrue("the live context for the current walk must survive pruning", store.hasContext("r1"))
    }

    @Test
    fun `an empty recording-walk index is treated as a failed read, never proof of universal orphanhood`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)
        analyzer.analyzeAndStore("orphan-uuid", longText)

        val lyingDao = object : VoiceRecordingDao by voiceRecordingDao {
            override suspend fun recordingWalkLiteIndex(): List<RecordingWalkLiteRow> = emptyList()
        }
        val guardedBuilder = ThreadsDossierBuilder(
            store, analyzer, preferences, lyingDao, walkDao,
            routeDataSampleDao, walkPhotoDao, altitudeSampleDao,
        )

        guardedBuilder.build(walkId)

        assertTrue(
            "an empty index must not be treated as proof every stored context is orphaned",
            store.hasContext("orphan-uuid"),
        )
        assertTrue(store.hasContext("r1"))
    }

    // --- U9: senses assembly ---------------------------------------------------

    @Test
    fun `the Noticed block is absent entirely when zero senses fire`() = runTest {
        // A short, sane walk span (both endpoints explicit) so
        // speechShape's "wordless remainder" gate — which a bare
        // startTimestamp-only walk would trivially satisfy via the
        // now-fallback's huge implied span — stays correctly silent.
        val walkStart = 1_000L
        val walkId = walkDao.insert(Walk(startTimestamp = walkStart, endTimestamp = walkStart + 60_000L))
        newRecording("r1", walkId, longText, startTimestamp = walkStart)
        val now = java.time.Instant.ofEpochMilli(walkStart + 60_000L)
        // Pre-mark the current lunation as already reported so moonLine's
        // own once-per-lunation precondition suppresses it too — this
        // test isolates "every sense stays silent on a plain, sparse
        // walk", not moonLine's own separately-tested firing rules.
        preferences.setMoonLineLastLunationIndex(LunationCalendar.mostRecentClosed(asOf = now).index)

        val block = builder.build(walkId, now = now)

        assertTrue(!block!!.text.contains("**Noticed:**"))
    }

    @Test
    fun `the Noticed block appears with a sense's line when one fires`() = runTest {
        // speechShape needs no threads/backfill/moon setup: all the
        // walk's words land in the first third, and the wordless
        // remainder clears 30 minutes.
        val walkStart = 0L
        val walkId = walkDao.insert(Walk(startTimestamp = walkStart, endTimestamp = walkStart + 3600_000L))
        newRecording("r1", walkId, longText, startTimestamp = walkStart)

        val block = builder.build(walkId, now = java.time.Instant.ofEpochMilli(walkStart + 3600_000L))

        assertTrue(block!!.text.contains("\n\n**Noticed:**\n"))
        assertTrue(block.text.contains("All the words came in the first third"))
    }

    @Test
    fun `a photo with no captured coordinates never participates — nullable, not a sentinel`() = runTest {
        // Android's WalkPhoto stores nullable lat/lng directly; unlike
        // iOS there is no (-1,-1) sentinel to translate. A photo with
        // both null must behave exactly like "no photo at all", never
        // like a plottable coordinate near null island.
        val walkStart = 0L
        val walkId = walkDao.insert(Walk(startTimestamp = walkStart, endTimestamp = walkStart + 3600_000L))
        newRecording("r1", walkId, longText, startTimestamp = walkStart)
        walkPhotoDao.insert(
            org.walktalkmeditate.pilgrim.data.entity.WalkPhoto(
                walkId = walkId,
                photoUri = "content://fake",
                pinnedAt = 1L,
                capturedLat = null,
                capturedLng = null,
            ),
        )

        // Must not throw, and photoAdjacency (needing a coordinate) must
        // not be the reason speechShape's line is missing — both may
        // coexist; the assertion is simply "the build completes cleanly".
        val block = builder.build(walkId, now = java.time.Instant.ofEpochMilli(walkStart + 3600_000L))
        assertNotNull(block)
    }

    @Test
    fun `an external write to the moon-line preference invalidates the memo even with an unchanged changeCount`() = runTest {
        val walkId = newWalk()
        newRecording("r1", walkId, longText)
        builder.build(walkId)
        val countAfterFirst = voiceRecordingDao.recordingWalkLiteIndexCallCount

        // Simulate a write from an entirely different build/actor landing
        // between two calls — MemoKey.moonState must be part of the key,
        // not just changeCount.
        preferences.setMoonLineLastLunationIndex(3)

        builder.build(walkId)

        assertTrue(
            "MemoKey.moonState must be part of the cache key",
            voiceRecordingDao.recordingWalkLiteIndexCallCount > countAfterFirst,
        )
    }

    @Test
    fun `moonLine reaches back beyond the 30-day recurrence window via the closed-lunation union`() = runTest {
        // Pick a lunation far from the epoch's own edge cases, then place
        // "now" comfortably inside the NEXT lunation (not right at the
        // boundary) so the closed lunation reaches back with a healthy
        // margin past the 30-day mark — verified by the check() below
        // rather than assumed.
        val targetLunation = LunationCalendar.lunation(600)
        val now = targetLunation.end.plus(15, java.time.temporal.ChronoUnit.DAYS)
        check(LunationCalendar.mostRecentClosed(asOf = now).index == targetLunation.index) {
            "test setup assumption broken: now must resolve mostRecentClosed to targetLunation"
        }
        val walkStart = now.minusSeconds(3_600)
        check(targetLunation.start.isBefore(walkStart.minus(ThreadStore.RECURRENCE_WINDOW))) {
            "test setup assumption broken: targetLunation must start more than 30 days before walkStart"
        }

        val walkId = walkDao.insert(Walk(startTimestamp = walkStart.toEpochMilli()))
        newRecording("current", walkId, longText, startTimestamp = walkStart.toEpochMilli())

        // A walk with a worded recording INSIDE the closed lunation but
        // OUTSIDE the raw 30-day window — reachable only via the union.
        // Its context must be explicitly analyzed/stored: `build()` only
        // auto-resolves contexts for the CURRENT walk's own recordings
        // (`resolveCurrentContexts`) — a different walk's context has to
        // already exist in the store, exactly as a prior build (or the
        // backfill sweep) would have left it.
        val wordedWalkStart = targetLunation.start.plusSeconds(3_600)
        val wordedWalkId = walkDao.insert(Walk(startTimestamp = wordedWalkStart.toEpochMilli()))
        newRecording("worded-in-lunation", wordedWalkId, longText, startTimestamp = wordedWalkStart.toEpochMilli())
        analyzer.analyzeAndStore("worded-in-lunation", longText)

        val block = builder.build(walkId, now = now)

        assertTrue(block!!.text.contains("has set: 1 walk, 1 with recorded words"))
    }
}
