// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.prompt.voices.ContemplativeVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.CustomPromptStyleVoice
import org.walktalkmeditate.pilgrim.core.prompt.voices.ReflectiveVoice
import org.walktalkmeditate.pilgrim.domain.WalkEventLike
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * The downstream model shouldn't read Pilgrim's data as fitness
 * telemetry. The practice lexicon teaches it the walk's ritual grammar —
 * what a wander is, what a Seek means, what this seek's story was — in
 * the app's own vocabulary. Mirrors iOS `PracticeLexiconTests.swift@9a418e4`.
 */
class PracticeLexiconTest {

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

    private val start = nyTimestamp(2024, 6, 15, 8, 30)

    private data class TestEvent(
        override val type: WalkEventType,
        override val timestamp: Long,
    ) : WalkEventLike

    private fun context(
        mode: PracticeMode = PracticeMode.Wander,
        seekStory: SeekStoryContext? = null,
        honorStory: HonorStoryContext? = null,
    ): ActivityContext = ActivityContext(
        recordings = emptyList(),
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
        mode = mode,
        seekStory = seekStory,
        pauses = emptyList(),
        ascentMeters = null,
        descentMeters = null,
        honorStory = honorStory,
    )

    private fun assembled(context: ActivityContext): String = PromptAssembler.assemble(
        context = context,
        voice = ContemplativeVoice,
        imperial = false,
        zone = nyZone,
    )

    @Test
    fun `wander walk explains itself`() {
        val text = assembled(context())
        assertTrue(
            "wander line: $text",
            text.contains(
                "**About this practice:** This walk was a wander — no destination, no goal; " +
                    "the path chose itself.",
            ),
        )
    }

    @Test
    fun `seek walk explains the surrender`() {
        val text = assembled(
            context(
                mode = PracticeMode.Seek,
                seekStory = SeekStoryContext(arrivalTimes = listOf(start + 1_800_000L)),
            ),
        )
        assertTrue(
            "surrender text: $text",
            text.contains(
                "**About this practice:** This walk was a Seek. The walker surrendered the " +
                    "choice of destination: a seed cast hidden clearings across the map, " +
                    "veiled in fog, revealed only by nearness and stillness. Arriving is " +
                    "not achievement; it is consent to be led.",
            ),
        )
    }

    @Test
    fun `single arrival carries its hour`() {
        val morningArrival = nyTimestamp(2024, 6, 15, 9, 30)
        val text = PromptAssembler.practiceLexicon(
            context(
                mode = PracticeMode.Seek,
                seekStory = SeekStoryContext(arrivalTimes = listOf(morningArrival)),
            ),
            nyZone,
        )
        assertTrue(
            "single arrival hour: $text",
            text.endsWith(" One clearing was found, reached in the morning."),
        )
    }

    @Test
    fun `seek story arrivals carry their hours`() {
        val morningArrival = nyTimestamp(2024, 6, 15, 9, 30)
        val eveningArrival = nyTimestamp(2024, 6, 15, 18, 30)
        val text = assembled(
            context(
                mode = PracticeMode.Seek,
                seekStory = SeekStoryContext(arrivalTimes = listOf(morningArrival, eveningArrival)),
            ),
        )
        assertTrue(
            "arrival span: $text",
            text.contains("2 clearings were found — the first in the morning, the last in the evening."),
        )
    }

    @Test
    fun `zero arrival seek is honored`() {
        val text = assembled(
            context(mode = PracticeMode.Seek, seekStory = SeekStoryContext(arrivalTimes = emptyList())),
        )
        assertTrue(
            "zero-arrival honor: $text",
            text.contains(
                " No clearing was reached this time — the seek honors this too; " +
                    "some walks are about the looking.",
            ),
        )
    }

    @Test
    fun `seek without story appends no arrival sentence`() {
        val text = PromptAssembler.practiceLexicon(
            context(mode = PracticeMode.Seek, seekStory = null),
            nyZone,
        )
        assertTrue("base seek text only: $text", text.endsWith("it is consent to be led."))
    }

    @Test
    fun `walk practice model no seek event is wander`() {
        val practice = WalkPracticeModel.practice(
            listOf(TestEvent(WalkEventType.WAYPOINT_MARKED, start)),
            honorEnabled = true,
        )
        assertEquals(PracticeMode.Wander, practice.mode)
        assertNull(practice.seekStory)
    }

    @Test
    fun `walk practice model seek event collects sorted arrivals`() {
        val late = start + 2_800_000L
        val early = start + 1_400_000L
        val practice = WalkPracticeModel.practice(
            listOf(
                TestEvent(WalkEventType.SEEK_MODE, start),
                TestEvent(WalkEventType.SEEK_ARRIVAL, late),
                TestEvent(WalkEventType.SEEK_ARRIVAL, early),
            ),
            honorEnabled = true,
        )
        assertEquals(PracticeMode.Seek, practice.mode)
        assertEquals(listOf(early, late), practice.seekStory?.arrivalTimes)
    }

    @Test
    fun `walk practice model seek without arrivals keeps empty story`() {
        val practice = WalkPracticeModel.practice(
            listOf(TestEvent(WalkEventType.SEEK_MODE, start)),
            honorEnabled = true,
        )
        assertEquals(PracticeMode.Seek, practice.mode)
        assertEquals(emptyList<Long>(), practice.seekStory?.arrivalTimes)
    }

