// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.ElementBalance
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryPosition
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacSign
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

class PromptAssemblerTest {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private val testStartTimestamp: Long = nyTimestamp(2026, 5, 4, 9, 41)
    private val testLunar: MoonPhase = MoonPhase(
        name = "Waxing Crescent",
        illumination = 0.32,
        ageInDays = 4.5,
    )

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

    private val fakeVoice = object : WalkPromptVoice {
        override fun preamble(hasSpeech: Boolean): String =
            if (hasSpeech) "PREAMBLE_TRUE" else "PREAMBLE_FALSE"

        override fun instruction(hasSpeech: Boolean): String =
            if (hasSpeech) "INSTRUCT_TRUE" else "INSTRUCT_FALSE"
    }

    private fun fixtureContext(
        recordings: List<RecordingContext> = emptyList(),
        meditations: List<MeditationContext> = emptyList(),
        waypoints: List<WaypointContext> = emptyList(),
        photoContexts: List<PhotoContextEntry> = emptyList(),
        placeNames: List<PlaceContext> = emptyList(),
        routeSpeeds: List<Double> = emptyList(),
        recentWalkSnippets: List<WalkSnippet> = emptyList(),
        intention: String? = null,
        weather: String? = null,
        celestial: CelestialSnapshot? = null,
        narrativeArc: NarrativeArc? = NarrativeArc.EMPTY,
        durationSeconds: Long = 1800L,
        distanceMeters: Double = 2_000.0,
        startTimestamp: Long = testStartTimestamp,
        lunarPhase: MoonPhase? = testLunar,
    ): ActivityContext = ActivityContext(
        recordings = recordings,
        meditations = meditations,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        startTimestamp = startTimestamp,
        placeNames = placeNames,
        routeSpeeds = routeSpeeds,
        recentWalkSnippets = recentWalkSnippets,
        intention = intention,
        waypoints = waypoints,
        weather = weather,
        lunarPhase = lunarPhase,
        celestial = celestial,
        photoContexts = photoContexts,
        narrativeArc = narrativeArc,
    )

    private fun assemble(
        context: ActivityContext,
        imperial: Boolean = false,
    ): String = PromptAssembler.assemble(
        context = context,
        voice = fakeVoice,
        imperial = imperial,
        zone = nyZone,
    )

    private fun sampleCelestial(): CelestialSnapshot = CelestialSnapshot(
        positions = listOf(
            PlanetaryPosition(
                planet = Planet.Sun,
                longitude = 5.0,
                tropical = ZodiacPosition(ZodiacSign.Aries, 5.0),
                sidereal = ZodiacPosition(ZodiacSign.Pisces, 11.0),
                isRetrograde = false,
                isIngress = false,
            ),
        ),
        planetaryHour = PlanetaryHour(planet = Planet.Venus, dayRuler = Planet.Sun),
        elementBalance = ElementBalance(
            counts = ZodiacSign.Element.entries.associateWith { 1 },
            dominant = ZodiacSign.Element.Fire,
        ),
        system = ZodiacSystem.Tropical,
        seasonalMarker = SeasonalMarker.SpringEquinox,
    )

    // 1 -----------------------------------------------------------------------

    @Test
    fun `assemble minimal silent walk`() {
        val context = fixtureContext()
        val result = assemble(context)
        val expected = buildString {
            append("PREAMBLE_FALSE")
            append("\n\n---\n\n")
            append("**Context:** Walk duration: 30 minutes | Distance: 2.00 km | ")
            append("Time: morning on May 4, 2026, 9:41 AM | ")
            append("Moon: Waxing Crescent (32% illumination)")
            append("\n\n---\n\n")
            append("INSTRUCT_FALSE")
        }
        assertEquals(expected, result)
    }

    // 2 -----------------------------------------------------------------------

