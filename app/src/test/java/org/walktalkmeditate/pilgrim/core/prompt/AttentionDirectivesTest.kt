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

    private fun recording(text: String, offsetSeconds: Long = 300L): RecordingContext =
        RecordingContext(
            uuid = "r-$offsetSeconds",
            timestamp = start + offsetSeconds * 1000L,
            startCoordinate = null,
            endCoordinate = null,
            wordsPerMinute = null,
            text = text,
        )

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

    // --- First vs last recording ---------------------------------------------

    @Test
    fun `first versus last two recordings fires`() {
        val directives = joined(
            context(
                recordings = listOf(
                    recording("Setting out heavy"),
                    recording("Coming home lighter", offsetSeconds = 3000L),
                ),
            ),
        )
        assertTrue(
            "exact compare phrasing: $directives",
            directives.contains(
                "Compare the first recording with the last — measure what changed in the walker between them.",
            ),
        )
    }

    @Test
    fun `first versus last single recording does not fire`() {
        val directives = joined(context(recordings = listOf(recording("Just one thought today"))))
        assertFalse("no compare: $directives", directives.contains("first recording"))
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
        // third) at once.
        val speeds = List(30) { 1.0 } + List(30) { 0.0 } + List(30) { 1.5 }
        val detected = AttentionDirectives.detect(
            context(
                recordings = listOf(
                    recording("I feel the mountain calling me today"),
                    recording("The mountain never lets go of my thoughts", offsetSeconds = 900L),
                    recording("Every mountain reminds me why I release my grip", offsetSeconds = 1_800L),
                ),
                durationSeconds = 3_600L,
                routeSpeeds = speeds,
                intention = "release tension",
            ),
        )

        assertEquals("all five detectors fire; the cap keeps only the first four: $detected", 4, detected.size)
        assertFalse(
            "firstVersusLast is last in the fixed array — it must be the one dropped: $detected",
            detected.any { it.contains("Compare the first recording with the last") },
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
                    recording("Setting out"),
                    recording("Returning", offsetSeconds = 3000L),
                ),
            ),
            voice = ReflectiveVoice,
            imperial = false,
            zone = nyZone,
        )
        assertTrue(
            "telling walk renders bullets: $telling",
            telling.contains(
                "\n\n**Attend to:**\n- Compare the first recording with the last — " +
                    "measure what changed in the walker between them.",
            ),
        )
    }
}