    @Test
    fun `custom style carries the lexicon`() {
        val custom = CustomPromptStyle(
            title = "Letters",
            icon = "envelope",
            instruction = "Write me a letter about this walk.",
        )
        val text = PromptAssembler.assemble(
            context = context(),
            voice = CustomPromptStyleVoice(custom),
            imperial = false,
            zone = nyZone,
        )
        assertTrue("custom carries lexicon: $text", text.contains("**About this practice:**"))
    }

    // --- Honor: one form for own and shared walks (parity spec G §8,
    // correction 21; iOS `PromptAssembler.swift:170-201@7c200bf`) ---

    @Test
    fun `honor walk with its Way present and arrived reads iOS's lexicon verbatim`() {
        val text = PromptAssembler.practiceLexicon(
            context(
                mode = PracticeMode.Honor,
                honorStory = HonorStoryContext(wayTitle = "Morning loop", arrived = true),
            ),
            nyZone,
        )
        assertEquals(
            "**About this practice:** This walk was an Honor. The walker followed a Way another walker " +
                "laid down, hearing their voices where they were spoken. Two traveling together; the line " +
                "was traced, not raced. The Way: Morning loop. The end of the Way was reached.",
            text,
        )
    }

    @Test
    fun `honor walk whose Way is gone and left before its end drops the title`() {
        val text = PromptAssembler.practiceLexicon(
            context(
                mode = PracticeMode.Honor,
                honorStory = HonorStoryContext(wayTitle = null, arrived = false),
            ),
            nyZone,
        )
        assertEquals(
            "**About this practice:** This walk was an Honor. The walker followed a Way another walker " +
                "laid down, hearing their voices where they were spoken. Two traveling together; the line " +
                "was traced, not raced. The Way was left before its end, which the practice honors too.",
            text,
        )
    }

    @Test
    fun `a shared Way's title enters raw, arrow and all`() {
        val text = PromptAssembler.practiceLexicon(
            context(
                mode = PracticeMode.Honor,
                honorStory = HonorStoryContext(wayTitle = "Rúa Nova → Praza do Obradoiro", arrived = true),
            ),
            nyZone,
        )
        assertTrue(
            "shared title: $text",
            text.endsWith(" The Way: Rúa Nova → Praza do Obradoiro. The end of the Way was reached."),
        )
    }

    @Test
    fun `honor without a story is the base sentence alone`() {
        val text = PromptAssembler.practiceLexicon(context(mode = PracticeMode.Honor), nyZone)
        assertEquals(
            "**About this practice:** This walk was an Honor. The walker followed a Way another walker " +
                "laid down, hearing their voices where they were spoken. Two traveling together; the line " +
                "was traced, not raced.",
            text,
        )
    }

    @Test
    fun `the honor lexicon rides the assembled prompt`() {
        val text = assembled(
            context(
                mode = PracticeMode.Honor,
                honorStory = HonorStoryContext(wayTitle = "Morning loop", arrived = true),
            ),
        )
        assertTrue("lexicon in prompt: $text", text.contains("This walk was an Honor."))
    }

    @Test
    fun `walk practice model honor event is honor with its arrival, untitled`() {
        val practice = WalkPracticeModel.practice(
            listOf(
                TestEvent(WalkEventType.HONOR_MODE, start),
                TestEvent(WalkEventType.HONOR_ARRIVAL, start + 1_800_000L),
            ),
            honorEnabled = true,
        )
        assertEquals(PracticeMode.Honor, practice.mode)
        assertNull(practice.seekStory)
        assertEquals(HonorStoryContext(wayTitle = null, arrived = true), practice.honorStory)
    }

    @Test
    fun `walk practice model honor wins over seek on one walk`() {
        val practice = WalkPracticeModel.practice(
            listOf(TestEvent(WalkEventType.SEEK_MODE, start), TestEvent(WalkEventType.HONOR_MODE, start)),
            honorEnabled = true,
        )
        assertEquals(PracticeMode.Honor, practice.mode)
        assertEquals(HonorStoryContext(wayTitle = null, arrived = false), practice.honorStory)
    }

    @Test
    fun `walk practice model honor walk with the flag off is the walk without its marker`() {
        val wander = WalkPracticeModel.practice(
            listOf(TestEvent(WalkEventType.HONOR_MODE, start)),
            honorEnabled = false,
        )
        assertEquals(PracticeMode.Wander, wander.mode)
        assertNull(wander.honorStory)

        val seek = WalkPracticeModel.practice(
            listOf(TestEvent(WalkEventType.SEEK_MODE, start), TestEvent(WalkEventType.HONOR_MODE, start)),
            honorEnabled = false,
        )
        assertEquals(PracticeMode.Seek, seek.mode)
    }

    @Test
    fun `plain wander produces no seek vocabulary`() {
        val text = PromptAssembler.assemble(
            context = context(),
            voice = ReflectiveVoice,
            imperial = false,
            zone = nyZone,
        )
        assertFalse("no surrender: $text", text.contains("surrendered"))
        assertFalse("no clearings: $text", text.contains("clearing"))
    }
}