    @Test
    fun `assemble full template all gates on`() {
        val recordingTime = nyTimestamp(2026, 5, 4, 9, 50)
        val meditationStart = nyTimestamp(2026, 5, 4, 10, 0)
        val meditationEnd = nyTimestamp(2026, 5, 4, 10, 5)
        val waypointTime = nyTimestamp(2026, 5, 4, 9, 45)
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            recordings = listOf(
                RecordingContext(
                    uuid = "r1",
                    timestamp = recordingTime,
                    startCoordinate = null,
                    endCoordinate = null,
                    wordsPerMinute = null,
                    text = "first thought",
                ),
            ),
            meditations = listOf(
                MeditationContext(
                    startDate = meditationStart,
                    endDate = meditationEnd,
                    durationSeconds = 300L,
                ),
            ),
            waypoints = listOf(
                WaypointContext(
                    label = "bird call",
                    icon = null,
                    timestamp = waypointTime,
                    coordinate = LatLng(40.71280, -74.00600),
                ),
            ),
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("tree", "sky"),
                        detectedText = listOf("STOP"),
                        people = 1,
                        outdoor = true,
                        dominantColor = "green",
                    ),
                ),
            ),
            placeNames = listOf(
                PlaceContext("Central Park", LatLng(40.7829, -73.9654), PlaceRole.Start),
                PlaceContext("Bryant Park", LatLng(40.7536, -73.9832), PlaceRole.End),
            ),
            routeSpeeds = List(10) { 1.5 },
            recentWalkSnippets = listOf(
                WalkSnippet(
                    date = nyTimestamp(2026, 5, 1, 7, 30),
                    placeName = null,
                    weatherCondition = null,
                    celestialSummary = null,
                    transcriptionPreview = "soft start",
                ),
            ),
            intention = "presence",
            weather = "Weather: Sunny, 21°C",
            celestial = sampleCelestial(),
        )
        val result = assemble(context)

        assertTrue("preamble: $result", result.startsWith("PREAMBLE_TRUE\n\n---\n\n**Context:**"))
        assertTrue("weather inline: $result", result.contains(" | Weather: Sunny, 21°C\n\n**Celestial Context"))
        assertTrue("intention prologue: $result", result.contains("**The walker's intention:** \"presence\""))
        assertTrue("location: $result", result.contains("**Location:** Started near Central Park → ended near Bryant Park"))
        assertTrue("pace: $result", result.contains("**Pace:** Average 11:06 min/km"))
        assertTrue("waypoints: $result", result.contains("**Waypoints marked during walk:**\n[9:45 AM, GPS: 40.71280, -74.00600] bird call"))
        assertTrue("photos: $result", result.contains("**Photos pinned along the walk:**"))
        assertTrue("transcription: $result", result.contains("**Walking Transcription:**\n\n[9:50 AM] first thought"))
        assertTrue("meditations: $result", result.contains("**Meditation Sessions:**\n\n[10:00 AM – 10:05 AM] Meditated for 5 min 0 sec"))
        assertTrue("recent walks: $result", result.contains("**Recent Walk Context (for continuity):**"))
        assertTrue(
            "instruction tail: $result",
            result.endsWith(
                "\n\n---\n\nINSTRUCT_TRUE Ground your response in the walker's stated intention: " +
                    "'presence'. Return to it. Help them see how their walk — its pace, its pauses, " +
                    "its moments — spoke to this purpose.",
            ),
        )
    }

    // 3 -----------------------------------------------------------------------

    @Test
    fun `assemble weather appends to context line`() {
        val context = fixtureContext(weather = "Weather: Sunny, 21°C")
        val result = assemble(context)
        assertTrue(
            "weather inline append: $result",
            result.contains("Moon: Waxing Crescent (32% illumination) | Weather: Sunny, 21°C\n\n---"),
        )
    }

    // 4 -----------------------------------------------------------------------

    @Test
    fun `assemble celestial present appends two-newlines block`() {
        val context = fixtureContext(celestial = sampleCelestial())
        val result = assemble(context)
        assertTrue(
            "celestial appended: $result",
            result.contains("(32% illumination)\n\n**Celestial Context (Tropical):** "),
        )
    }

    // 5 -----------------------------------------------------------------------

    @Test
    fun `assemble intention present appends prologue and instruction tail`() {
        val context = fixtureContext(intention = "presence")
        val result = assemble(context)

        assertTrue(
            "prologue: $result",
            result.contains(
                "\n\n**The walker's intention:** \"presence\"\nThis intention was set deliberately " +
                    "before the walk began. It represents what the walker chose to carry with them. " +
                    "Let it be the lens through which you interpret everything below.",
            ),
        )
        assertTrue(
            "instruction tail: $result",
            result.contains(
                "INSTRUCT_FALSE Ground your response in the walker's stated intention: 'presence'. " +
                    "Return to it.",
            ),
        )
    }

    // 6 -----------------------------------------------------------------------

    @Test
    fun `assemble intention absent omits prologue and instruction tail`() {
        val context = fixtureContext(intention = null)
        val result = assemble(context)
        assertFalse("no prologue: $result", result.contains("**The walker's intention:**"))
        assertFalse("no tail: $result", result.contains("Ground your response"))
    }

    // 7 -----------------------------------------------------------------------

    @Test
    fun `assemble location present appends location block`() {
        val context = fixtureContext(
            placeNames = listOf(
                PlaceContext("Central Park", LatLng(40.7829, -73.9654), PlaceRole.Start),
            ),
        )
        val result = assemble(context)
        assertTrue(
            "location: $result",
            result.contains("\n\n**Location:** Near Central Park"),
        )
    }

    // 8 -----------------------------------------------------------------------

    @Test
    fun `assemble pace block omitted when under 10 moving samples`() {
        val context = fixtureContext(routeSpeeds = List(9) { 1.5 })
        val result = assemble(context)
        assertFalse("no pace: $result", result.contains("**Pace:**"))
    }

    // 9 -----------------------------------------------------------------------

    @Test
    fun `assemble waypoints block rendered with time and coord`() {
        val t1 = nyTimestamp(2026, 5, 4, 9, 45)
        val t2 = nyTimestamp(2026, 5, 4, 9, 55)
        val context = fixtureContext(
            waypoints = listOf(
                WaypointContext("bird call", null, t1, LatLng(40.71280, -74.00600)),
                WaypointContext("rest", null, t2, LatLng(40.71300, -74.00650)),
            ),
        )
        val result = assemble(context)
        assertTrue(
            "waypoints joined by newline: $result",
            result.contains(
                "**Waypoints marked during walk:**\n" +
                    "[9:45 AM, GPS: 40.71280, -74.00600] bird call\n" +
                    "[9:55 AM, GPS: 40.71300, -74.00650] rest",
            ),
        )
    }

    // 10 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block minimal`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = emptyList(),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = false,
                        dominantColor = "gray",
                    ),
                ),
            ),
        )
        val result = assemble(context)
        assertTrue("header: $result", result.contains("Photo 1 (0.35 km, 9:47 AM, GPS: 40.71300, -74.00650):"))
        assertFalse("no scene: $result", result.contains("Scene:"))
        assertFalse("no text-found: $result", result.contains("Text found:"))
        assertTrue("people none: $result", result.contains("People: none"))
        assertTrue("outdoor no: $result", result.contains("Outdoor: no"))
    }

    // 11 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block full`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("tree", "sky"),
                        detectedText = listOf("STOP", "ONE WAY"),
                        people = 2,
                        outdoor = true,
                        dominantColor = "green",
                    ),
                ),
            ),
        )
        val result = assemble(context)
        assertTrue("scene: $result", result.contains("\n  Scene: tree, sky"))
        assertTrue("text-found: $result", result.contains("\n  Text found: \"STOP\", \"ONE WAY\""))
        assertTrue("people 2: $result", result.contains("\n  People: 2"))
        assertTrue("outdoor yes: $result", result.contains("\n  Outdoor: yes"))
    }

    // 12 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block null coordinate omits gps segment`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = null,
                    context = PhotoContext(
                        tags = emptyList(),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = false,
                        dominantColor = "gray",
                    ),
                ),
            ),
        )
        val result = assemble(context)
        assertTrue("no-gps header: $result", result.contains("Photo 1 (0.35 km, 9:47 AM):"))
        assertFalse("no GPS substring after photo header: $result", result.contains("9:47 AM, GPS:"))
    }

    // 13 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block has no animals line`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("dog", "cat"),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = true,
                        dominantColor = "brown",
                    ),
                ),
            ),
        )
        val result = assemble(context)
        assertFalse("no Animals: line: $result", result.contains("Animals:"))
    }

    // 14 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block has no focal area line`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("tree"),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = true,
                        dominantColor = "green",
                    ),
                ),
            ),
        )
        val result = assemble(context)
        assertFalse("no Focal area: line: $result", result.contains("Focal area:"))
    }

    // 15 ----------------------------------------------------------------------

    @Test
    fun `assemble photos block has no visual narrative or color progression block`() {
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val arc = NarrativeArc(
            attentionArc = "consistently_close",
            solitude = "alone",
            recurringTheme = listOf("water"),
            dominantColors = listOf("blue", "green"),
        )
        val context = fixtureContext(
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("tree"),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = true,
                        dominantColor = "green",
                    ),
                ),
            ),
            narrativeArc = arc,
        )
        val result = assemble(context)
        assertFalse("no Visual narrative: $result", result.contains("Visual narrative:"))
        assertFalse("no Color progression: $result", result.contains("Color progression:"))
        assertFalse("no Recurring theme: $result", result.contains("Recurring theme:"))
    }

    // 16 ----------------------------------------------------------------------

    @Test
    fun `assemble transcription block rendered when recordings non-empty`() {
        val ts = nyTimestamp(2026, 5, 4, 9, 50)
        val context = fixtureContext(
            recordings = listOf(
                RecordingContext(
                    uuid = "r1",
                    timestamp = ts,
                    startCoordinate = null,
                    endCoordinate = null,
                    wordsPerMinute = null,
                    text = "first thought",
                ),
            ),
        )
        val result = assemble(context)
        assertTrue(
            "transcription block: $result",
            result.contains("\n\n**Walking Transcription:**\n\n[9:50 AM] first thought"),
        )
    }

    // 17 ----------------------------------------------------------------------

    @Test
    fun `assemble meditations block rendered when meditations non-empty`() {
        val start = nyTimestamp(2026, 5, 4, 10, 0)
        val end = nyTimestamp(2026, 5, 4, 10, 6)
        val context = fixtureContext(
            meditations = listOf(
                MeditationContext(start, end, durationSeconds = 330L),
            ),
        )
        val result = assemble(context)
        assertTrue(
            "meditations block: $result",
            result.contains(
                "\n\n**Meditation Sessions:**\n\n[10:00 AM – 10:06 AM] Meditated for 5 min 30 sec",
            ),
        )
    }

    // 18 ----------------------------------------------------------------------

    @Test
    fun `assemble recent walks block rendered when snippets non-empty`() {
        val context = fixtureContext(
            recentWalkSnippets = listOf(
                WalkSnippet(
                    date = nyTimestamp(2026, 5, 1, 7, 30),
                    placeName = null,
                    weatherCondition = null,
                    celestialSummary = null,
                    transcriptionPreview = "soft start",
                ),
            ),
        )
        val result = assemble(context)
        assertTrue(
            "recent walks block: $result",
            result.contains("\n\n**Recent Walk Context (for continuity):**\n\n[May 1] \"soft start\""),
        )
    }

    // 19 ----------------------------------------------------------------------

    @Test
    fun `assemble section order matches iOS exact`() {
        val recordingTime = nyTimestamp(2026, 5, 4, 9, 50)
        val meditationStart = nyTimestamp(2026, 5, 4, 10, 0)
        val meditationEnd = nyTimestamp(2026, 5, 4, 10, 5)
        val waypointTime = nyTimestamp(2026, 5, 4, 9, 45)
        val photoTime = nyTimestamp(2026, 5, 4, 9, 47)
        val context = fixtureContext(
            recordings = listOf(
                RecordingContext("r1", recordingTime, null, null, null, "first"),
            ),
            meditations = listOf(
                MeditationContext(meditationStart, meditationEnd, 300L),
            ),
            waypoints = listOf(
                WaypointContext("bird", null, waypointTime, LatLng(40.71280, -74.00600)),
            ),
            photoContexts = listOf(
                PhotoContextEntry(
                    index = 1,
                    distanceIntoWalkMeters = 350.0,
                    time = photoTime,
                    coordinate = LatLng(40.71300, -74.00650),
                    context = PhotoContext(
                        tags = listOf("tree"),
                        detectedText = emptyList(),
                        people = 0,
                        outdoor = true,
                        dominantColor = "green",
                    ),
                ),
            ),
            placeNames = listOf(
                PlaceContext("Central Park", LatLng(40.7829, -73.9654), PlaceRole.Start),
            ),
            routeSpeeds = List(10) { 1.5 },
            recentWalkSnippets = listOf(
                WalkSnippet(
                    date = nyTimestamp(2026, 5, 1, 7, 30),
                    placeName = null,
                    weatherCondition = null,
                    celestialSummary = null,
                    transcriptionPreview = "soft start",
                ),
            ),
            intention = "presence",
            weather = "Weather: Sunny, 21°C",
            celestial = sampleCelestial(),
        )
        val result = assemble(context)

        val markers = listOf(
            "PREAMBLE_TRUE",
            "**Context:**",
            "Weather: Sunny",
            "**Celestial Context",
            "**The walker's intention:**",
            "**Location:**",
            "**Pace:**",
            "**Waypoints marked during walk:**",
            "**Photos pinned along the walk:**",
            "**Walking Transcription:**",
            "**Meditation Sessions:**",
            "**Recent Walk Context (for continuity):**",
            "INSTRUCT_TRUE",
        )
        var lastIndex = -1
        var lastMarker = ""
        for (marker in markers) {
            val idx = result.indexOf(marker)
            assertNotEquals("missing marker $marker in: $result", -1, idx)
            assertTrue(
                "marker '$marker' (idx=$idx) precedes previous '$lastMarker' (idx=$lastIndex). full: $result",
                idx > lastIndex,
            )
            lastIndex = idx
            lastMarker = marker
        }

        // Final divider precedes instruction tail
        val instructIdx = result.indexOf("INSTRUCT_TRUE")
        val lastDividerIdx = result.lastIndexOf("\n\n---\n\n")
        assertTrue(
            "final divider must precede instruction: dividerIdx=$lastDividerIdx instructIdx=$instructIdx",
            lastDividerIdx in 0 until instructIdx,
        )
    }

    // 20 ----------------------------------------------------------------------

    @Test
    fun `assemble intention both places uses single quotes in instruction tail`() {
        val context = fixtureContext(intention = "theIntention")
        val result = assemble(context)
        assertTrue(
            "exact tail phrase with single quotes: $result",
            result.contains(
                "Ground your response in the walker's stated intention: 'theIntention'.",
            ),
        )
    }
}
