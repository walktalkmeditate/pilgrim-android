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
import org.walktalkmeditate.pilgrim.core.prompt.voices.ContemplativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CreativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CustomPromptStyleVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.GratitudeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.JournalingVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.PhilosophicalVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.ReflectiveVoice
import org.walktalkmeditate.pilgrim.core.threads.TemporalLean
import org.walktalkmeditate.pilgrim.core.threads.Threads
import org.walktalkmeditate.pilgrim.core.threads.ThreadsDossierFormatter
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContext
import org.walktalkmeditate.pilgrim.core.threads.TranscriptMarkers
import org.walktalkmeditate.pilgrim.core.threads.TranscriptNlp
import org.walktalkmeditate.pilgrim.core.threads.WordNetLexicon

/**
 * Every generated prompt must end with a response contract: the
 * downstream LLM is told how to answer (voice-specific form constraints)
 * and what it may never do (invent details, ignore the walker's
 * language, flatten a two-voice recording into a monologue). Mirrors iOS
 * `PromptResponseContractTests.swift@0172e2b`. Robolectric-backed since
 * v2 (U7): [spokenContext] has non-empty recordings, so
 * [PromptAssembler.assemble] routes through [AttentionDirectives.detect],
 * which requires an installed [WordNetLexicon] (see [setUp]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptResponseContractTest {

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

    private val builtInVoices = listOf(
        ContemplativeVoice, ReflectiveVoice, CreativeVoice,
        GratitudeVoice, PhilosophicalVoice, JournalingVoice,
    )

    private val customVoice = CustomPromptStyleVoice(
        CustomPromptStyle(
            title = "Letters",
            icon = "envelope",
            instruction = "Write me a letter about this walk.",
        ),
    )

    private fun context(recordings: List<RecordingContext>): ActivityContext = ActivityContext(
        recordings = recordings,
        meditations = emptyList(),
        durationSeconds = 1800L,
        distanceMeters = 2_000.0,
        startTimestamp = start,
        placeNames = emptyList(),
        routeSpeeds = emptyList(),
        recentWalkSnippets = emptyList(),
        intention = null,
        waypoints = emptyList(),
        weather = null,
        lunarPhase = MoonPhase(name = "First Quarter", illumination = 0.5, ageInDays = 7.4),
        celestial = null,
        photoContexts = emptyList(),
        narrativeArc = null,
        mode = PracticeMode.Wander,
        seekStory = null,
        pauses = emptyList(),
        ascentMeters = null,
        descentMeters = null,
    )

    private fun spokenContext(): ActivityContext = context(
        recordings = listOf(
            RecordingContext(
                uuid = "r1",
                timestamp = start + 300_000L,
                startCoordinate = null,
                endCoordinate = null,
                wordsPerMinute = null,
                text = "The fog is lifting off the river",
            ),
        ),
    )

    private fun silentContext(): ActivityContext = context(recordings = emptyList())

    private fun assembled(voice: WalkPromptVoice, context: ActivityContext): String =
        PromptAssembler.assemble(
            context = context,
            voice = voice,
            imperial = false,
            zone = nyZone,
        )

    @Test
    fun `every style includes contract section`() {
        for (voice in builtInVoices + customVoice) {
            assertTrue(
                "$voice must carry a response contract",
                assembled(voice, spokenContext()).contains("**How to respond:**"),
            )
        }
    }

    @Test
    fun `anti-fabrication line present even on silent walks`() {
        for (voice in builtInVoices + customVoice) {
            assertTrue(
                "$voice must forbid fabricated details",
                assembled(voice, silentContext()).contains("never invent"),
            )
        }
    }

    @Test
    fun `language line present with speech`() {
        assertTrue(
            assembled(ReflectiveVoice, spokenContext())
                .contains("Respond in the language the walker speaks in the transcription."),
        )
    }

    @Test
    fun `language line absent without speech`() {
        assertFalse(
            "no transcript means no language to mirror",
            assembled(ReflectiveVoice, silentContext()).contains("in the language"),
        )
    }

    @Test
    fun `multi-voice line present with speech absent without`() {
        assertTrue(assembled(ContemplativeVoice, spokenContext()).contains("more than one voice"))
        assertFalse(assembled(ContemplativeVoice, silentContext()).contains("more than one voice"))
    }

    @Test
    fun `contemplative limits questions`() {
        assertTrue(assembled(ContemplativeVoice, spokenContext()).contains("at most one question"))
    }

    @Test
    fun `creative replies with the piece itself`() {
        assertTrue(assembled(CreativeVoice, spokenContext()).contains("no introduction"))
    }

    @Test
    fun `custom style carries shared contract`() {
        val text = assembled(customVoice, spokenContext())
        assertTrue(text.contains("**How to respond:**"))
        assertTrue(text.contains("never invent"))
    }

    @Test
    fun `contemplative spoken contract exact block`() {
        assertEquals(
            "**How to respond:**\n" +
                "- Write in unhurried prose — no bullet points, no headings.\n" +
                "- Ask at most one question, and let it be one worth carrying.\n" +
                "- Do not summarize the walk back to the walker; they were there.\n" +
                "- Respond in the language the walker speaks in the transcription.\n" +
                "- If more than one voice appears in the transcription, honor it as a " +
                "conversation — attend to what happened between the speakers, and never " +
                "guess at names.\n" +
                "- Draw only on what this walk actually holds — never invent details, " +
                "events, or memories that are not in the context above.",
            PromptAssembler.responseContract(ContemplativeVoice, hasSpeech = true, threadsDossier = null),
        )
    }

    @Test
    fun `custom silent contract exact block`() {
        assertEquals(
            "**How to respond:**\n" +
                "- Draw only on what this walk actually holds — never invent details, " +
                "events, or memories that are not in the context above.",
            PromptAssembler.responseContract(customVoice, hasSpeech = false, threadsDossier = null),
        )
    }

    // --- U7: thought-thread safety line, gated on the dossier ARTIFACT --------

    @Test
    fun `thought-thread safety line present only when a threads dossier is carried`() {
        val withDossier = PromptAssembler.responseContract(
            ReflectiveVoice,
            hasSpeech = false,
            threadsDossier = "**Thought threads (on-device linguistic analysis):**",
        )
        assertTrue(
            "verbatim safety line: $withDossier",
            withDossier.contains(
                "The thought-thread marker profiles are descriptive on-device linguistic signals, not " +
                    "assessments — interpret them gently, never produce clinical or diagnostic language, " +
                    "and never treat a single walk's numbers as meaningful on their own.",
            ),
        )
        val withoutDossier = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = false, threadsDossier = null)
        assertFalse(
            "no safety line without a dossier: $withoutDossier",
            withoutDossier.contains("thought-thread marker profiles"),
        )
    }

    @Test
    fun `thought-thread safety line sits between the multi-voice line and the anti-fabrication line`() {
        val text = PromptAssembler.responseContract(
            ReflectiveVoice,
            hasSpeech = true,
            threadsDossier = "**Thought threads (on-device linguistic analysis):**",
        )
        val multiVoiceIdx = text.indexOf("more than one voice")
        val safetyIdx = text.indexOf("thought-thread marker profiles")
        val antiFabricationIdx = text.indexOf("Draw only on what this walk actually holds")
        assertTrue("order: $text", multiVoiceIdx in 0 until safetyIdx)
        assertTrue("order: $text", safetyIdx in 0 until antiFabricationIdx)
    }

    // --- Interpretive key: teach only what the dossier printed (iOS PR #72 fold-in) ---

    private val shareClause = "Read the absolutist-word share as how fixed the walker's framing was, " +
        "and self-focus as how far they placed themselves at the centre of it."

    private val tallyClause = "Read the absolutist and self-focus counts as a bare tally of how fixed " +
        "the walker's framing was and how far they placed themselves at the centre of it — too few " +
        "words to read as a rate, so do not weigh them."

    private val modalClause = "Read the modal lean as the frame the walker was working inside — " +
        "obligation means the frame constrained them, counterfactual means they were already " +
        "replaying alternatives, possibility and tentative mean it was still open, intention means " +
        "they had settled on a course, and desire means they were naming a want rather than a plan."

    private val keyTrailer = "None of these has a fixed meaning; read each through this walk's " +
        "intention and practice."

    private fun threadsContext(
        uuid: String,
        wordCount: Int,
        languageCode: String? = "en",
        modalCounts: Map<String, Int> = emptyMap(),
    ): TranscriptContext = TranscriptContext(
        uuid = uuid,
        languageCode = languageCode,
        wordCount = wordCount,
        themes = emptyList(),
        markers = TranscriptMarkers(
            wordCount = wordCount,
            absolutistCount = 3,
            firstPersonCount = 10,
            insightCount = 0,
            causationCount = 0,
            discrepancyCount = 0,
            temporalLean = TemporalLean.PRESENT,
            modalCounts = modalCounts,
        ),
        transcriptHash = "hash-$uuid",
    )

    private fun realDossier(
        current: TranscriptContext,
        allContexts: List<TranscriptContext> = emptyList(),
        walkIdByRecordingUuid: Map<String, Long> = emptyMap(),
    ): String = requireNotNull(
        ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(current to null),
            allContexts = allContexts,
            threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
            currentWalkId = 1L,
            backfillComplete = false,
            walkIdByRecordingUuid = walkIdByRecordingUuid,
        ),
    ) { "the real formatter must produce a dossier for a non-empty recording list" }

    @Test
    fun `responseContract against real formatter output adapts to what was printed — density shares teach the share clause`() {
        val dossier = realDossier(threadsContext("r1", wordCount = 150))
        assertTrue("fixture must print shares: $dossier", dossier.contains("absolutist words"))

        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = dossier)

        assertTrue("share clause: $contract", contract.contains(shareClause))
        assertFalse("never both share and tally: $contract", contract.contains(tallyClause))
        assertFalse("no modal lean was printed: $contract", contract.contains(modalClause))
        assertTrue("key ends on the no-fixed-meaning trailer: $contract", contract.contains(keyTrailer))
    }

    @Test
    fun `responseContract against real formatter output adapts to what was printed — small-sample raw counts teach the bare-tally clause`() {
        val dossier = realDossier(threadsContext("r1", wordCount = 40))
        assertTrue("fixture must print raw counts: $dossier", dossier.contains("raw counts only"))

        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = dossier)

        assertTrue("tally clause: $contract", contract.contains(tallyClause))
        assertFalse("never both share and tally: $contract", contract.contains(shareClause))
    }

    @Test
    fun `responseContract against real formatter output adapts to what was printed — a printed modal lean teaches the modal clause`() {
        val current = threadsContext("r1", wordCount = 150, modalCounts = mapOf("should" to 12))
        val priors = listOf(
            threadsContext("p1", wordCount = 200, modalCounts = mapOf("should" to 1)),
            threadsContext("p2", wordCount = 200, modalCounts = mapOf("should" to 1)),
            threadsContext("p3", wordCount = 200, modalCounts = mapOf("should" to 1)),
        )
        val dossier = realDossier(
            current = current,
            allContexts = priors + current,
            walkIdByRecordingUuid = mapOf("r1" to 1L, "p1" to 2L, "p2" to 3L, "p3" to 4L),
        )
        assertTrue("fixture must print a modal lean: $dossier", dossier.contains("modal lean:"))

        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = dossier)

        assertTrue("modal clause: $contract", contract.contains(modalClause))
        assertTrue("shares were printed too: $contract", contract.contains(shareClause))
    }

    @Test
    fun `responseContract against real formatter output adapts to what was printed — a dossier that withheld every probe gets the handling note and no key`() {
        val dossier = realDossier(threadsContext("r1", wordCount = 150, languageCode = "es"))
        assertTrue("fixture must withhold markers: $dossier", dossier.contains("Markers unavailable"))

        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = dossier)

        assertTrue("handling note stays unconditional: $contract", contract.contains("thought-thread marker profiles"))
        assertFalse("no share clause: $contract", contract.contains(shareClause))
        assertFalse("no tally clause: $contract", contract.contains(tallyClause))
        assertFalse("no modal clause: $contract", contract.contains(modalClause))
        assertFalse("no trailer without clauses: $contract", contract.contains(keyTrailer))
    }

    @Test
    fun `null threadsDossier carries neither the handling note nor the interpretive key`() {
        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = null)

        assertFalse("no handling note: $contract", contract.contains("thought-thread marker profiles"))
        assertFalse("no key: $contract", contract.contains(keyTrailer))
    }

    @Test
    fun `interpretive key clauses and trailer join into one contract line`() {
        val dossier = realDossier(threadsContext("r1", wordCount = 150))
        val contract = PromptAssembler.responseContract(ReflectiveVoice, hasSpeech = true, threadsDossier = dossier)

        assertTrue("one bullet, single-space joined: $contract", contract.contains("- $shareClause $keyTrailer\n"))
    }

    @Test
    fun `journaling constraint switches person with speech`() {
        assertTrue(
            JournalingVoice.responseConstraints(hasSpeech = true)
                .contains("Write the entry in the walker's own first-person voice, keeping their phrasing where it lives."),
        )
        assertTrue(
            JournalingVoice.responseConstraints(hasSpeech = false)
                .contains("Keep the entry in second person, as a witness would write it."),
        )
    }

    @Test
    fun `contract closes the prompt`() {
        for (voice in builtInVoices + customVoice) {
            val text = assembled(voice, spokenContext())
            assertTrue(
                "$voice contract must be the final section",
                text.endsWith(
                    "- Draw only on what this walk actually holds — never invent details, " +
                        "events, or memories that are not in the context above.",
                ),
            )
        }
    }
}
