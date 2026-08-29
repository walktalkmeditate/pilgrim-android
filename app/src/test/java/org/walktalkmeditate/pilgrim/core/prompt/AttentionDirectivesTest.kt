// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.prompt.voices.ReflectiveVoice
import org.walktalkmeditate.pilgrim.core.threads.SpokenStoplist
import org.walktalkmeditate.pilgrim.core.threads.TranscriptNlp
import org.walktalkmeditate.pilgrim.core.threads.WordNetLexicon

/**
 * The assembler injects a dossier of context; attention directives turn
 * it into pursuit — deterministic pattern detection that tells the
 * downstream model what is remarkable about *this* walk. Each detector
 * must fire only when its pattern is genuinely present. Mirrors iOS
 * `AttentionDirectivesTests.swift@0172e2b` (v2, lemma-based — parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md` BEH-71..73). Robolectric
 * + a real installed [WordNetLexicon] are required from v2 on: every
 * detector call with non-empty recordings routes through
 * [TranscriptNlp.contentLemmaMentions], which throws if no lexicon has
 * been installed for the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttentionDirectivesTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        TranscriptNlp.install(WordNetLexicon(context, json))
    }

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private val start: Long = LocalDateTime.of(2024, 6, 15, 9, 0)
        .atZone(nyZone)
        .toInstant()
        .toEpochMilli()

    private fun recording(
        text: String,
        offsetSeconds: Long = 300L,
        wordsPerMinute: Double? = null,
    ): RecordingContext =
        RecordingContext(
            uuid = "r-$offsetSeconds",
            timestamp = start + offsetSeconds * 1000L,
            startCoordinate = null,
            endCoordinate = null,
            wordsPerMinute = wordsPerMinute,
            text = text,
        )

    /** [count] filler tokens that are never WordNet content words — clears
     * [TranscriptNlp.wordCount] floors without adding a single subject lemma. */
    private fun paddedWords(count: Int): String = List(count) { "and" }.joinToString(separator = " ")

    // Distinct WordNet-noun-listed base forms (verified against the committed
    // asset), none in SpokenStoplist.scaffoldLemmas — each contributes exactly
    // one subject lemma, so set sizes below are exact and auditable.
    private val openingSubjectWords = listOf(
        "river", "bridge", "mountain", "music", "harvest", "temple",
        "candle", "lantern", "forest", "garden", "stone", "water",
    )

    private val closingSubjectWords = listOf(
        "winter", "valley", "meadow", "shadow", "silence", "prayer",
        "breath", "autumn", "ember", "orchard", "harbor", "lake",
    )

    private val extraSubjectWords = listOf(
        "cloud", "island", "desert", "castle", "village", "bell", "rain", "snow",
        "wind", "fire", "star", "moon", "tree", "bird", "fish", "horse", "tower",
        "gate", "wall", "roof", "door", "window", "cellar", "chimney",
    )

    private fun subjectLemmas(text: String): Set<String> =
        TranscriptNlp.contentLemmaMentions(text)
            .map { it.lemma }
            .toSet()
            .minus(SpokenStoplist.scaffoldLemmas)

    private fun subjectLemmaCount(text: String): Int = subjectLemmas(text).size

    private fun sharedLemmaCount(first: String, second: String): Int =
        subjectLemmas(first).intersect(subjectLemmas(second)).size

    private fun context(
        recordings: List<RecordingContext> = emptyList(),
        meditations: List<MeditationContext> = emptyList(),
        durationSeconds: Long = 1800L,
        routeSpeeds: List<Double> = emptyList(),
        intention: String? = null,
        pauses: List<PauseContext> = emptyList(),
    ): ActivityContext = ActivityContext(
        recordings = recordings,
        meditations = meditations,
        durationSeconds = durationSeconds,
        distanceMeters = 2_000.0,
        startTimestamp = start,
        placeNames = emptyList(),
        routeSpeeds = routeSpeeds,
        recentWalkSnippets = emptyList(),
        intention = intention,
        waypoints = emptyList(),
        weather = null,
        lunarPhase = MoonPhase(name = "First Quarter", illumination = 0.5, ageInDays = 7.4),
        celestial = null,
        photoContexts = emptyList(),
        narrativeArc = null,
        mode = PracticeMode.Wander,
        seekStory = null,
        pauses = pauses,
        ascentMeters = null,
        descentMeters = null,
    )

    private fun joined(context: ActivityContext): String =
        AttentionDirectives.detect(context).joinToString(separator = "\n")

    // --- Pace shift ----------------------------------------------------------

    @Test
    fun `pace shift slowing final third fires`() {
        val speeds = List(20) { 1.5 } + List(20) { 1.2 } + List(20) { 0.9 }
        val directives = joined(context(routeSpeeds = speeds))
        assertTrue(
            "exact slow phrasing: $directives",
            directives.contains(
                "The walker's pace slowed by 40% in the final third — something slowed them; notice what.",
            ),
        )
    }

    @Test
    fun `pace shift quickening final third fires`() {
        val speeds = List(20) { 0.9 } + List(20) { 1.2 } + List(20) { 1.5 }
        val directives = joined(context(routeSpeeds = speeds))
        assertTrue(
            "exact quicken phrasing: $directives",
            directives.contains(
                "The walker's pace quickened by 67% in the final third — something carried them; notice what.",
            ),
        )
    }

    @Test
    fun `pace shift uniform pace does not fire`() {
        val directives = joined(context(routeSpeeds = List(60) { 1.4 }))
        assertFalse("no slowed: $directives", directives.contains("slowed"))
        assertFalse("no quickened: $directives", directives.contains("quickened"))
    }

    // --- Stillness -----------------------------------------------------------

    @Test
    fun `stillness long still run without meditation fires`() {
        val speeds = List(40) { 1.4 } + List(20) { 0.0 } + List(40) { 1.4 }
        val directives = joined(context(durationSeconds = 3600L, routeSpeeds = speeds))
        assertTrue(
            "exact stillness phrasing: $directives",
            directives.contains(
                "The route shows about 12 minutes of stillness in one place — ask what held the walker there.",
            ),
        )
    }

    @Test
    fun `stillness covered by meditation does not fire`() {
        val speeds = List(40) { 1.4 } + List(20) { 0.0 } + List(40) { 1.4 }
        val meditation = MeditationContext(
            startDate = start + 600_000L,
            endDate = start + 1_500_000L,
            durationSeconds = 900L,
        )
        val directives = joined(
            context(meditations = listOf(meditation), durationSeconds = 3600L, routeSpeeds = speeds),
        )
        assertFalse(
            "stillness explained by a logged meditation is not news: $directives",
            directives.contains("stillness"),
        )
    }

    @Test
    fun `stillness covered by recorded pause does not fire`() {
        val speeds = List(40) { 1.4 } + List(20) { 0.0 } + List(40) { 1.4 }
        val pause = PauseContext(startDate = start + 600_000L, durationSeconds = 900L)
        val directives = joined(
            context(durationSeconds = 3600L, routeSpeeds = speeds, pauses = listOf(pause)),
        )
        assertFalse(
            "stillness explained by a recorded pause is not news — the Pauses line already tells it: $directives",
            directives.contains("stillness"),
        )
    }

    @Test
    fun `stillness invalid negative speeds do not count as stillness`() {
        val speeds = List(40) { 1.4 } + List(20) { -1.0 } + List(40) { 1.4 }
        val directives = joined(context(durationSeconds = 3600L, routeSpeeds = speeds))
        assertFalse(
            "negative speeds are invalid GPS fixes, not a still walker: $directives",
            directives.contains("stillness"),
        )
    }

    // --- Intention echo ------------------------------------------------------

    @Test
    fun `intention echo intention word spoken fires`() {
        val directives = joined(
            context(
                recordings = listOf(recording("I keep coming back to release, letting the grip soften")),
                intention = "Release what I cannot carry",
            ),
        )
        assertTrue(
            "exact echo phrasing: $directives",
            directives.contains(
                "The walker's intention spoke of 'release', and 'release' surfaces again " +
                    "in their spoken words — trace how it traveled.",
            ),
        )
    }

    @Test
    fun `intention echo no overlap does not fire`() {
        val directives = joined(
            context(
                recordings = listOf(recording("The bakery smelled wonderful this morning")),
                intention = "Release what I cannot carry",
            ),
        )
        assertFalse("no echo: $directives", directives.contains("surfaces again"))
    }

    @Test
    fun `intention echo lemma match (an inflection, not the exact surface) omits again`() {
        // "grieving" -> lemma "grieve" (confirmed lemmatization, TranscriptNlpTest);
        // spoken uses the bare "grieve" surface — same lemma, different surface.
        val directives = joined(
            context(
                recordings = listOf(recording("I grieve every single day now")),
                intention = "grieving quietly by the water",
            ),
        )
        assertTrue(
            "lemma-tier phrasing without again: $directives",
            directives.contains(
                "The walker's intention spoke of 'grieving', and 'grieve' surfaces " +
                    "in their spoken words — trace how it traveled.",
            ),
        )
        assertFalse("lemma tier must never say again: $directives", directives.contains("surfaces again"))
    }

    @Test
    fun `intention echo related-word match (shared WordNet synset) omits again`() {
        // "grief" and "sorrow" share a synset (confirmed, TranscriptNlpTest)
        // but are neither the same surface nor the same lemma.
        val directives = joined(
            context(
                recordings = listOf(recording("There is so much sorrow in this quiet place")),
                intention = "processing my grief",
            ),
        )
        assertTrue(
            "related-tier phrasing without again: $directives",
            directives.contains(
                "The walker's intention spoke of 'grief', and 'sorrow' surfaces " +
                    "in their spoken words — trace how it traveled.",
            ),
        )
        assertFalse("related tier must never say again: $directives", directives.contains("surfaces again"))
    }

    // --- Recurring word ------------------------------------------------------

    @Test
    fun `recurring word returning three times fires`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording("The river was high today"),
                    recording("I crossed the river at the old bridge", offsetSeconds = 900L),
                    recording("Something about the river keeps pulling me", offsetSeconds = 1500L),
                ),
            ),
        )
        assertTrue(
            "exact recurring phrasing: $directives",
            directives.contains(
                "The word 'river' returns 3 times across the recordings — it may be doing quiet work.",
            ),
        )
    }

    @Test
    fun `recurring word all words unique does not fire`() {
        val directives = joined(
            context(recordings = listOf(recording("Cold wind moving between bare branches"))),
        )
        assertFalse("no recurring: $directives", directives.contains("returns"))
    }

    @Test
    fun `recurring word count tie breaks to alphabetically first`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording("stone water stone water"),
                    recording("stone water stone water", offsetSeconds = 900L),
                ),
            ),
        )
        assertTrue(
            "tie resolves to 'stone': $directives",
            directives.contains("The word 'stone' returns 4 times"),
        )
    }

    @Test
    fun `recurring word excludes the spoken-scaffold lemma, promoting the next real candidate`() {
        // "think" appears 4 times (well above the floor of 3) but is a
        // SpokenStoplist.scaffoldLemmas entry; excluding it must redirect
        // to "river" (3 times), never silence the directive entirely.
        val directives = joined(
            context(
                recordings = listOf(
                    recording("I think the river is peaceful today"),
                    recording("I think the river moves slowly now", offsetSeconds = 900L),
                    recording("I think the river remembers everything", offsetSeconds = 1_800L),
                    recording("I think winter is coming soon", offsetSeconds = 2_700L),
                ),
            ),
        )
        assertFalse("scaffold lemma 'think' must never win: $directives", directives.contains("'think'"))
        assertTrue(
            "excluding a scaffold lemma redirects to the next candidate, never silences: $directives",
            directives.contains("The word 'river' returns 3 times across the recordings — it may be doing quiet work."),
        )
    }

    @Test
    fun `recurring word tuple-swap tie-break applies twice — winning lemma, then its display surface`() {
        // "moved"x2 + "moving"x2 share lemma "move" (count 4, above the
        // floor) — first tuple-swap picks "move" as the winning lemma;
        // second tuple-swap breaks the moved/moving surface tie to the
        // alphabetically smaller "moved".
        val directives = joined(
            context(
                recordings = listOf(
                    recording("I moved forward and moved again"),
                    recording("The light kept moving and moving", offsetSeconds = 900L),
                ),
            ),
        )
        assertTrue(
            "surface tie-break picks the alphabetically smaller 'moved': $directives",
            directives.contains("The word 'moved' returns 4 times across the recordings — it may be doing quiet work."),
        )
    }

    // --- First vs last recording (fires only on a measured shift) ------------

    @Test
    fun `first versus last two short recordings with nothing measurable contributes nothing`() {
        // The old version fired unconditionally on every two-recording walk,
        // presupposing its own conclusion — that line is gone.
        val directives = joined(
            context(
                recordings = listOf(
                    recording("Setting out heavy"),
                    recording("Coming home lighter", offsetSeconds = 3000L),
                ),
            ),
        )
        assertFalse(
            "the old unconditional line is gone: $directives",
            directives.contains("Compare the first recording with the last"),
        )
        assertFalse(
            "no shift claim without a measured shift: $directives",
            directives.contains("attend to what moved between them"),
        )
    }

    @Test
    fun `first versus last single recording does not fire`() {
        val directives = joined(context(recordings = listOf(recording("Just one thought today"))))
        assertFalse("no compare: $directives", directives.contains("attend to what moved between them"))
    }

    @Test
    fun `speaking rate shift of exactly plus 15 percent at the 25-word floor fires the faster line`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 115.0),
                ),
            ),
        )
        assertTrue(
            "exact faster phrasing: $directives",
            directives.contains(
                "The walker spoke faster by the last recording than the first — attend to what moved between them.",
            ),
        )
    }

    @Test
    fun `speaking rate shift of exactly minus 15 percent at the 25-word floor fires the more-slowly line`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 85.0),
                ),
            ),
        )
        assertTrue(
            "exact more-slowly phrasing: $directives",
            directives.contains(
                "The walker spoke more slowly by the last recording than the first — attend to what moved between them.",
            ),
        )
    }

    @Test
    fun `speaking rate shift below the 15 percent threshold stays silent`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 114.0),
                ),
            ),
        )
        assertFalse("under-threshold pace is silent: $directives", directives.contains("attend to what moved"))
    }

    @Test
    fun `speaking rate shift with a 24-word side stays silent regardless of which side is short`() {
        val shortFirst = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(24), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 130.0),
                ),
            ),
        )
        assertFalse("24-word first recording is noise, not a rate: $shortFirst", shortFirst.contains("attend to what moved"))

        val shortLast = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(24), offsetSeconds = 3000L, wordsPerMinute = 130.0),
                ),
            ),
        )
        assertFalse("24-word last recording is noise, not a rate: $shortLast", shortLast.contains("attend to what moved"))
    }

    @Test
    fun `speaking rate shift with missing wordsPerMinute stays silent regardless of which side is missing`() {
        val missingLast = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = null),
                ),
            ),
        )
        assertFalse("missing last pace is silent: $missingLast", missingLast.contains("attend to what moved"))

        val missingFirst = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = null),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 130.0),
                ),
            ),
        )
        assertFalse("missing first pace is silent: $missingFirst", missingFirst.contains("attend to what moved"))
    }

    @Test
    fun `speaking rate shift with a zero first pace stays silent`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 0.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 100.0),
                ),
            ),
        )
        assertFalse("zero first pace has no relative change: $directives", directives.contains("attend to what moved"))
    }

    @Test
    fun `subject shift with disjoint 12-lemma vocabularies fires the shares-little line`() {
        val opening = openingSubjectWords.joinToString(separator = " ")
        val closing = closingSubjectWords.joinToString(separator = " ")
        assertEquals(12, subjectLemmaCount(opening))
        assertEquals(12, subjectLemmaCount(closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertTrue(
            "exact shares-little phrasing: $directives",
            directives.contains(
                "The walker's last recording shares little vocabulary with the first — attend to what moved between them.",
            ),
        )
    }

    @Test
    fun `subject shift with an 11-lemma smaller side stays silent`() {
        val opening = openingSubjectWords.dropLast(1).joinToString(separator = " ")
        assertEquals(11, subjectLemmaCount(opening))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closingSubjectWords.joinToString(separator = " "), offsetSeconds = 3000L),
                ),
            ),
        )
        assertFalse("11 lemmas is below the judgment floor: $directives", directives.contains("attend to what moved"))
    }

    @Test
    fun `subject shift subset vocabulary stays silent — the Jaccard regression`() {
        // A long opening followed by a short closing note drawn entirely from
        // the same vocabulary: the overlap coefficient reads 1.0 (correctly
        // silent), where Jaccard would collapse to |smaller| / |larger| and
        // penalize the short recording for being short, firing "shares little
        // vocabulary" on a walk that never left its subject.
        val opening = (openingSubjectWords + closingSubjectWords).joinToString(separator = " ")
        val closing = openingSubjectWords.joinToString(separator = " ")
        assertEquals(24, subjectLemmaCount(opening))
        assertEquals(12, subjectLemmaCount(closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertFalse("a contained vocabulary never changed subject: $directives", directives.contains("attend to what moved"))
    }

    @Test
    fun `subject shift at an overlap of exactly the ceiling fires`() {
        // The ceiling is exclusive. The disjoint and subset cases pin
        // overlap 0.0 and 1.0; anywhere between them a retuned constant or
        // a `>=` comparison passes unnoticed, so this fixture lands ON
        // 0.20 — three shared lemmas against a 15-lemma smaller side.
        val shared = extraSubjectWords.take(3)
        val opening = (openingSubjectWords + shared).joinToString(separator = " ")
        val closing = (closingSubjectWords + shared).joinToString(separator = " ")
        assertEquals(15, subjectLemmaCount(opening))
        assertEquals(15, subjectLemmaCount(closing))
        assertEquals(3, sharedLemmaCount(opening, closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertTrue(
            "an overlap of exactly 0.20 is still little enough vocabulary to speak on: $directives",
            directives.contains("shares little vocabulary with the first"),
        )
    }

    @Test
    fun `subject shift at an overlap one lemma above the ceiling stays silent`() {
        // The same three shared lemmas against a 12-lemma smaller side reads
        // 0.25 — a quarter of the closing recording is the opening's own
        // vocabulary, which is a walk that wandered, not one that changed
        // subject.
        val shared = extraSubjectWords.take(3)
        val opening = (openingSubjectWords.dropLast(3) + shared).joinToString(separator = " ")
        val closing = (closingSubjectWords.dropLast(3) + shared).joinToString(separator = " ")
        assertEquals(12, subjectLemmaCount(opening))
        assertEquals(12, subjectLemmaCount(closing))
        assertEquals(3, sharedLemmaCount(opening, closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertFalse(
            "an overlap of 0.25 is past the ceiling: $directives",
            directives.contains("attend to what moved"),
        )
    }

    @Test
    fun `subject shift with a length ratio above 3 stays silent at otherwise-firing vocabularies`() {
        val opening = openingSubjectWords.joinToString(separator = " ")
        val closing = (closingSubjectWords + extraSubjectWords + "morning").joinToString(separator = " ")
        assertEquals(12, subjectLemmaCount(opening))
        assertEquals(37, subjectLemmaCount(closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertFalse("a thin sample against 37 lemmas is silent: $directives", directives.contains("attend to what moved"))
    }

    @Test
    fun `subject shift at a length ratio of exactly 3 fires`() {
        val opening = openingSubjectWords.joinToString(separator = " ")
        val closing = (closingSubjectWords + extraSubjectWords).joinToString(separator = " ")
        assertEquals(12, subjectLemmaCount(opening))
        assertEquals(36, subjectLemmaCount(closing))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening),
                    recording(closing, offsetSeconds = 3000L),
                ),
            ),
        )
        assertTrue(
            "ratio of exactly 3.0 is within the ceiling: $directives",
            directives.contains("shares little vocabulary with the first"),
        )
    }

    @Test
    fun `subject shift stays silent for a non-English walk`() {
        val detected = AttentionDirectives.detect(
            context(
                recordings = listOf(
                    recording(openingSubjectWords.joinToString(separator = " ")),
                    recording(closingSubjectWords.joinToString(separator = " "), offsetSeconds = 3000L),
                ),
            ),
            detectedLanguageCode = "es",
        )
        assertFalse(
            "the lemma substrate is English-only; a Spanish walk must not read as divergence: $detected",
            detected.any { it.contains("attend to what moved") },
        )
    }

    @Test
    fun `speaking rate shift still fires for a non-English walk`() {
        val detected = AttentionDirectives.detect(
            context(
                recordings = listOf(
                    recording(paddedWords(25), offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 120.0),
                ),
            ),
            detectedLanguageCode = "es",
        )
        assertTrue(
            "the language gate belongs to the subject branch only: $detected",
            detected.any { it.contains("The walker spoke faster by the last recording than the first") },
        )
    }

    @Test
    fun `pace branch wins when both pace and subject would fire`() {
        val opening = openingSubjectWords.joinToString(separator = " ") + " " + paddedWords(13)
        val closing = closingSubjectWords.joinToString(separator = " ") + " " + paddedWords(13)
        assertTrue(TranscriptNlp.wordCount(opening) >= 25)
        assertEquals(12, subjectLemmaCount(opening))

        val directives = joined(
            context(
                recordings = listOf(
                    recording(opening, offsetSeconds = 300L, wordsPerMinute = 100.0),
                    recording(closing, offsetSeconds = 3000L, wordsPerMinute = 120.0),
                ),
            ),
        )
        assertTrue("pace is declared first: $directives", directives.contains("The walker spoke faster"))
        assertFalse("only one first-versus-last line may speak: $directives", directives.contains("shares little vocabulary"))
    }

    // --- Cap and assembly ----------------------------------------------------

    @Test
    fun `directives capped at four`() {
        val speeds = List(30) { 1.5 } + List(30) { 0.0 } + List(30) { 0.8 }
        val detected = AttentionDirectives.detect(
            context(
                recordings = listOf(
                    recording("Release the river from its banks"),
                    recording("The river again, release again", offsetSeconds = 900L),
                    recording("Still the river", offsetSeconds = 1500L),
                ),
                durationSeconds = 3600L,
                routeSpeeds = speeds,
                intention = "Release what I cannot carry",
            ),
        )
        assertTrue("cap of four: $detected", detected.size <= 4)
    }

    @Test
    fun `when all five detectors fire, the array order drops firstVersusLast (the last entry)`() {
        // 30 moving + 30 still + 30 faster-moving samples: fires BOTH
        // stillness (the middle run) and paceShift (quickened final
        // third) at once. First and last recordings carry 25+ words and a
        // +20% wordsPerMinute shift so firstVersusLast genuinely fires too.
        val speeds = List(30) { 1.0 } + List(30) { 0.0 } + List(30) { 1.5 }
        val detected = AttentionDirectives.detect(
            context(
                recordings = listOf(
                    recording(
                        "I feel the mountain calling me today " + paddedWords(18),
                        wordsPerMinute = 100.0,
                    ),
                    recording("The mountain never lets go of my thoughts", offsetSeconds = 900L),
                    recording(
                        "Every mountain reminds me why I release my grip " + paddedWords(16),
                        offsetSeconds = 1_800L,
                        wordsPerMinute = 120.0,
                    ),
                ),
                durationSeconds = 3_600L,
                routeSpeeds = speeds,
                intention = "release tension",
            ),
        )

        assertEquals("all five detectors fire; the cap keeps only the first four: $detected", 4, detected.size)
        assertFalse(
            "firstVersusLast is last in the fixed array — it must be the one dropped: $detected",
            detected.any { it.contains("attend to what moved between them") },
        )
        assertTrue("stillness must survive the cap: $detected", detected.any { it.contains("stillness") })
        assertTrue("paceShift must survive the cap: $detected", detected.any { it.contains("quickened") })
        assertTrue(
            "intentionEcho must survive the cap: $detected",
            detected.any { it.contains("surfaces again") },
        )
        assertTrue(
            "recurringWord must survive the cap: $detected",
            detected.any { it.contains("'mountain' returns 3 times") },
        )
    }

    @Test
    fun `assembler includes section only when directives fire`() {
        val quiet = PromptAssembler.assemble(
            context = context(),
            voice = ReflectiveVoice,
            imperial = false,
            zone = nyZone,
        )
        assertFalse("quiet walk has no directives: $quiet", quiet.contains("**Attend to:**"))

        val telling = PromptAssembler.assemble(
            context = context(
                recordings = listOf(
                    recording(paddedWords(25), wordsPerMinute = 100.0),
                    recording(paddedWords(25), offsetSeconds = 3000L, wordsPerMinute = 120.0),
                ),
            ),
            voice = ReflectiveVoice,
            imperial = false,
            zone = nyZone,
        )
        assertTrue(
            "telling walk renders bullets: $telling",
            telling.contains(
                "\n\n**Attend to:**\n- The walker spoke faster by the last recording than the first — " +
                    "attend to what moved between them.",
            ),
        )
    }
}
