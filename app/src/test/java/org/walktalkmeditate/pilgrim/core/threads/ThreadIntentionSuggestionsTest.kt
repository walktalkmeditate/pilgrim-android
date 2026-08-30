// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase

/**
 * [ThreadIntentionSuggestions] against a REAL [TranscriptContextStore]
 * (Robolectric filesDir) — parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md` BEH-49..52/UI-28..31/
 * EDG-79..81. The recording→walk join is supplied directly via the
 * [ThreadIntentionSuggestions.current] `walkIndex` test seam (mirroring
 * iOS's own `walkIndex` parameter) so most tests never touch the DAO at
 * all; one test exercises the production default (empty DB → empty
 * walk index → `emptyList()`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadIntentionSuggestionsTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var store: TranscriptContextStore
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var engine: ThreadIntentionSuggestions

    private val now = Instant.parse("2026-08-25T12:00:00Z")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        preferences = FakeThreadsPreferencesRepository()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = ThreadIntentionSuggestions(store, preferences, db.voiceRecordingDao())
    }

    @After
    fun tearDown() {
        db.close()
        ThreadIntentionSuggestions.PENDING_FIELD_GATE = false
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
    }

    // ---- fixtures --------------------------------------------------------

    private fun walkLite(walkId: Long, daysAgo: Long) =
        WalkLite(walkId = walkId, startedAt = now.minus(daysAgo, ChronoUnit.DAYS), intention = null, weatherCondition = null)

    private fun theme(lemma: String, displayTerm: String) =
        Theme(lemma = lemma, displayTerm = displayTerm, mentionCount = 2, salience = 0.2, mentions = emptyList())

    private fun markers() = TranscriptMarkers(
        wordCount = 30,
        absolutistCount = 0,
        firstPersonCount = 0,
        insightCount = 0,
        causationCount = 0,
        discrepancyCount = 0,
        temporalLean = TemporalLean.PRESENT,
    )

    private fun context(uuid: String, vararg themes: Theme) = TranscriptContext(
        uuid = uuid,
        languageCode = "en",
        wordCount = 30,
        themes = themes.toList(),
        markers = markers(),
        transcriptHash = "hash-$uuid",
        // Explicit, as at the production write site: the property defaults
        // to the stale sentinel, so an omission here would seed history
        // this engine correctly refuses to read.
        analysisVersion = TranscriptContext.ANALYSIS_VERSION,
    )

    /** Saves [uuid] -> [theme] on its own walk [daysAgo] days before [now], returns the (uuid, WalkLite) pair. */
    private suspend fun seed(uuid: String, walkId: Long, daysAgo: Long, vararg themes: Theme): Pair<String, WalkLite> {
        assertTrue(store.save(context(uuid, *themes)))
        return uuid to walkLite(walkId, daysAgo)
    }

    // ---- pipeline ----------------------------------------------------------

    @Test
    fun `two distinct walks in 30 days qualify a lemma`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 5L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 3L, theme("river", "river")).let { index += it }

        assertEquals(listOf("walk with 'river'"), engine.current(now, index))
    }

    @Test
    fun `a single walk never qualifies`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 5L, theme("river", "river")).let { index += it }

        assertEquals(emptyList<String>(), engine.current(now, index))
    }

    @Test
    fun `an appearance older than 30 days does not count toward the window`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 5L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 45L, theme("river", "river")).let { index += it }

        assertEquals(emptyList<String>(), engine.current(now, index))
    }

    @Test
    fun `rank by distinct-walk count descending then term ascending`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        // "river": 2 distinct walks; "hill": 3 distinct walks -> hill ranks first.
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        seed("h1", 3L, 1L, theme("hill", "hill")).let { index += it }
        seed("h2", 4L, 2L, theme("hill", "hill")).let { index += it }
        seed("h3", 5L, 3L, theme("hill", "hill")).let { index += it }

        assertEquals(listOf("walk with 'hill'", "walk with 'river'"), engine.current(now, index))
    }

    @Test
    fun `tie in count breaks lexicographically ascending by term`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("z1", 1L, 1L, theme("zephyr", "zephyr")).let { index += it }
        seed("z2", 2L, 2L, theme("zephyr", "zephyr")).let { index += it }
        seed("a1", 3L, 1L, theme("acorn", "acorn")).let { index += it }
        seed("a2", 4L, 2L, theme("acorn", "acorn")).let { index += it }

        assertEquals(listOf("walk with 'acorn'", "walk with 'zephyr'"), engine.current(now, index))
    }

    @Test
    fun `pipeline order is sort then template then dedup then cap`() = runTest {
        // "move" and "moving" are different lemmas that both render the
        // SAME phrase via a shared displayTerm ("the move") — the classic
        // move-moving cohort collision (BEH-50/EDG-80). Both out-rank
        // "river" on count (3 vs 2 distinct walks). If cap(2) ran BEFORE
        // dedup, the result would collapse to a single "the move" chip
        // and "river" would never be offered; the correct order
        // (dedup-then-cap) fills the second slot with the next DISTINCT
        // phrase instead.
        val index = mutableMapOf<String, WalkLite>()
        seed("m1", 1L, 1L, theme("move", "the move")).let { index += it }
        seed("m2", 2L, 2L, theme("move", "the move")).let { index += it }
        seed("m3", 3L, 3L, theme("move", "the move")).let { index += it }
        seed("g1", 4L, 1L, theme("moving", "the move")).let { index += it }
        seed("g2", 5L, 2L, theme("moving", "the move")).let { index += it }
        seed("g3", 6L, 3L, theme("moving", "the move")).let { index += it }
        seed("r1", 7L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 8L, 2L, theme("river", "river")).let { index += it }

        assertEquals(listOf("walk with 'the move'", "walk with 'river'"), engine.current(now, index))
    }

    @Test
    fun `caps at two suggestions`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        seed("h1", 3L, 1L, theme("hill", "hill")).let { index += it }
        seed("h2", 4L, 2L, theme("hill", "hill")).let { index += it }
        seed("f1", 5L, 1L, theme("field", "field")).let { index += it }
        seed("f2", 6L, 2L, theme("field", "field")).let { index += it }

        assertEquals(2, engine.current(now, index).size)
    }

    // ---- guards -------------------------------------------------------------

    @Test
    fun `toggle off returns empty regardless of data`() = runTest {
        preferences.setThreadsAfterWalks(false)
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }

        assertEquals(emptyList<String>(), engine.current(now, index))
    }

    @Test
    fun `kill switch flip returns empty even with qualifying data`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        ThreadIntentionSuggestions.PENDING_FIELD_GATE = true

        assertEquals(emptyList<String>(), engine.current(now, index))
    }

    @Test
    fun `empty walk index from the production DAO default returns empty`() = runTest {
        // No override supplied: reads the live (empty) Room index.
        assertEquals(emptyList<String>(), engine.current(now))
    }

    // ---- memo ---------------------------------------------------------------

    @Test
    fun `same day and no writes serves the cached result`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        val first = engine.current(now, index)

        // A DIFFERENT walkIndex that would recompute to empty (only one
        // walk survives) still returns the FIRST call's cached result,
        // because changeCount + day are unchanged since call 1.
        val shrunk = mapOf(index.entries.first().toPair())
        val second = engine.current(now, shrunk)

        assertEquals(first, second)
        assertEquals(listOf("walk with 'river'"), second)
    }

    @Test
    fun `a store write invalidates the memo`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        val first = engine.current(now, index)
        assertEquals(listOf("walk with 'river'"), first)

        // changeCount-captured-before-load semantics: a write landing
        // after call 1 returned (and thus after its own preLoadChangeCount
        // was captured) must leave call 1's memo entry stale for call 2 —
        // never silently absorbed as if call 1 had already accounted for it.
        store.save(context("r3", theme("river", "river")))

        val shrunk = mapOf(index.entries.first().toPair())
        val second = engine.current(now, shrunk)

        assertEquals(emptyList<String>(), second)
    }

    @Test
    fun `day rollover invalidates the memo independent of changeCount`() = runTest {
        val index = mutableMapOf<String, WalkLite>()
        seed("r1", 1L, 1L, theme("river", "river")).let { index += it }
        seed("r2", 2L, 2L, theme("river", "river")).let { index += it }
        val first = engine.current(now, index)
        assertEquals(listOf("walk with 'river'"), first)

        val shrunk = mapOf(index.entries.first().toPair())
        val tomorrow = now.plus(1, ChronoUnit.DAYS)
        val second = engine.current(tomorrow, shrunk)

        assertEquals(emptyList<String>(), second)
    }
}
