// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.prompt.voices.ContemplativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CreativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CustomPromptStyleVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.GratitudeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.JournalingVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.PhilosophicalVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.ReflectiveVoice

/**
 * Every generated prompt must end with a response contract: the
 * downstream LLM is told how to answer (voice-specific form constraints)
 * and what it may never do (invent details, ignore the walker's
 * language, flatten a two-voice recording into a monologue). Mirrors iOS
 * `PromptResponseContractTests.swift@9a418e4`.
 */
class PromptResponseContractTest {

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
            PromptAssembler.responseContract(ContemplativeVoice, hasSpeech = true),
        )
    }

    @Test
    fun `custom silent contract exact block`() {
        assertEquals(
            "**How to respond:**\n" +
                "- Draw only on what this walk actually holds — never invent details, " +
                "events, or memories that are not in the context above.",
            PromptAssembler.responseContract(customVoice, hasSpeech = false),
        )
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
