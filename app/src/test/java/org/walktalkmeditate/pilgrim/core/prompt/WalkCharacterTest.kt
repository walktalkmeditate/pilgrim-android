// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Two different walks must not open with identical prose. [WalkCharacter]
 * distills what made this walk distinct (length, hour, moon, stillness)
 * into a preamble note every style — including custom styles — carries.
 * Mirrors iOS `WalkCharacterTests.swift@9a418e4`.
 */
class WalkCharacterTest {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
    }

    private fun nyTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(nyZone)
            .toInstant()
            .toEpochMilli()

    private val morning = nyTimestamp(2024, 6, 15, 10, 0)
    private val night = nyTimestamp(2024, 6, 15, 21, 30)

    private val halfMoon = MoonPhase(name = "First Quarter", illumination = 0.5, ageInDays = 7.4)
    private val fullMoon = MoonPhase(name = "Full Moon", illumination = 0.99, ageInDays = 14.7)

    private fun context(
        durationSeconds: Long = 1800L,
        startTimestamp: Long = morning,
        lunarPhase: MoonPhase? = halfMoon,
        meditations: List<MeditationContext> = emptyList(),
    ): ActivityContext = ActivityContext(
        recordings = emptyList(),
        meditations = meditations,
        durationSeconds = durationSeconds,
        distanceMeters = 2_000.0,
        startTimestamp = startTimestamp,
        placeNames = emptyList(),
        routeSpeeds = emptyList(),
        recentWalkSnippets = emptyList(),
        intention = null,
        waypoints = emptyList(),
        weather = null,
        lunarPhase = lunarPhase,
        celestial = null,
        photoContexts = emptyList(),
        narrativeArc = null,
        mode = PracticeMode.Wander,
        seekStory = null,
        pauses = emptyList(),
        ascentMeters = null,
        descentMeters = null,
    )

    @Test
    fun `ordinary day walk yields null`() {
        assertNull(WalkCharacter.note(context(), nyZone))
    }

    @Test
    fun `long night walk names both`() {
        val note = WalkCharacter.note(context(durationSeconds = 5400L, startTimestamp = night), nyZone)
        assertTrue("long: $note", note?.contains("long walk") == true)
        assertTrue("night: $note", note?.contains("night") == true)
    }

    @Test
    fun `long night walk composes grammatically`() {
        assertEquals(
            "the time phrase must attach to the walk noun, not trail the elaboration",
            "This was a long walk into the night — the kind where thought thins out " +
                "and something quieter takes over.",
            WalkCharacter.note(context(durationSeconds = 5400L, startTimestamp = night), nyZone),
        )
    }

    @Test
    fun `brief walk honors brevity`() {
        assertEquals(
            "This was a brief walk, taken anyway — brevity is not smallness.",
            WalkCharacter.note(context(durationSeconds = 600L), nyZone),
        )
    }

    @Test
    fun `early start walk names the unclaimed day`() {
        val early = nyTimestamp(2024, 6, 15, 6, 30)
        assertEquals(
            "This was a walk begun before the day claimed its shape.",
            WalkCharacter.note(context(startTimestamp = early), nyZone),
        )
    }

    @Test
    fun `full moon is named`() {
        val note = WalkCharacter.note(context(lunarPhase = fullMoon), nyZone)
        assertEquals("This was a walk, under a full moon.", note)
    }

    @Test
    fun `new moon is named`() {
        val newMoon = MoonPhase(name = "New Moon", illumination = 0.01, ageInDays = 0.3)
        val note = WalkCharacter.note(context(lunarPhase = newMoon), nyZone)
        assertEquals("This was a walk, under a new moon.", note)
    }

    @Test
    fun `meditated walk names stillness`() {
        val meditation = MeditationContext(
            startDate = morning + 600_000L,
            endDate = morning + 1_200_000L,
            durationSeconds = 600L,
        )
        val note = WalkCharacter.note(context(meditations = listOf(meditation)), nyZone)
        assertEquals("This was a walk, with stillness folded into it.", note)
    }

    @Test
    fun `null lunar phase adds no moon tail`() {
        assertNull(WalkCharacter.note(context(lunarPhase = null), nyZone))
    }

    @Test
    fun `assembler weaves note into every built-in style`() {
        val longNight = context(durationSeconds = 5400L, startTimestamp = night)
        val voices = listOf(
            ContemplativeVoice, ReflectiveVoice, CreativeVoice,
            GratitudeVoice, PhilosophicalVoice, JournalingVoice,
        )
        for (voice in voices) {
            val text = PromptAssembler.assemble(
                context = longNight,
                voice = voice,
                imperial = false,
                zone = nyZone,
            )
            assertTrue("$voice must carry the walk's character", text.contains("long walk"))
        }
    }

    @Test
    fun `custom style shares standard preamble and note`() {
        val custom = CustomPromptStyle(
            title = "Letters",
            icon = "envelope",
            instruction = "Write me a letter about this walk.",
        )
        val text = PromptAssembler.assemble(
            context = context(durationSeconds = 5400L, startTimestamp = night),
            voice = CustomPromptStyleVoice(custom),
            imperial = false,
            zone = nyZone,
        )
        assertTrue(
            "custom styles must share the standard preamble, not a hardcoded copy",
            text.contains(StandardPreamble.text(hasSpeech = false)),
        )
        assertTrue(text.contains("long walk"))
    }
}
