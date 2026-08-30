// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * String-pinned verbatim template fidelity, ported from
 * `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift` (parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md`, BEH-43..48/UI-32..41).
 * Every string here is pasted directly into an AI prompt — wording,
 * punctuation, decimal precision, and markdown emphasis are FUNCTIONAL.
 */
class ThreadsDossierFormatterTest {

    private val anchor: Instant = Instant.parse("2026-08-20T09:00:00Z")

    private fun markers(
        wordCount: Int,
        absolutistCount: Int = 0,
        firstPersonCount: Int = 0,
        insightCount: Int = 0,
        causationCount: Int = 0,
        discrepancyCount: Int = 0,
        temporalLean: TemporalLean = TemporalLean.PRESENT,
        modalCounts: Map<String, Int> = emptyMap(),
        sentiment: Double? = null,
    ) = TranscriptMarkers(
        wordCount = wordCount,
        absolutistCount = absolutistCount,
        firstPersonCount = firstPersonCount,
        insightCount = insightCount,
        causationCount = causationCount,
        discrepancyCount = discrepancyCount,
        temporalLean = temporalLean,
        modalCounts = modalCounts,
        sentiment = sentiment,
    )

    private fun context(
        uuid: String,
        markers: TranscriptMarkers,
        themes: List<Theme> = emptyList(),
    ) = TranscriptContext(
        uuid = uuid,
        languageCode = "en",
        wordCount = markers.wordCount,
        themes = themes,
        markers = markers,
        transcriptHash = "hash-$uuid",
    )

    private fun theme(lemma: String, mentionCount: Int = 2, salience: Double = 0.1) =
        Theme(lemma = lemma, displayTerm = lemma, mentionCount = mentionCount, salience = salience, mentions = emptyList())

    // --- markerLine: density switch -------------------------------------------

    @Test
    fun `markerLine renders percentage template at or above the density floor`() {
        val m = markers(
            wordCount = 100, absolutistCount = 3, firstPersonCount = 5,
            insightCount = 1, causationCount = 2, discrepancyCount = 4,
            temporalLean = TemporalLean.PAST,
        )
        val line = ThreadsDossierFormatter.markerLine(context("r1", m), baseline = null)
        assertEquals(
            "absolutist words 3.0% over 100 words; self-focus 5.0%; " +
                "insight 1, causation 2, discrepancy 4; " +
                "temporal lean: past (coarse heuristic)",
            line,
        )
    }

    @Test
    fun `markerLine below the density floor uses the structurally different small-sample sentence`() {
        val m = markers(wordCount = 99, absolutistCount = 3, firstPersonCount = 5)
        val line = ThreadsDossierFormatter.markerLine(context("r1", m), baseline = null)
        assertTrue(
            "small-sample raw-counts sentence: $line",
            line.startsWith("99 words — small sample, raw counts only: 3 absolutist, 5 self-focus"),
        )
        assertFalse("no percentage in small-sample branch: $line", line.contains("%"))
    }

    @Test
    fun `markerLine appends baseline clause only when supplied`() {
        val m = markers(wordCount = 200, absolutistCount = 4)
        val withBaseline = ThreadsDossierFormatter.markerLine(
            context("r1", m),
            baseline = PersonalBaseline(absolutist = 0.025, firstPerson = 0.05),
        )
        assertTrue(
            "baseline clause verbatim: $withBaseline",
            withBaseline.contains("absolutist words 2.0% over 200 words (your usual walking baseline ~2.5%)"),
        )
        val withoutBaseline = ThreadsDossierFormatter.markerLine(context("r1", m), baseline = null)
        assertFalse(
            "no baseline clause when none supplied: $withoutBaseline",
            withoutBaseline.contains("baseline"),
        )
    }

    @Test
    fun `markerLine appends sentiment only when present`() {
        val withSentiment = ThreadsDossierFormatter.markerLine(
            context("r1", markers(wordCount = 100, sentiment = 0.256)),
            baseline = null,
        )
        assertTrue("sentiment two-decimal precision: $withSentiment", withSentiment.endsWith("sentiment 0.26"))
        val withoutSentiment = ThreadsDossierFormatter.markerLine(
            context("r1", markers(wordCount = 100, sentiment = null)),
            baseline = null,
        )
        assertFalse("no sentiment clause: $withoutSentiment", withoutSentiment.contains("sentiment"))
    }

    // --- personalBaseline: floor of 5 qualifying prior recordings -------------

    @Test
    fun `personalBaseline is omitted entirely below 5 qualifying recordings`() {
        val contexts = (1..4).map { context("r$it", markers(wordCount = 100, absolutistCount = 1)) }
        assertNull(ThreadsDossierFormatter.personalBaseline(contexts))
    }

    @Test
    fun `personalBaseline computes at exactly 5 qualifying recordings`() {
        val contexts = (1..5).map { context("r$it", markers(wordCount = 100, absolutistCount = 1, firstPersonCount = 2)) }
        val baseline = ThreadsDossierFormatter.personalBaseline(contexts)
        assertEquals(0.01, baseline!!.absolutist, 1e-9)
        assertEquals(0.02, baseline.firstPerson, 1e-9)
    }

    @Test
    fun `personalBaseline excludes recordings below the density floor from qualification`() {
        val qualifying = (1..5).map { context("r$it", markers(wordCount = 100, absolutistCount = 1)) }
        val tooShort = context("short", markers(wordCount = 10, absolutistCount = 100))
        val baseline = ThreadsDossierFormatter.personalBaseline(qualifying + tooShort)
        assertEquals(0.01, baseline!!.absolutist, 1e-9)
    }

    // --- non-English line, literal ---------------------------------------------

    @Test
    fun `markerLine renders the exact non-English replacement line`() {
        // Android's TranscriptContext.markers is non-nullable (U3 tightening
        // — the analyzer writes nothing at all for non-English text, unlike
        // iOS which stores themes with a nil markers field) — this branch
        // is therefore defensive/unreachable in production, ported "for the
        // shape" per the parity spec, keyed off languageCode instead of a
        // markers-nullability check the Kotlin type system can't express.
        val nonEnglish = context("r1", markers(wordCount = 50)).copy(languageCode = "ja")
        assertEquals(
            "Markers unavailable (non-English recording).",
            ThreadsDossierFormatter.markerLine(nonEnglish, baseline = null),
        )
    }

    // --- modal lean: two-part gate ---------------------------------------------

    @Test
    fun `modal lean fires only when count is at least 10 AND rate is at least 2x the walker's own baseline`() {
        // Baseline: 3 prior walks, each contributing "should" x2 over 100
        // words -> rate 0.02, averagePerWalk 2.
        val baselineWalk = { id: Long -> WalkLite(id, anchor.minus(10, ChronoUnit.DAYS), null, null) }
        val baselineContexts = (1..3).map {
            context("baseline-$it", markers(wordCount = 100, modalCounts = mapOf("should" to 2)))
        }
        val walkIndex = (1..3).associate { "baseline-$it" to baselineWalk(it.toLong()).walkId } +
            mapOf("current" to 99L)

        // Current walk: familyCount = 20 ("should" x20) over 200 words ->
        // rate 0.1, which is 5x the 0.02 baseline and >= modalRemarkableMinCount(10).
        val current = context("current", markers(wordCount = 200, modalCounts = mapOf("should" to 20)))

        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(current to null),
            allContexts = baselineContexts + current,
            threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
            currentWalkId = 99L,
            backfillComplete = true,
            walkIdByRecordingUuid = walkIndex,
        )
        assertTrue(
            "modal lean clause with U+00D7 multiplication sign and averagePerWalk: $line",
            line!!.contains("modal lean: obligation — 'should' ×20 (your usual ~2 per walk)"),
        )
    }

    @Test
    fun `modal lean stays silent below the absolute floor of 10`() {
        val walkIndex = mapOf("current" to 99L)
        val current = context("current", markers(wordCount = 200, modalCounts = mapOf("should" to 9)))
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(current to null),
            allContexts = listOf(current),
            threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
            currentWalkId = 99L,
            backfillComplete = true,
            walkIdByRecordingUuid = walkIndex,
        )
        assertFalse("below absolute floor, no modal lean: $line", line!!.contains("modal lean"))
    }

    @Test
    fun `modal lean stays silent with no baseline — first walks never speak here`() {
        val walkIndex = mapOf("current" to 99L)
        val current = context("current", markers(wordCount = 200, modalCounts = mapOf("should" to 50)))
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(current to null),
            allContexts = listOf(current),
            threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
            currentWalkId = 99L,
            backfillComplete = true,
            walkIdByRecordingUuid = walkIndex,
        )
        assertFalse("no baseline (0 prior walks) must not default to a trivially-passing rate: $line", line!!.contains("modal lean"))
    }

    // --- dossier: fixed section order + headers ---------------------------------

    @Test
    fun `dossier renders the always-present header and numbered recording lines`() {
        val a = context("a", markers(wordCount = 100, absolutistCount = 1))
        val b = context("b", markers(wordCount = 100, absolutistCount = 2))
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(a to null, b to null),
            allContexts = listOf(a, b),
            threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertTrue(line!!.startsWith("**Thought threads (on-device linguistic analysis):**"))
        assertTrue(line.contains("\nRecording 1: "))
        assertTrue(line.contains("\nRecording 2: "))
    }

    @Test
    fun `dossier returns null when there are no current recordings`() {
        assertNull(
            ThreadsDossierFormatter.dossier(
                currentRecordings = emptyList(),
                allContexts = emptyList(),
                threads = Threads(active = emptyList(), firstTimeLemmas = emptySet()),
                currentWalkId = 1L,
                backfillComplete = true,
            ),
        )
    }

    @Test
    fun `dossier threads section renders only threads active in the current walk, with singular vs plural`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val walkOld = WalkLite(2L, anchor.minus(5, ChronoUnit.DAYS), null, null)
        val contexts = listOf(
            context("r-now", markers(wordCount = 100), themes = listOf(theme("river"))),
            context("r-old", markers(wordCount = 100), themes = listOf(theme("river"))),
        )
        val threads = ThreadStore.build(
            contexts,
            mapOf("r-now" to walkNow, "r-old" to walkOld),
            anchor,
            backfillComplete = true,
        )
        val current = contexts[0]
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(current to null),
            allContexts = contexts,
            threads = threads,
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertTrue("threads header: $line", line!!.contains("\n\n**Threads across recent walks:**"))
        assertTrue("recurring, 2 walks, plural: $line", line.contains("'river' — 2 walks in the last 30 days"))
    }

    @Test
    fun `dossier threads section singular for exactly one walk in window`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val contexts = listOf(context("r-now", markers(wordCount = 100), themes = listOf(theme("river"))))
        val threads = ThreadStore.build(contexts, mapOf("r-now" to walkNow), anchor, backfillComplete = false)
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(contexts[0] to null),
            allContexts = contexts,
            threads = threads,
            currentWalkId = 1L,
            backfillComplete = false,
        )
        // backfill incomplete + no earlier appearance -> status is null, so
        // neither firstTime nor recurring text renders, but the thread line
        // (quoted term) still appears since it's active in the current walk.
        assertTrue(line!!.contains("\n'river'"))
        assertFalse(line.contains("first appearance"))
        assertFalse(line.contains("walk in the last 30 days"))
        assertFalse(line.contains("walks in the last 30 days"))
    }

    @Test
    fun `dossier first appearance clause is gated on backfillComplete exactly like the quiet section`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val contexts = listOf(context("r-now", markers(wordCount = 100), themes = listOf(theme("river"))))
        val threadsComplete = ThreadStore.build(contexts, mapOf("r-now" to walkNow), anchor, backfillComplete = true)
        val lineComplete = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(contexts[0] to null),
            allContexts = contexts,
            threads = threadsComplete,
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertTrue("firstTime + origin date when complete: $lineComplete", lineComplete!!.contains("first appearance in the record"))
        assertTrue(lineComplete.contains("(first spoken"))
    }

    @Test
    fun `dossier quiet section requires backfillComplete, at least 2 of last 30-day walks, capped at 2, tuple-swap sort`() {
        // Three threads absent from the current walk, each present in a
        // different number of qualifying recent walks; only >=2 qualify,
        // capped to 2, sorted (walks desc, lemma asc).
        val walks = (0..3).map { WalkLite(it.toLong(), anchor.minus((3 - it).toLong(), ChronoUnit.DAYS), null, null) }
        val currentWalk = WalkLite(99L, anchor, null, null)

        fun ctxWithThemes(uuid: String, vararg lemmas: String) =
            context(uuid, markers(wordCount = 100), themes = lemmas.map { theme(it) })

        val contexts = listOf(
            ctxWithThemes("w0", "zephyr", "apple"), // both present in only walk 0 (1 walk -> below floor)
            ctxWithThemes("w1", "apple", "cedar"),
            ctxWithThemes("w2", "cedar"),
            ctxWithThemes("current", "birch"), // current walk mentions an unrelated lemma
        )
        val recordingToWalk = mapOf(
            "w0" to walks[0], "w1" to walks[1], "w2" to walks[2], "current" to currentWalk,
        )
        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = true)
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(contexts.last() to null),
            allContexts = contexts,
            threads = threads,
            currentWalkId = 99L,
            backfillComplete = true,
        )
        assertTrue("quiet header: $line", line!!.contains("\n\n**Quiet this walk:**"))
        // "apple" present in 2 walks (w0, w1); "cedar" present in 2 walks (w1, w2);
        // "zephyr" present in 1 walk (below the floor of 2) — excluded.
        // Tie on walks(2,2) breaks to lemma ascending: "apple" before "cedar".
        val expected = "\nNotably quiet this walk: 'apple' — present in 2 of the walker's recent walks." +
            "\nNotably quiet this walk: 'cedar' — present in 2 of the walker's recent walks."
        assertTrue("exact quiet lines, tuple-swap order, cap 2: $line", line.endsWith(expected))
        assertFalse("zephyr below the floor of 2 must not appear: $line", line.contains("'zephyr'"))
    }

    @Test
    fun `dossier quiet section is omitted when backfill is incomplete`() {
        val walks = (0..1).map { WalkLite(it.toLong(), anchor.minus((1 - it).toLong(), ChronoUnit.DAYS), null, null) }
        val currentWalk = WalkLite(99L, anchor, null, null)
        fun ctxWithThemes(uuid: String, vararg lemmas: String) =
            context(uuid, markers(wordCount = 100), themes = lemmas.map { theme(it) })
        val contexts = listOf(
            ctxWithThemes("w0", "cedar"),
            ctxWithThemes("w1", "cedar"),
            ctxWithThemes("current", "birch"),
        )
        val recordingToWalk = mapOf("w0" to walks[0], "w1" to walks[1], "current" to currentWalk)
        val threads = ThreadStore.build(contexts, recordingToWalk, anchor, backfillComplete = false)
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(contexts.last() to null),
            allContexts = contexts,
            threads = threads,
            currentWalkId = 99L,
            backfillComplete = false,
        )
        assertFalse("quiet section gated on backfillComplete: $line", line!!.contains("**Quiet this walk:**"))
    }

    // --- pace correlation --------------------------------------------------------

    @Test
    fun `pace correlation renders the slower phrasing at or beyond the negative pace threshold`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val themed = context("themed", markers(wordCount = 100), themes = listOf(theme("river")))
        val rest = context("rest", markers(wordCount = 100))
        val threads = ThreadStore.build(
            listOf(themed, rest),
            mapOf("themed" to walkNow, "rest" to walkNow),
            anchor,
            backfillComplete = true,
        )
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(themed to 100.0, rest to 130.0),
            allContexts = listOf(themed, rest),
            threads = threads,
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertTrue(
            "exact slower phrasing, leading comma-space, no capital: $line",
            line!!.contains(", spoken more slowly than the rest of this walk"),
        )
    }

    @Test
    fun `pace correlation renders the quicker phrasing at or beyond the positive pace threshold`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val themed = context("themed", markers(wordCount = 100), themes = listOf(theme("river")))
        val rest = context("rest", markers(wordCount = 100))
        val threads = ThreadStore.build(
            listOf(themed, rest),
            mapOf("themed" to walkNow, "rest" to walkNow),
            anchor,
            backfillComplete = true,
        )
        val line = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(themed to 130.0, rest to 100.0),
            allContexts = listOf(themed, rest),
            threads = threads,
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertTrue(
            "exact quicker phrasing: $line",
            line!!.contains(", spoken more quickly than the rest of this walk"),
        )
    }

    @Test
    fun `pace correlation is nil inside the band and requires both sides non-empty and a positive rest mean`() {
        val walkNow = WalkLite(1L, anchor, null, null)
        val themed = context("themed", markers(wordCount = 100), themes = listOf(theme("river")))
        val rest = context("rest", markers(wordCount = 100))
        val threads = ThreadStore.build(
            listOf(themed, rest),
            mapOf("themed" to walkNow, "rest" to walkNow),
            anchor,
            backfillComplete = true,
        )
        val insideBand = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(themed to 105.0, rest to 100.0),
            allContexts = listOf(themed, rest),
            threads = threads,
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertFalse("inside +-0.15 band renders no clause: $insideBand", insideBand!!.contains("spoken more"))

        val onlyThemed = ThreadsDossierFormatter.dossier(
            currentRecordings = listOf(themed to 130.0),
            allContexts = listOf(themed),
            threads = ThreadStore.build(listOf(themed), mapOf("themed" to walkNow), anchor, true),
            currentWalkId = 1L,
            backfillComplete = true,
        )
        assertFalse("empty rest side -> no correlation clause: $onlyThemed", onlyThemed!!.contains("spoken more"))
    }
}
