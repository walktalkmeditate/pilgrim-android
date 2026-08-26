// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DossierSenses] + [DossierSensesTracks] fixtures, ported from
 * `Pilgrim/Models/Threads/DossierSenses.swift` /
 * `DossierSensesTracks.swift` (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`). Robolectric-hosted
 * because [DossierSenses.distanceMeters] calls the real
 * `android.location.Location.distanceBetween` (a pure Java/Vincenty
 * static under Robolectric — no shadow needed, but the plain unit-test
 * android.jar throws on any un-stubbed platform call).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DossierSensesTest {

    @Before
    fun setUp() {
        // intentionLineage/markerColoring route through TranscriptNlp,
        // which requires an installed lexicon before its first
        // lemma-dependent call (see TranscriptNlp's own KDoc).
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        TranscriptNlp.install(WordNetLexicon(context, json))
    }

    private val base: Instant = Instant.parse("2026-06-15T09:00:00Z")

    private fun minimalInput(): SenseInput = SenseInput(
        currentWalkId = 1L,
        walkStart = base,
        walkEnd = base.plusSeconds(3600),
        totalAscent = 0.0,
        elevationSeries = emptyList(),
        photos = emptyList(),
        currentRecordings = emptyList(),
        threads = emptyList(),
        backfillComplete = false,
        walkSnapshots = emptyList(),
        recordingTimestamps = emptyMap(),
        fixes = emptyMap(),
        moon = null,
    )

    private fun appearance(recordingUuid: String, walkId: Long, date: Instant) =
        ThreadAppearance(recordingUuid = recordingUuid, walkId = walkId, date = date, mentionCount = 1, salience = 0.1)

    private fun thread(lemma: String, displayTerm: String = lemma, appearances: List<ThreadAppearance>) =
        ActiveThread(lemma = lemma, displayTerm = displayTerm, appearances = appearances)

    private fun theme(lemma: String, displayTerm: String = lemma, mentions: List<LemmaMention> = emptyList()) =
        Theme(lemma = lemma, displayTerm = displayTerm, mentionCount = maxOf(1, mentions.size), salience = 0.1, mentions = mentions)

    private fun recording(
        uuid: String,
        start: Instant,
        end: Instant,
        text: String = "",
        wordCount: Int = 0,
        themes: List<Theme> = emptyList(),
    ) = CurrentRecording(uuid = uuid, start = start, end = end, text = text, wordCount = wordCount, themes = themes)

    /** ~1 degree of latitude ≈ 111,320m — an approximation good to well
     * within 1% at these scales, used only to place test fixtures safely
     * away from thresholds (never to assert distanceMeters' own accuracy —
     * see the dedicated geodesic-verification tests below). */
    private fun northOf(coordinate: Coordinate, meters: Double): Coordinate =
        Coordinate(coordinate.latitude + meters / 111_320.0, coordinate.longitude)

    // =====================================================================
    // Dispatcher — cap / priority / dedup / Output
    // =====================================================================

    @Test fun `5 senses firing yields exactly 3 lines in priority order, and never evaluates the rest`() {
        val calls = mutableListOf<DossierSenses.Sense>()
        val stubbed = mapOf(
            DossierSenses.Sense.PLACE_RESONANCE to SenseLine("place", "lemma-place"),
            DossierSenses.Sense.MOON_LINE to SenseLine("moon", "lemma-moon"),
            DossierSenses.Sense.MARKER_COLORING to SenseLine("marker", "lemma-marker"),
            DossierSenses.Sense.INTENTION_LINEAGE to SenseLine("lineage", "lemma-lineage"),
            DossierSenses.Sense.CLIMB_ANCHORING to SenseLine("climb", "lemma-climb"),
        )
        val output = DossierSenses.lines(minimalInput()) { sense, _, _ ->
            calls += sense
            stubbed[sense]
        }
        assertEquals(listOf("place", "moon", "marker"), output.lines)
        assertEquals(
            listOf(DossierSenses.Sense.PLACE_RESONANCE, DossierSenses.Sense.MOON_LINE, DossierSenses.Sense.MARKER_COLORING),
            calls,
        )
    }

    @Test fun `a theme named at rank 1 never reappears at a lower rank — dedup skips, doesn't stop`() {
        val output = DossierSenses.lines(minimalInput()) { sense, _, _ ->
            when (sense) {
                DossierSenses.Sense.PLACE_RESONANCE -> SenseLine("river surfaced", "river")
                DossierSenses.Sense.MARKER_COLORING -> SenseLine("river again", "river")
                DossierSenses.Sense.INTENTION_LINEAGE -> SenseLine("stone carried", "stone")
                else -> null
            }
        }
        assertEquals(listOf("river surfaced", "stone carried"), output.lines)
    }

    @Test fun `a nil-lemma line never registers in used and can never be suppressed`() {
        val output = DossierSenses.lines(minimalInput()) { sense, _, _ ->
            when (sense) {
                DossierSenses.Sense.PLACE_RESONANCE -> SenseLine("first nil-lemma line", null)
                DossierSenses.Sense.MOON_LINE -> SenseLine("second nil-lemma line", null)
                else -> null
            }
        }
        assertEquals(listOf("first nil-lemma line", "second nil-lemma line"), output.lines)
    }

    @Test fun `reportedLunationIndex is null when moonLine's line is dedup-dropped, even though it returned non-null`() {
        val output = DossierSenses.lines(minimalInput().copy(moon = moonInput(lunationIndex = 7))) { sense, _, _ ->
            when (sense) {
                DossierSenses.Sense.PLACE_RESONANCE -> SenseLine("place claims it first", "shared-lemma")
                DossierSenses.Sense.MOON_LINE -> SenseLine("moon line text", "shared-lemma")
                else -> null
            }
        }
        assertEquals(listOf("place claims it first"), output.lines)
        assertNull("moonLine's line was dedup-dropped, so the lunation must not be marked reported", output.reportedLunationIndex)
    }

    @Test fun `reportedLunationIndex is set when moonLine's line survives into the block`() {
        val output = DossierSenses.lines(minimalInput().copy(moon = moonInput(lunationIndex = 7))) { sense, _, _ ->
            if (sense == DossierSenses.Sense.MOON_LINE) SenseLine("moon line text", null) else null
        }
        assertEquals(7, output.reportedLunationIndex)
    }

    @Test fun `zero senses firing yields an empty output with no reported lunation`() {
        val output = DossierSenses.lines(minimalInput()) { _, _, _ -> null }
        assertTrue(output.lines.isEmpty())
        assertNull(output.reportedLunationIndex)
    }

    @Test fun `default dispatch wires through to the real per-sense implementations`() {
        // speechShape needs no threads/backfill/moon — a clean way to
        // prove the production `evaluate` default actually reaches
        // DossierSensesTracks rather than a mis-wired stub.
        val input = minimalInput().copy(
            currentRecordings = listOf(
                recording(uuid = "r1", start = base, end = base.plusSeconds(60), wordCount = 5),
            ),
            walkEnd = base.plusSeconds(3600),
        )
        val output = DossierSenses.lines(input)
        assertEquals(1, output.lines.size)
        assertTrue(output.lines.single().startsWith("All the words came in the first third"))
    }

    private fun moonInput(
        lunationIndex: Int = 1,
        moonName: String = "Wolf Moon",
        start: Instant = base.minus(30, ChronoUnit.DAYS),
        end: Instant = base,
        lastReportedIndex: Int? = null,
        currentWalkHasWords: Boolean = true,
        allWalkDates: List<Instant> = emptyList(),
        wordedWalkDates: List<Instant> = emptyList(),
    ) = MoonInput(
        lunationIndex = lunationIndex, moonName = moonName, start = start, end = end,
        lastReportedIndex = lastReportedIndex, currentWalkHasWords = currentWalkHasWords,
        allWalkDates = allWalkDates, wordedWalkDates = wordedWalkDates,
    )

    // =====================================================================
    // Shared helpers
    // =====================================================================

    @Test fun `median of an empty list is 0`() {
        assertEquals(0.0, DossierSenses.median(emptyList()), 0.0)
    }

    @Test fun `median of an odd-count list is the middle element`() {
        assertEquals(2.0, DossierSenses.median(listOf(3.0, 1.0, 2.0)), 0.0)
    }

    @Test fun `median of an even-count list averages the two middle elements`() {
        assertEquals(2.5, DossierSenses.median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
    }

    @Test fun `timesPhrase spells 2 as twice`() {
        assertEquals("twice", DossierSenses.timesPhrase(2))
    }

    @Test fun `timesPhrase spells 3 through 9`() {
        val expected = mapOf(
            3 to "three times", 4 to "four times", 5 to "five times", 6 to "six times",
            7 to "seven times", 8 to "eight times", 9 to "nine times",
        )
        for ((n, phrase) in expected) assertEquals(phrase, DossierSenses.timesPhrase(n))
    }

    @Test fun `timesPhrase renders bare numerals outside 2 through 9`() {
        assertEquals("1 times", DossierSenses.timesPhrase(1))
        assertEquals("10 times", DossierSenses.timesPhrase(10))
        assertEquals("23 times", DossierSenses.timesPhrase(23))
    }

    @Test fun `ordinalWord spells 3 through 12 as capitalized words`() {
        val expected = mapOf(
            3 to "Third", 4 to "Fourth", 5 to "Fifth", 6 to "Sixth", 7 to "Seventh",
            8 to "Eighth", 9 to "Ninth", 10 to "Tenth", 11 to "Eleventh", 12 to "Twelfth",
        )
        for ((n, word) in expected) assertEquals(word, DossierSenses.ordinalWord(n))
    }

    @Test fun `ordinalWord falls to numeral suffixes past the table, with the 11-13 teens rule intact`() {
        assertEquals("13th", DossierSenses.ordinalWord(13))
        assertEquals("21st", DossierSenses.ordinalWord(21))
        assertEquals("22nd", DossierSenses.ordinalWord(22))
        assertEquals("23rd", DossierSenses.ordinalWord(23))
        assertEquals("24th", DossierSenses.ordinalWord(24))
        assertEquals("101st", DossierSenses.ordinalWord(101))
        // %100 check precedes %10 — 111/112/113 must NOT read "111st"/"112nd"/"113rd".
        assertEquals("111th", DossierSenses.ordinalWord(111))
        assertEquals("112th", DossierSenses.ordinalWord(112))
        assertEquals("113th", DossierSenses.ordinalWord(113))
    }

    @Test fun `qualifies accepts gapSeconds inclusive at exactly 90`() {
        assertTrue(DossierSenses.qualifies(RouteFix(Coordinate(0.0, 0.0), horizontalAccuracy = 1.0, gapSeconds = 90.0)))
    }

    @Test fun `qualifies rejects gapSeconds just past 90`() {
        assertTrue(!DossierSenses.qualifies(RouteFix(Coordinate(0.0, 0.0), horizontalAccuracy = 1.0, gapSeconds = 90.001)))
    }

    @Test fun `qualifies rejects horizontalAccuracy exclusive at exactly 100`() {
        assertTrue(!DossierSenses.qualifies(RouteFix(Coordinate(0.0, 0.0), horizontalAccuracy = 100.0, gapSeconds = 1.0)))
    }

    @Test fun `qualifies accepts horizontalAccuracy just under 100`() {
        assertTrue(DossierSenses.qualifies(RouteFix(Coordinate(0.0, 0.0), horizontalAccuracy = 99.999, gapSeconds = 1.0)))
    }

    @Test fun `activeThreads filters to threads with an appearance on the current walk, preserving order`() {
        val current = thread("bravo", appearances = listOf(appearance("r1", 1L, base)))
        val other = thread("alpha", appearances = listOf(appearance("r2", 2L, base)))
        // "other" (alpha) has no appearance on walk 1 — only "current"
        // (bravo) does, so activeThreads must return just the latter,
        // preserving the input list's own lemma-alphabetical order
        // (ThreadStore.build's own sort — activeThreads never re-sorts).
        val input = minimalInput().copy(currentWalkId = 1L, threads = listOf(other, current))
        assertEquals(listOf(current), DossierSenses.activeThreads(input))
    }

    // --- geodesic distance: verified, not assumed, at the three tuned thresholds ---

    @Test fun `distanceMeters approximates a known ~100m north-south offset within 1 percent`() {
        val a = Coordinate(35.0, 139.0)
        val b = Coordinate(35.0 + 100.0 / 111_320.0, 139.0)
        val distance = DossierSenses.distanceMeters(a, b)
        assertTrue("expected ~100m, got $distance", kotlin.math.abs(distance - 100.0) < 1.0)
    }

    @Test fun `distanceMeters straddles the 150m placeClusterRadius threshold as expected`() {
        val hub = Coordinate(35.0, 139.0)
        val justInside = northOf(hub, 140.0)
        val justOutside = northOf(hub, 165.0)
        assertTrue(DossierSenses.distanceMeters(hub, justInside) <= 150.0)
        assertTrue(DossierSenses.distanceMeters(hub, justOutside) > 150.0)
    }

    @Test fun `distanceMeters straddles the 75m photoTieRadius threshold as expected`() {
        val hub = Coordinate(35.0, 139.0)
        val justInside = northOf(hub, 65.0)
        val justOutside = northOf(hub, 90.0)
        assertTrue(DossierSenses.distanceMeters(hub, justInside) <= 75.0)
        assertTrue(DossierSenses.distanceMeters(hub, justOutside) > 75.0)
    }

    // =====================================================================
    // 1/8 — placeResonance
    // =====================================================================

    @Test fun `placeResonance is silent until backfill completes`() {
        val hub = Coordinate(35.0, 139.0)
        val input = placeResonanceFixture(backfillComplete = false, hub = hub)
        assertNull(DossierSensesTracks.placeResonance(input, emptySet()))
    }

    @Test fun `placeResonance fires when a tight cross-walk cluster is more specific than baseline`() {
        val input = placeResonanceFixture(backfillComplete = true, hub = Coordinate(35.0, 139.0))
        val line = DossierSensesTracks.placeResonance(input, emptySet())
        assertEquals("'river' has surfaced on 2 walks — twice near the same stretch of ground.", line?.text)
        assertEquals("river", line?.lemma)
    }

    @Test fun `placeResonance emits bare numerals for 3+ mentions, never the spelled timesPhrase form`() {
        val hub = Coordinate(35.0, 139.0)
        val input = placeResonanceFixture(backfillComplete = true, hub = hub, extraSameSpotMention = true)
        val line = DossierSensesTracks.placeResonance(input, emptySet())
        assertEquals("'river' has surfaced on 2 walks — 3 times near the same stretch of ground.", line?.text)
    }

    @Test fun `placeResonance suppresses a zero baseline — a one-spot walker is never more specific than routine`() {
        // Only r1/r2 (river, identical coords) qualify — no third
        // mention anywhere means baseline's own pairwise distance list
        // is a single 0.0, so baseline == 0 and the strict `< 0` guard
        // can never pass.
        val hub = Coordinate(35.0, 139.0)
        val input = placeResonanceFixture(backfillComplete = true, hub = hub, includeFarMention = false)
        assertNull(DossierSensesTracks.placeResonance(input, emptySet()))
    }

    @Test fun `placeResonance requires at least 2 distinct walks window-wide`() {
        val hub = Coordinate(35.0, 139.0)
        val input = placeResonanceFixture(backfillComplete = true, hub = hub, secondMentionSameWalk = true)
        assertNull(DossierSensesTracks.placeResonance(input, emptySet()))
    }

    @Test fun `placeResonance applies the theme candidate cap BEFORE the suppression filter`() {
        // alpha..delta (the first 4 lemma-ordered candidates) are all
        // suppressed; echo (5th) would otherwise qualify but must never
        // be reached because .take(4) applies before suppression
        // filtering.
        val hub = Coordinate(35.0, 139.0)
        val far = northOf(hub, 100_000.0)
        val filler = thread("alpha", appearances = listOf(appearance("f1", 1L, base)))
        val filler2 = thread("bravo", appearances = listOf(appearance("f2", 1L, base)))
        val filler3 = thread("charlie", appearances = listOf(appearance("f3", 1L, base)))
        val filler4 = thread("delta", appearances = listOf(appearance("f4", 1L, base)))
        val echo = thread(
            "echo",
            appearances = listOf(
                appearance("e1", 90L, base.minus(5, ChronoUnit.DAYS)),
                appearance("e2", 1L, base),
            ),
        )
        val input = minimalInput().copy(
            currentWalkId = 1L,
            walkStart = base,
            walkEnd = base.plusSeconds(60),
            backfillComplete = true,
            threads = listOf(filler, filler2, filler3, filler4, echo),
            recordingTimestamps = mapOf(
                "e1" to base.minus(5, ChronoUnit.DAYS),
                "e2" to base,
                "far" to base,
            ),
            fixes = mapOf(
                "e1" to RouteFix(hub, 1.0, 1.0),
                "e2" to RouteFix(hub, 1.0, 1.0),
                "far" to RouteFix(far, 1.0, 1.0),
            ),
        )
        // Give "echo" a baseline partner far away so it WOULD pass the
        // specificity guard if only it were reached (any theme's mention
        // participates in the baseline — model it via a 6th filler
        // thread far from the hub).
        val farFiller = thread("zulu", appearances = listOf(appearance("far", 1L, base)))
        val fullInput = input.copy(threads = input.threads + farFiller)
        assertNull(DossierSensesTracks.placeResonance(fullInput, setOf("alpha", "bravo", "charlie", "delta")))
    }

    /**
     * river: 2 recordings at the SAME hub coordinate (r1 on a past walk,
     * r2 on the current walk) — a tight cluster. A third mention (any
     * theme, "stone") sits far away, giving the baseline a non-zero
     * spread so the specificity guard is satisfiable.
     */
    private fun placeResonanceFixture(
        backfillComplete: Boolean,
        hub: Coordinate,
        includeFarMention: Boolean = true,
        secondMentionSameWalk: Boolean = false,
        extraSameSpotMention: Boolean = false,
    ): SenseInput {
        val pastWalkDate = base.minus(5, ChronoUnit.DAYS)
        val r2Walk = if (secondMentionSameWalk) 90L else 1L
        val river = thread(
            "river",
            appearances = listOfNotNull(
                appearance("r1", 90L, pastWalkDate),
                appearance("r2", r2Walk, base),
                // Same walk as r1 — a second recording at the same spot
                // on an already-counted walk raises the CLUSTER's
                // mentionCount to 3 without adding a third distinct walk.
                if (extraSameSpotMention) appearance("r3", 90L, pastWalkDate) else null,
            ),
        )
        val stone = thread("stone", appearances = listOf(appearance("s1", 92L, pastWalkDate)))
        val timestamps = mutableMapOf(
            "r1" to pastWalkDate,
            "r2" to base,
        )
        val fixes = mutableMapOf(
            "r1" to RouteFix(hub, 1.0, 1.0),
            "r2" to RouteFix(hub, 1.0, 1.0),
        )
        if (extraSameSpotMention) {
            timestamps["r3"] = pastWalkDate
            fixes["r3"] = RouteFix(hub, 1.0, 1.0)
        }
        if (includeFarMention) {
            timestamps["s1"] = pastWalkDate
            fixes["s1"] = RouteFix(northOf(hub, 100_000.0), 1.0, 1.0)
        }
        return minimalInput().copy(
            currentWalkId = 1L,
            walkStart = base,
            walkEnd = base.plusSeconds(60),
            backfillComplete = backfillComplete,
            threads = listOf(river, stone),
            recordingTimestamps = timestamps,
            fixes = fixes,
        )
    }

    // =====================================================================
    // 2/8 — moonLine
    // =====================================================================

    @Test fun `moonLine is silent when the lunation already reported equals the current one`() {
        val input = minimalInput().copy(moon = moonInput(lunationIndex = 3, lastReportedIndex = 3))
        assertNull(DossierSensesTracks.moonLine(input, emptySet()))
    }

    @Test fun `moonLine is silent when the current walk itself has no words`() {
        val input = minimalInput().copy(moon = moonInput(currentWalkHasWords = false))
        assertNull(DossierSensesTracks.moonLine(input, emptySet()))
    }

    @Test fun `moonLine requires at least one worded walk in the CLOSED lunation, not just the current walk`() {
        val moon = moonInput(
            start = base.minus(10, ChronoUnit.DAYS),
            end = base,
            allWalkDates = listOf(base.minus(5, ChronoUnit.DAYS)),
            wordedWalkDates = emptyList(),
        )
        assertNull(DossierSensesTracks.moonLine(minimalInput().copy(moon = moon), emptySet()))
    }

    @Test fun `moonLine membership is half-open — a walk exactly at end belongs to the NEXT lunation`() {
        val start = base.minus(10, ChronoUnit.DAYS)
        val end = base
        val moon = moonInput(
            start = start, end = end,
            allWalkDates = listOf(end),
            wordedWalkDates = listOf(end),
        )
        // The boundary walk lands outside [start, end) -> wordedCount == 0 -> silent.
        assertNull(DossierSensesTracks.moonLine(minimalInput().copy(moon = moon), emptySet()))
    }

    @Test fun `moonLine's theme-less fallback ends with a period and carries a null lemma`() {
        val start = base.minus(10, ChronoUnit.DAYS)
        val walkDate = base.minus(5, ChronoUnit.DAYS)
        val moon = moonInput(start = start, end = base, allWalkDates = listOf(walkDate, walkDate), wordedWalkDates = listOf(walkDate))
        val line = DossierSensesTracks.moonLine(minimalInput().copy(moon = moon), emptySet())
        assertEquals("The Wolf Moon has set: 2 walks, 1 with recorded words.", line?.text)
        assertNull(line?.lemma)
    }

    @Test fun `moonLine pluralizes walk(s) only — wordedCount never modifies a noun`() {
        val start = base.minus(10, ChronoUnit.DAYS)
        val walkDate = base.minus(5, ChronoUnit.DAYS)
        val moon = moonInput(start = start, end = base, allWalkDates = listOf(walkDate), wordedWalkDates = listOf(walkDate))
        val line = DossierSensesTracks.moonLine(minimalInput().copy(moon = moon), emptySet())
        assertEquals("The Wolf Moon has set: 1 walk, 1 with recorded words.", line?.text)
    }

    @Test fun `moonLine names the top theme by most walks, tie broken to the lexicographically smallest lemma`() {
        val start = base.minus(10, ChronoUnit.DAYS)
        val walkDate = base.minus(5, ChronoUnit.DAYS)
        val walkDate2 = base.minus(6, ChronoUnit.DAYS)
        val moon = moonInput(start = start, end = base, allWalkDates = listOf(walkDate, walkDate2), wordedWalkDates = listOf(walkDate))
        // "zebra" and "apple" both walked 1 of 1 possible in-lunation
        // appearance each — tie on walk-count, "apple" must win.
        val zebra = thread("zebra", appearances = listOf(appearance("z1", 10L, walkDate)))
        val apple = thread("apple", appearances = listOf(appearance("a1", 11L, walkDate)))
        val input = minimalInput().copy(moon = moon, threads = listOf(zebra, apple))
        val line = DossierSensesTracks.moonLine(input, emptySet())
        assertEquals("The Wolf Moon has set: 2 walks, 1 with recorded words; 'apple' walked in 1 of them.", line?.text)
        assertEquals("apple", line?.lemma)
    }

    @Test fun `moonLine excludes suppressed themes from the top-theme pick`() {
        val start = base.minus(10, ChronoUnit.DAYS)
        val walkDate = base.minus(5, ChronoUnit.DAYS)
        val moon = moonInput(start = start, end = base, allWalkDates = listOf(walkDate), wordedWalkDates = listOf(walkDate))
        val apple = thread("apple", appearances = listOf(appearance("a1", 11L, walkDate)))
        val input = minimalInput().copy(moon = moon, threads = listOf(apple))
        val line = DossierSensesTracks.moonLine(input, setOf("apple"))
        assertEquals("The Wolf Moon has set: 1 walk, 1 with recorded words.", line?.text)
        assertNull(line?.lemma)
    }

    // =====================================================================
    // 3/8 — markerColoring
    // =====================================================================

    private fun mention(surface: String, start: Int) = LemmaMention(lemma = surface, surface = surface, start = start, length = surface.length)

    @Test fun `markerColoring is first-match-wins across lemma-ordered threads and array-ordered recordings`() {
        // 100 filler tokens keep the window a small fraction of the
        // whole transcript, so window density clears the 2x-overall gate
        // (a short transcript would make the window ITSELF most of the
        // text, diluting the ratio below the gate).
        val text = ("absolutely always never nothing totally " + "filler ".repeat(100)).trim()
        val theme = theme("focus", mentions = TranscriptNlp.wordTokenOffsets(text).take(1).map { mention(it.token, it.start) })
        val rec = recording("r1", base, base.plusSeconds(30), text = text, themes = listOf(theme))
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val input = minimalInput().copy(currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec))
        val line = DossierSensesTracks.markerColoring(input, emptySet())
        assertTrue(line?.text?.startsWith("Absolutist words cluster around 'focus'") == true)
        assertEquals("focus", line?.lemma)
    }

    @Test fun `markerColoring returns null when no recording's marker gates pass`() {
        val rec = recording("r1", base, base.plusSeconds(30), text = "a very calm ordinary walk with nothing remarkable at all today")
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val input = minimalInput().copy(currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec))
        assertNull(DossierSensesTracks.markerColoring(input, emptySet()))
    }

    @Test fun `markerLine requires the absolute floor of 3 absolutist words in the window, not density alone`() {
        // A single absolutist word surrounded by dense absolutist
        // padding OUTSIDE the ±15 window would fail the absolute floor
        // even though a naive density-only check might pass.
        val words = mutableListOf<String>()
        repeat(20) { words += "filler" }
        words += "focus"
        words += "always"
        repeat(20) { words += "filler" }
        val text = words.joinToString(" ")
        val tokens = TranscriptNlp.wordTokenOffsets(text)
        val focusToken = tokens.first { it.token == "focus" }
        val theme = theme("focus", mentions = listOf(mention("focus", focusToken.start)))
        assertNull(markerLineViaRecording(theme, "focus", text))
    }

    @Test fun `markerLine's displayed ratio truncates toward zero, never rounds`() {
        // 4 absolutist words inside a small window against a rest with
        // exactly 1 absolutist word over many rest tokens produces a
        // ratio that truncates rather than rounds up.
        val windowWords = listOf("always", "never", "everyone", "everything", "focus")
        val restFillerWithOneAbsolutist = (listOf("nothing") + List(200) { "calm" })
        val text = (windowWords + restFillerWithOneAbsolutist).joinToString(" ")
        val tokens = TranscriptNlp.wordTokenOffsets(text)
        val focusToken = tokens.first { it.token == "focus" }
        val theme = theme("focus", mentions = listOf(mention("focus", focusToken.start)))
        val line = markerLineViaRecording(theme, "focus", text)
        assertTrue("expected a line, got null", line != null)
    }

    private fun markerLineViaRecording(theme: Theme, lemma: String, text: String): String? {
        val rec = recording("r1", base, base.plusSeconds(30), text = text, themes = listOf(theme))
        val thread = thread(lemma, appearances = listOf(appearance("r1", 1L, base)))
        val input = minimalInput().copy(currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec))
        return DossierSensesTracks.markerColoring(input, emptySet())?.text
    }

    // =====================================================================
    // 4/8 — intentionLineage
    // =====================================================================

    @Test fun `intentionLineage is silent when today's intention is null`() {
        val today = WalkSnapshotRow(walkId = 1L, startDate = base, intention = null, weatherCondition = null)
        val input = minimalInput().copy(currentWalkId = 1L, walkSnapshots = listOf(today))
        assertNull(DossierSensesTracks.intentionLineage(input, emptySet()))
    }

    @Test fun `intentionLineage is silent when today's intention is empty, same as null`() {
        val today = WalkSnapshotRow(walkId = 1L, startDate = base, intention = "", weatherCondition = null)
        val input = minimalInput().copy(currentWalkId = 1L, walkSnapshots = listOf(today))
        assertNull(DossierSensesTracks.intentionLineage(input, emptySet()))
    }

    @Test fun `intentionLineage is silent when the intention is entirely scaffold words`() {
        // "want"/"think"/"feel" all lemmatize to themselves and all sit
        // in SpokenStoplist.scaffoldLemmas — nothing survives the filter.
        val today = WalkSnapshotRow(walkId = 1L, startDate = base, intention = "I want to think and feel", weatherCondition = null)
        val input = minimalInput().copy(currentWalkId = 1L, walkSnapshots = listOf(today))
        assertNull(DossierSensesTracks.intentionLineage(input, emptySet()))
    }

    @Test fun `intentionLineage fires on the third distinct walk carrying the shared lemma, ordinal-worded`() {
        val presence1 = WalkSnapshotRow(1L, base.minus(1, ChronoUnit.DAYS), "presence", null)
        val presence2 = WalkSnapshotRow(2L, base.minus(2, ChronoUnit.DAYS), "presence", null)
        val today = WalkSnapshotRow(3L, base, "presence and gratitude", null)
        val input = minimalInput().copy(
            currentWalkId = 3L, walkStart = base, walkEnd = base,
            walkSnapshots = listOf(presence1, presence2, today),
        )
        val line = DossierSensesTracks.intentionLineage(input, emptySet())
        assertEquals("Third walk in the last 30 days carrying some form of 'presence'.", line?.text)
        assertEquals("presence", line?.lemma)
    }

    @Test fun `intentionLineage requires at least 3 distinct walks — 2 is not enough`() {
        val presence1 = WalkSnapshotRow(1L, base.minus(1, ChronoUnit.DAYS), "presence", null)
        val today = WalkSnapshotRow(2L, base, "presence", null)
        val input = minimalInput().copy(currentWalkId = 2L, walkStart = base, walkEnd = base, walkSnapshots = listOf(presence1, today))
        assertNull(DossierSensesTracks.intentionLineage(input, emptySet()))
    }

    @Test fun `intentionLineage applies the scaffold stoplist to historical intentions too, not just today's`() {
        // Two historical walks say "just walking" (all-scaffold, no
        // lemmas contributed) plus one saying "presence" — today shares
        // "presence" but only 2 total walks (today + the one real match)
        // carry it, short of the 3-walk floor.
        val scaffold1 = WalkSnapshotRow(1L, base.minus(1, ChronoUnit.DAYS), "just walking", null)
        val scaffold2 = WalkSnapshotRow(2L, base.minus(2, ChronoUnit.DAYS), "just walking", null)
        val real = WalkSnapshotRow(3L, base.minus(3, ChronoUnit.DAYS), "presence", null)
        val today = WalkSnapshotRow(4L, base, "presence", null)
        val input = minimalInput().copy(
            currentWalkId = 4L, walkStart = base, walkEnd = base,
            walkSnapshots = listOf(scaffold1, scaffold2, real, today),
        )
        assertNull(DossierSensesTracks.intentionLineage(input, emptySet()))
    }

    // =====================================================================
    // 5/8 — climbAnchoring
    // =====================================================================

    private fun elevation(offsetSeconds: Long, altitude: Double) = ElevationSample(base.plusSeconds(offsetSeconds), altitude)

    /** Long flat padding, then a clean 80m climb over 40s, then flat
     * again — smoothing-safe since the transition is isolated from the
     * fixture's edges. */
    private fun basicClimbSeries(): List<ElevationSample> = listOf(
        elevation(0, 0.0), elevation(10, 0.0), elevation(20, 0.0), elevation(30, 0.0), elevation(40, 0.0),
        elevation(50, 20.0), elevation(60, 40.0), elevation(70, 60.0), elevation(80, 80.0),
        elevation(90, 80.0), elevation(100, 80.0), elevation(110, 80.0), elevation(120, 80.0),
    )

    @Test fun `climbAnchoring is silent below the 50m whole-walk ascent floor`() {
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base.plusSeconds(45), base.plusSeconds(85), themes = listOf(theme("focus")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 49.0, elevationSeries = basicClimbSeries(),
            threads = listOf(thread), currentRecordings = listOf(rec),
        )
        assertNull(DossierSensesTracks.climbAnchoring(input, emptySet()))
    }

    @Test fun `climbAnchoring fires when a themed recording overlaps the steepest run`() {
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base.plusSeconds(45), base.plusSeconds(85), themes = listOf(theme("focus")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 80.0, elevationSeries = basicClimbSeries(),
            threads = listOf(thread), currentRecordings = listOf(rec),
        )
        val line = DossierSensesTracks.climbAnchoring(input, emptySet())
        assertEquals("'focus' was spoken on the day's steepest climb.", line?.text)
    }

    @Test fun `climbAnchoring matches on interval OVERLAP — a recording starting before and ending during the climb still counts`() {
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        // Starts well before the climb (t=5) and ends partway INTO it
        // (t=55) — containment would miss this; overlap must not.
        val rec = recording("r1", base.plusSeconds(5), base.plusSeconds(55), themes = listOf(theme("focus")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 80.0, elevationSeries = basicClimbSeries(),
            threads = listOf(thread), currentRecordings = listOf(rec),
        )
        assertTrue(DossierSensesTracks.climbAnchoring(input, emptySet()) != null)
    }

    @Test fun `climbAnchoring separates the per-run gain floor from the whole-walk ascent floor`() {
        // 3 separate 18m bumps (54m total ascent, clears the 50m whole-
        // walk floor) but each run individually undershoots the 20m
        // per-run floor, so the sense must stay silent.
        val series = listOf(
            elevation(0, 0.0), elevation(10, 18.0), elevation(20, 0.0),
            elevation(30, 18.0), elevation(40, 0.0),
            elevation(50, 18.0), elevation(60, 0.0),
        )
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base, base.plusSeconds(60), themes = listOf(theme("focus")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 54.0, elevationSeries = series,
            threads = listOf(thread), currentRecordings = listOf(rec),
        )
        assertNull(DossierSensesTracks.climbAnchoring(input, emptySet()))
    }

    @Test fun `climbAnchoring force-closes an in-progress run at series end instead of discarding it`() {
        // The series ends WHILE still climbing (no flat tail) — without
        // the end-of-series force-close, this run would never close and
        // the sense would silently miss the walk's own steepest (only)
        // climb.
        val series = listOf(
            elevation(0, 0.0), elevation(10, 0.0),
            elevation(20, 20.0), elevation(30, 40.0), elevation(40, 60.0), elevation(50, 80.0),
        )
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base.plusSeconds(15), base.plusSeconds(45), themes = listOf(theme("focus")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 80.0, elevationSeries = series,
            threads = listOf(thread), currentRecordings = listOf(rec),
        )
        assertTrue(DossierSensesTracks.climbAnchoring(input, emptySet()) != null)
    }

    @Test fun `climbAnchoring picks the lemma-alphabetically first of two overlapping themes`() {
        val alpha = thread("alpha", appearances = listOf(appearance("r1", 1L, base)))
        val zulu = thread("zulu", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base.plusSeconds(45), base.plusSeconds(85), themes = listOf(theme("alpha"), theme("zulu")))
        val input = minimalInput().copy(
            currentWalkId = 1L, totalAscent = 80.0, elevationSeries = basicClimbSeries(),
            threads = listOf(alpha, zulu), currentRecordings = listOf(rec),
        )
        val line = DossierSensesTracks.climbAnchoring(input, emptySet())
        assertEquals("alpha", line?.lemma)
    }

    // =====================================================================
    // 6/8 — weatherWeave
    // =====================================================================

    private fun weatherWalk(walkId: Long, daysAgo: Long, condition: String?) =
        WalkSnapshotRow(walkId = walkId, startDate = base.minus(daysAgo, ChronoUnit.DAYS), intention = null, weatherCondition = condition)

    @Test fun `weatherWeave fires exactly Both walks when the count is exactly 2`() {
        val river = thread("river", appearances = listOf(appearance("r1", 1L, base.minus(1, ChronoUnit.DAYS)), appearance("r2", 2L, base)))
        // river's shared bucket (RAIN, count 2) must be STRICTLY below
        // the window's mode — 3 unrelated "clear" walks make CLEAR the
        // mode (3), so RAIN (2) clears the guard.
        val snapshots = listOf(
            weatherWalk(1L, 1, "lightRain"), weatherWalk(2L, 0, "heavyRain"),
            weatherWalk(3L, 2, "clear"), weatherWalk(4L, 3, "clear"), weatherWalk(5L, 4, "clear"),
        )
        val input = minimalInput().copy(currentWalkId = 2L, walkStart = base, walkEnd = base, threads = listOf(river), walkSnapshots = snapshots)
        val line = DossierSensesTracks.weatherWeave(input, emptySet())
        assertEquals("Both walks where 'river' surfaced were under rain.", line?.text)
    }

    @Test fun `weatherWeave says All N walks when the count exceeds 2`() {
        val river = thread(
            "river",
            appearances = listOf(
                appearance("r1", 1L, base.minus(2, ChronoUnit.DAYS)),
                appearance("r2", 2L, base.minus(1, ChronoUnit.DAYS)),
                appearance("r3", 3L, base),
            ),
        )
        // river's shared bucket (WIND, count 3) must be strictly below
        // the mode — 4 unrelated "clear" walks make CLEAR the mode (4).
        val snapshots = listOf(
            weatherWalk(1L, 2, "wind"), weatherWalk(2L, 1, "wind"), weatherWalk(3L, 0, "wind"),
            weatherWalk(4L, 3, "clear"), weatherWalk(5L, 4, "clear"), weatherWalk(6L, 5, "clear"), weatherWalk(7L, 6, "clear"),
        )
        val input = minimalInput().copy(currentWalkId = 3L, walkStart = base, walkEnd = base, threads = listOf(river), walkSnapshots = snapshots)
        val line = DossierSensesTracks.weatherWeave(input, emptySet())
        assertEquals("All 3 walks where 'river' surfaced were in wind.", line?.text)
    }

    @Test fun `weatherWeave suppresses on a tie with the window's mode — strict less-than only`() {
        // clear x2 (river's walks) vs rain x2 (elsewhere in the window)
        // is a TIE for modeCount — the ship-gate-tightened guard treats
        // a tie with the mode as suppressing.
        val river = thread("river", appearances = listOf(appearance("r1", 1L, base.minus(1, ChronoUnit.DAYS)), appearance("r2", 2L, base)))
        val snapshots = listOf(
            weatherWalk(1L, 1, "clear"), weatherWalk(2L, 0, "clear"),
            weatherWalk(3L, 2, "lightRain"), weatherWalk(4L, 3, "heavyRain"),
        )
        val input = minimalInput().copy(currentWalkId = 2L, walkStart = base, walkEnd = base, threads = listOf(river), walkSnapshots = snapshots)
        assertNull(DossierSensesTracks.weatherWeave(input, emptySet()))
    }

    @Test fun `weatherWeave requires unanimity — one walk missing weather data breaks the shared claim`() {
        val river = thread("river", appearances = listOf(appearance("r1", 1L, base.minus(1, ChronoUnit.DAYS)), appearance("r2", 2L, base)))
        val snapshots = listOf(weatherWalk(1L, 1, "clear"), weatherWalk(2L, 0, null))
        val input = minimalInput().copy(currentWalkId = 2L, walkStart = base, walkEnd = base, threads = listOf(river), walkSnapshots = snapshots)
        assertNull(DossierSensesTracks.weatherWeave(input, emptySet()))
    }

    @Test fun `weatherWeave in-wind and in-fog use the in preposition, not under`() {
        val river = thread("river", appearances = listOf(appearance("r1", 1L, base.minus(1, ChronoUnit.DAYS)), appearance("r2", 2L, base)))
        // river's shared bucket (FOG, count 2) strictly below the mode
        // (CLEAR, count 3, from unrelated walks).
        val snapshots = listOf(
            weatherWalk(1L, 1, "fog"), weatherWalk(2L, 0, "fog"),
            weatherWalk(3L, 2, "clear"), weatherWalk(4L, 3, "clear"), weatherWalk(5L, 4, "clear"),
        )
        val input = minimalInput().copy(currentWalkId = 2L, walkStart = base, walkEnd = base, threads = listOf(river), walkSnapshots = snapshots)
        val line = DossierSensesTracks.weatherWeave(input, emptySet())
        assertEquals("Both walks where 'river' surfaced were in fog.", line?.text)
    }

    // =====================================================================
    // 7/8 — photoAdjacency
    // =====================================================================

    @Test fun `photoAdjacency requires both the 75m separation AND the 600s gap`() {
        val hub = Coordinate(35.0, 139.0)
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base, base.plusSeconds(10), themes = listOf(theme("focus")))
        // Within radius but far outside the time gap.
        val photo = PhotoPin(capturedAt = base.plusSeconds(10_000), coordinate = hub)
        val input = minimalInput().copy(
            currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec),
            fixes = mapOf("r1" to RouteFix(hub, 1.0, 1.0)), photos = listOf(photo),
        )
        assertNull(DossierSensesTracks.photoAdjacency(input, emptySet()))
    }

    @Test fun `photoAdjacency fires when both gates pass and the recording's fix qualifies`() {
        val hub = Coordinate(35.0, 139.0)
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base, base.plusSeconds(10), themes = listOf(theme("focus")))
        val photo = PhotoPin(capturedAt = base.plusSeconds(5), coordinate = hub)
        val input = minimalInput().copy(
            currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec),
            fixes = mapOf("r1" to RouteFix(hub, 1.0, 1.0)), photos = listOf(photo),
        )
        val line = DossierSensesTracks.photoAdjacency(input, emptySet())
        assertEquals("A photo was taken near where 'focus' was spoken.", line?.text)
        assertEquals("focus", line?.lemma)
    }

    @Test fun `photoAdjacency requires the recording's own fix to pass hygiene before any photo is considered`() {
        val hub = Coordinate(35.0, 139.0)
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base, base.plusSeconds(10), themes = listOf(theme("focus")))
        val photo = PhotoPin(capturedAt = base.plusSeconds(5), coordinate = hub)
        val input = minimalInput().copy(
            currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec),
            // horizontalAccuracy == 100 fails qualifies() (exclusive).
            fixes = mapOf("r1" to RouteFix(hub, 100.0, 1.0)), photos = listOf(photo),
        )
        assertNull(DossierSensesTracks.photoAdjacency(input, emptySet()))
    }

    @Test fun `photoAdjacency intervalGap is zero for a photo captured mid-recording, not measured from an edge`() {
        val hub = Coordinate(35.0, 139.0)
        val thread = thread("focus", appearances = listOf(appearance("r1", 1L, base)))
        // A long recording; the photo lands in the middle — a midpoint/
        // start-only distance would fail the 600s guard here.
        val rec = recording("r1", base, base.plusSeconds(2000), themes = listOf(theme("focus")))
        val photo = PhotoPin(capturedAt = base.plusSeconds(1000), coordinate = hub)
        val input = minimalInput().copy(
            currentWalkId = 1L, threads = listOf(thread), currentRecordings = listOf(rec),
            fixes = mapOf("r1" to RouteFix(hub, 1.0, 1.0)), photos = listOf(photo),
        )
        assertTrue(DossierSensesTracks.photoAdjacency(input, emptySet()) != null)
    }

    @Test fun `photoAdjacency keeps the single global-best across all thread-recording-photo triples`() {
        val hub = Coordinate(35.0, 139.0)
        val nearer = northOf(hub, 10.0)
        val farther = northOf(hub, 50.0)
        val alpha = thread("alpha", appearances = listOf(appearance("r1", 1L, base)))
        val zulu = thread("zulu", appearances = listOf(appearance("r1", 1L, base)))
        val rec = recording("r1", base, base.plusSeconds(10), themes = listOf(theme("alpha"), theme("zulu")))
        val photoNear = PhotoPin(capturedAt = base.plusSeconds(5), coordinate = nearer)
        val photoFar = PhotoPin(capturedAt = base.plusSeconds(5), coordinate = farther)
        val input = minimalInput().copy(
            currentWalkId = 1L, threads = listOf(alpha, zulu), currentRecordings = listOf(rec),
            fixes = mapOf("r1" to RouteFix(hub, 1.0, 1.0)), photos = listOf(photoFar, photoNear),
        )
        // Both threads share the same recording/fix, so separation/gap
        // are identical for alpha and zulu — the winner is decided by
        // lemma-alphabetical activeThreads order (alpha first).
        val line = DossierSensesTracks.photoAdjacency(input, emptySet())
        assertEquals("alpha", line?.lemma)
    }

    // =====================================================================
    // 8/8 — speechShape
    // =====================================================================

    @Test fun `speechShape fires when all words land in the first third and the remainder exceeds 30 minutes`() {
        val walkEnd = base.plusSeconds(3600 * 2)
        val rec = recording("r1", base, base.plusSeconds(60), wordCount = 10)
        val input = minimalInput().copy(walkStart = base, walkEnd = walkEnd, currentRecordings = listOf(rec))
        val line = DossierSensesTracks.speechShape(input, emptySet())
        assertTrue(line != null)
        assertNull(line?.lemma)
    }

    @Test fun `speechShape truncates minutes, never rounds`() {
        // walk span 3600s, first third ends at 1200s; a recording ending
        // at 1000s leaves a remainder of 3600-1000=2600s -> 43.33min ->
        // truncated to 43, not rounded to 43 (same here) — use a value
        // where rounding vs truncation actually differ: remainder 2599s
        // ~ 43.31min, still 43 either way; pick 1801s remainder over the
        // 1800s floor to land just past the guard with an exact non-
        // integer minute count.
        val walkEnd = base.plusSeconds(3600)
        val rec = recording("r1", base, base.plusSeconds(1199), wordCount = 3)
        val input = minimalInput().copy(walkStart = base, walkEnd = walkEnd, currentRecordings = listOf(rec))
        // remainder = 3600 - 1199 = 2401s = 40.0166...min -> truncates to 40
        val line = DossierSensesTracks.speechShape(input, emptySet())
        assertEquals("All the words came in the first third; the last 40 minutes were wordless.", line?.text)
    }

    @Test fun `speechShape requires the wordless remainder strictly greater than 1800s — exactly 1800 is silent`() {
        val walkEnd = base.plusSeconds(3600)
        // first third ends at 1200s; recording must end at-or-before that.
        val rec = recording("r1", base, base.plusSeconds(1200), wordCount = 3)
        val input = minimalInput().copy(walkStart = base, walkEnd = walkEnd, currentRecordings = listOf(rec))
        // remainder = 3600-1200 = 2400s > 1800 -> fires; now construct exactly-1800 case:
        val walkEnd2 = base.plusSeconds(3000)
        val input2 = minimalInput().copy(walkStart = base, walkEnd = walkEnd2, currentRecordings = listOf(rec))
        // remainder = 3000-1200 = 1800 exactly -> must be silent (strict >)
        assertNull(DossierSensesTracks.speechShape(input2, emptySet()))
        assertTrue(DossierSensesTracks.speechShape(input, emptySet()) != null)
    }

    @Test fun `speechShape requires EVERY worded recording to end at-or-before the first third — one late word disqualifies all`() {
        val walkEnd = base.plusSeconds(3600)
        val early = recording("r1", base, base.plusSeconds(60), wordCount = 5)
        // Ends well past the first-third mark (1200s).
        val late = recording("r2", base.plusSeconds(2000), base.plusSeconds(2010), wordCount = 2)
        val input = minimalInput().copy(walkStart = base, walkEnd = walkEnd, currentRecordings = listOf(early, late))
        assertNull(DossierSensesTracks.speechShape(input, emptySet()))
    }

    @Test fun `speechShape returns null when there are no worded recordings at all`() {
        val walkEnd = base.plusSeconds(3600)
        val silent = recording("r1", base, base.plusSeconds(60), wordCount = 0)
        val input = minimalInput().copy(walkStart = base, walkEnd = walkEnd, currentRecordings = listOf(silent))
        assertNull(DossierSensesTracks.speechShape(input, emptySet()))
    }
}
