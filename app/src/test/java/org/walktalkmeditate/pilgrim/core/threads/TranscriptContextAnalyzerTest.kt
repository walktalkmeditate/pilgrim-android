// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient

/**
 * [TranscriptContextAnalyzer.analyzeAndStore] against a REAL
 * [TranscriptContextStore] (Robolectric filesDir) and a REAL
 * [ThreadsAnalysisEnvironment] (real WordNet + VADER assets) — the
 * production wiring end to end, with only the language client and the
 * toggle faked for determinism.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranscriptContextAnalyzerTest {

    private lateinit var store: TranscriptContextStore
    private lateinit var environment: ThreadsAnalysisEnvironment
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private var languageGuess: LanguageGuess = LanguageGuess("en", 0.99f)
    private lateinit var analyzer: TranscriptContextAnalyzer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
        preferences = FakeThreadsPreferencesRepository()
        val languageClient = MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(languageGuess)
            },
        )
        analyzer = TranscriptContextAnalyzer(store, environment, languageClient, preferences)
    }

    private val musicText =
        "I was walking and I have to say I think about music because I can think about " +
            "music too and I will think about music again since I have so many things I want and need"

    private fun rangeOfOccurrence(text: String, word: String, occurrenceIndex: Int): IntRange {
        var idx = -1
        repeat(occurrenceIndex + 1) { idx = text.indexOf(word, idx + 1) }
        check(idx >= 0) { "occurrence $occurrenceIndex of \"$word\" not found in fixture" }
        return idx until (idx + word.length)
    }

    // ---- toggle-off → no write ----

    @Test
    fun `toggle off returns null and writes nothing`() = runTest {
        preferences.setThreadsAfterWalks(false)

        val result = analyzer.analyzeAndStore("u1", musicText)

        assertNull(result)
        assertFalse(store.hasContext("u1"))
    }

    // ---- non-English → null + no write ----

    @Test
    fun `non-English detected language returns null and writes nothing`() = runTest {
        languageGuess = LanguageGuess("ja", 0.99f)

        val result = analyzer.analyzeAndStore("u1", musicText)

        assertNull(result)
        assertFalse(store.hasContext("u1"))
    }

    @Test
    fun `undetectable language (low confidence) returns null and writes nothing`() = runTest {
        languageGuess = LanguageGuess("en", 0.1f)

        val result = analyzer.analyzeAndStore("u1", musicText)

        assertNull(result)
        assertFalse(store.hasContext("u1"))
    }

    @Test
    fun `detectLanguage surfaces the language even when analyzeAndStore would write nothing`() = runTest {
        languageGuess = LanguageGuess("ja", 0.99f)

        assertEquals("ja", analyzer.detectLanguage(musicText))
        assertNull(analyzer.analyzeAndStore("u1", musicText))
    }

    // ---- sub-25-word → written, markers present, zero themes ----

    @Test
    fun `sub-25-word English transcript is written with markers but zero themes`() = runTest {
        val shortText = "I was thinking about the walk today and it felt nice"
        assertTrue(TranscriptNlp.wordCount(shortText) < ThemeExtractor.MINIMUM_WORDS)

        val result = analyzer.analyzeAndStore("u1", shortText)

        assertTrue(result != null)
        assertEquals(emptyList<Theme>(), result!!.themes)
        assertEquals(TranscriptNlp.wordCount(shortText), result.markers.wordCount)
        assertTrue(store.hasContext("u1"))
    }

    @Test
    fun `a written context is stamped with the current analysis version, never the sentinel`() = runTest {
        analyzer.analyzeAndStore("u1", musicText)

        // hasCurrentContext is the discriminant the backfill sweeps on: an
        // unstamped write would leave every recording permanently stale and
        // the sweep permanently incomplete.
        assertTrue(store.hasCurrentContext("u1"))
        assertEquals(TranscriptContext.ANALYSIS_VERSION, store.readRaw("u1")!!.analysisVersion)
    }

    // ---- flagged-range theme survival polarity ----

    @Test
    fun `a theme survives when at least one mention falls outside every flagged range`() = runTest {
        // "music" mentioned 3 times; flag only the first two occurrences.
        val flagged = listOf(
            rangeOfOccurrence(musicText, "music", 0),
            rangeOfOccurrence(musicText, "music", 1),
        )

        val result = analyzer.analyzeAndStore("u1", musicText, flagged)

        assertEquals(listOf("music"), result!!.themes.map { it.lemma })
    }

    @Test
    fun `a theme is dropped when every mention falls inside flagged ranges`() = runTest {
        val flagged = listOf(
            rangeOfOccurrence(musicText, "music", 0),
            rangeOfOccurrence(musicText, "music", 1),
            rangeOfOccurrence(musicText, "music", 2),
        )

        val result = analyzer.analyzeAndStore("u1", musicText, flagged)

        assertEquals(
            "every mention flagged — the theme must not survive on flagged-only evidence",
            emptyList<Theme>(),
            result!!.themes,
        )
    }

    @Test
    fun `no flagged ranges keeps every theme that would otherwise qualify`() = runTest {
        val result = analyzer.analyzeAndStore("u1", musicText, emptyList())

        assertEquals(listOf("music"), result!!.themes.map { it.lemma })
    }

    // ---- global replace for markers text (EDG-49) ----

    @Test
    fun `marker scrubbing removes every occurrence of the flagged fragment, not just the flagged one`() = runTest {
        // "should" is a discrepancyCount lexicon word (MarkerLexicons.discrepancy),
        // a field TranscriptMarkers actually stores (unlike the future/past
        // tallies, which only feed the derived temporalLean).
        val text = "should you help me with this today. " +
            "apple banana cherry date fig grape kiwi lemon mango orange peach quince fruit basket collection. " +
            "should you help me again"
        val firstShouldRange = rangeOfOccurrence(text, "should", 0)
        check(text.substring(firstShouldRange.first, firstShouldRange.last + 1) == "should")

        val unflagged = analyzer.analyzeAndStore("baseline", text, emptyList())
        val flaggedOnlyFirst = analyzer.analyzeAndStore("scrubbed", text, listOf(firstShouldRange))

        assertEquals("both unflagged 'should's count toward discrepancyCount", 2, unflagged!!.markers.discrepancyCount)
        assertEquals(
            "a GLOBAL replace must also scrub the second, un-flagged 'should' " +
                "— a range-based excision would leave discrepancyCount at 1",
            0,
            flaggedOnlyFirst!!.markers.discrepancyCount,
        )
    }

    // ---- every-occurrence matching (the flaggedRanges companion helper) ----

    @Test
    fun `flaggedRanges finds every occurrence of a repeated fragment, not just the first`() {
        val text = "echo echo the mountain said echo back to us and echo again"

        val ranges = TranscriptContextAnalyzer.flaggedRanges(text, listOf("echo"))

        assertEquals(4, ranges.size)
        for (range in ranges) {
            assertEquals("echo", text.substring(range.first, range.last + 1))
        }
    }

    @Test
    fun `flaggedRanges returns non-overlapping successive occurrences for adjacent repeats`() {
        val text = "echoecho"

        val ranges = TranscriptContextAnalyzer.flaggedRanges(text, listOf("echo"))

        assertEquals(listOf(0..3, 4..7), ranges)
    }

    // ---- analyzeOrForget: the shared manual-edit / retranscribe-clear path (BEH-59 carry) ----

    @Test
    fun `analyzeOrForget with the toggle on and real text eagerly analyzes with no flagged fragments`() = runTest {
        analyzer.analyzeOrForget("u1", musicText)

        val stored = store.readRaw("u1")
        assertTrue("a hand-edited transcript is trusted verbatim, themes included", stored!!.themes.isNotEmpty())
        assertEquals(TranscriptContext.hashTranscript(musicText), stored.transcriptHash)
    }

    @Test
    fun `analyzeOrForget with the toggle off removes any existing context without a tombstone`() = runTest {
        analyzer.analyzeAndStore("u1", musicText)
        assertTrue(store.hasContext("u1"))

        preferences.setThreadsAfterWalks(false)
        analyzer.analyzeOrForget("u1", "an edit made while the feature is off")

        assertFalse("toggle-off must remove the stale context, not analyze the new text", store.hasContext("u1"))

        // No tombstone: re-enabling and re-analyzing the SAME uuid must
        // succeed afterward (a tombstone would silently block it — BEH-20).
        preferences.setThreadsAfterWalks(true)
        val healed = analyzer.analyzeAndStore("u1", musicText)
        assertTrue("removeContext must not tombstone this uuid", healed != null && store.hasContext("u1"))
    }

    @Test
    fun `analyzeOrForget with a null or blank transcription removes the stale context regardless of toggle`() = runTest {
        analyzer.analyzeAndStore("u1", musicText)
        assertTrue(store.hasContext("u1"))

        analyzer.analyzeOrForget("u1", null)

        assertFalse(
            "a null/blank transcription has nothing worth analyzing — clean up instead of leaving it stale",
            store.hasContext("u1"),
        )
    }

    @Test
    fun `analyzeOrForget with a blank transcription and toggle on still removes rather than analyzes`() = runTest {
        analyzer.analyzeAndStore("u1", musicText)

        analyzer.analyzeOrForget("u1", "   ")

        assertFalse(store.hasContext("u1"))
    }
}
