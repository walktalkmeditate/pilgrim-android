// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Personal per-word rates over a walker's own qualifying recording history. */
data class PersonalBaseline(val absolutist: Double, val firstPerson: Double)

/** A modal family's rate across the walker's OWN qualifying prior walks. */
data class ModalBaselineEntry(val rate: Double, val averagePerWalk: Double)

/**
 * Renders the AI-prompt dossier text, ported from
 * `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift` at the pinned
 * iOS commit (parity spec `docs/parity/2026-08-25-threads-engine-port.md`,
 * BEH-43..48/UI-32..41). Every string here is pasted directly into an AI
 * prompt — wording, punctuation, decimal precision, and markdown emphasis
 * are FUNCTIONAL, not cosmetic; a "cleanup" that rewords any of them
 * changes what the model reads verbatim.
 */
object ThreadsDossierFormatter {

    const val DENSITY_FLOOR_WORDS = 100
    const val BASELINE_FLOOR_RECORDINGS = 5
    const val MINIMUM_ABSENCE_WALKS = 2
    const val MAX_ABSENCE_LINES = 2
    const val PACE_DIFFERENCE_THRESHOLD = 0.15
    const val MODAL_BASELINE_FLOOR_WALKS = 3
    const val MODAL_REMARKABLE_MIN_COUNT = 10
    const val MODAL_REMARKABLE_RATE_MULTIPLE = 2.0
    private val ABSENCE_WINDOW: Duration = Duration.ofDays(30)

    private const val ENGLISH = "en"
    private val shortDateFormatter: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /**
     * One recording's marker line. The ENTIRE line is replaced by the
     * literal non-English placeholder when [TranscriptContext.languageCode]
     * isn't English — Android's [TranscriptContext.markers] is
     * non-nullable (an already-shipped U3 tightening: the analyzer writes
     * nothing at all for non-English text, unlike iOS which stores a
     * context with a nil `markers` field), so this branch is defensive/
     * unreachable in production today; it is ported anyway for the shape
     * per the parity spec, keyed off [TranscriptContext.languageCode]
     * since Kotlin's non-null [TranscriptContext.markers] can't express
     * iOS's `markers == nil` check directly.
     */
    fun markerLine(context: TranscriptContext, baseline: PersonalBaseline?): String {
        if (context.languageCode != ENGLISH) return "Markers unavailable (non-English recording)."
        val markers = context.markers
        val parts = mutableListOf<String>()

        if (markers.wordCount >= DENSITY_FLOOR_WORDS) {
            val absolutist = markers.absolutistCount.toDouble() / markers.wordCount * 100
            var absolutistPart =
                String.format(Locale.US, "absolutist words %.1f%% over %d words", absolutist, markers.wordCount)
            if (baseline != null) {
                absolutistPart += String.format(
                    Locale.US,
                    " (your usual walking baseline ~%.1f%%)",
                    baseline.absolutist * 100,
                )
            }
            parts += absolutistPart
            val firstPerson = markers.firstPersonCount.toDouble() / markers.wordCount * 100
            parts += String.format(Locale.US, "self-focus %.1f%%", firstPerson)
        } else {
            parts += "${markers.wordCount} words — small sample, raw counts only: " +
                "${markers.absolutistCount} absolutist, ${markers.firstPersonCount} self-focus"
        }
        parts += "insight ${markers.insightCount}, causation ${markers.causationCount}, " +
            "discrepancy ${markers.discrepancyCount}"
        // temporalLean is nullable on the Kotlin model (the analyzer's own
        // English gate makes it non-null whenever we reach this line in
        // practice) — the PRESENT fallback below is defensive only, never
        // reached when languageCode == "en" produced this context.
        parts += "temporal lean: ${temporalLeanLabel(markers.temporalLean ?: TemporalLean.PRESENT)} (coarse heuristic)"
        markers.sentiment?.let { parts += String.format(Locale.US, "sentiment %.2f", it) }

        return parts.joinToString(separator = "; ")
    }

    /** iOS's `MarkerAnalyzer`/`MarkerLexicons` string enums use the lowercase
     * case name as their rawValue; Kotlin enum constants are conventionally
     * UPPER_SNAKE_CASE, so every raw label needs this one exception — the
     * "balanced"/PRESENT naming divergence is documented on [TranscriptMarkers]. */
    private fun temporalLeanLabel(lean: TemporalLean): String =
        if (lean == TemporalLean.PRESENT) "balanced" else lean.name.lowercase(Locale.ROOT)

    /**
     * Personal baseline over [contexts] — omitted ENTIRELY (never a
     * placeholder) below [BASELINE_FLOOR_RECORDINGS] qualifying prior
     * recordings, each of which must independently clear
     * [DENSITY_FLOOR_WORDS] the same way a single recording's own line
     * does.
     */
    fun personalBaseline(contexts: List<TranscriptContext>): PersonalBaseline? {
        val qualifying = contexts.filter { it.languageCode == ENGLISH && it.markers.wordCount >= DENSITY_FLOOR_WORDS }
        if (qualifying.size < BASELINE_FLOOR_RECORDINGS) return null
        val totalWords = qualifying.sumOf { it.markers.wordCount }
        if (totalWords <= 0) return null
        return PersonalBaseline(
            absolutist = qualifying.sumOf { it.markers.absolutistCount }.toDouble() / totalWords,
            firstPerson = qualifying.sumOf { it.markers.firstPersonCount }.toDouble() / totalWords,
        )
    }

    private data class ModalLeanSummary(
        val family: ModalFamily,
        val word: String,
        val count: Int,
        val familyCount: Int,
        val familyRate: Double,
    )

    /**
     * Today's dominant modal family and its dominant surface word, summed
     * across the WALK's recordings (not one recording at a time — the
     * clause speaks once per walk). Deterministic ties: [ModalFamily.entries]
     * and each family's word array ([MarkerLexicons.modalFamilies]) are
     * declaration-ordered, and only a STRICTLY greater count replaces the
     * running best (BEH-44).
     */
    private fun modalLeanSummary(contexts: List<TranscriptContext>): ModalLeanSummary? {
        val totalWords = contexts.sumOf { it.wordCount }
        if (totalWords <= 0) return null

        val familyTotals = HashMap<ModalFamily, Int>()
        val wordTotals = HashMap<String, Int>()
        for (context in contexts) {
            if (context.languageCode != ENGLISH) continue
            for ((word, count) in context.markers.modalCounts) {
                wordTotals[word] = (wordTotals[word] ?: 0) + count
                MarkerLexicons.modalFamily(word)?.let { family ->
                    familyTotals[family] = (familyTotals[family] ?: 0) + count
                }
            }
        }

        var dominantFamily: ModalFamily? = null
        var dominantFamilyCount = 0
        for (family in ModalFamily.entries) {
            val count = familyTotals[family] ?: 0
            if (count > 0 && (dominantFamily == null || count > dominantFamilyCount)) {
                dominantFamily = family
                dominantFamilyCount = count
            }
        }
        val family = dominantFamily ?: return null

        var dominantWord: String? = null
        var dominantWordCount = 0
        for (word in MarkerLexicons.modalFamilies[family].orEmpty()) {
            val count = wordTotals[word] ?: 0
            if (count > 0 && (dominantWord == null || count > dominantWordCount)) {
                dominantWord = word
                dominantWordCount = count
            }
        }
        val word = dominantWord ?: return null

        return ModalLeanSummary(
            family = family,
            word = word,
            count = dominantWordCount,
            familyCount = dominantFamilyCount,
            familyRate = dominantFamilyCount.toDouble() / totalWords,
        )
    }

    /**
     * Per-family baseline, grouped by WALK ([MODAL_BASELINE_FLOOR_WALKS]
     * prior, contexted walks required via [walkIdByRecordingUuid]) rather
     * than by recording — a state lean is a per-walk fact, not a
     * per-recording one (BEH-46).
     */
    fun modalBaseline(
        contexts: List<TranscriptContext>,
        walkIdByRecordingUuid: Map<String, Long>,
        excludingWalkId: Long,
    ): Map<ModalFamily, ModalBaselineEntry>? {
        val qualifying = contexts.filter { context ->
            context.languageCode == ENGLISH &&
                context.markers.wordCount >= DENSITY_FLOOR_WORDS &&
                walkIdByRecordingUuid[context.uuid]?.let { it != excludingWalkId } == true
        }
        val walksRepresented = qualifying.mapNotNull { walkIdByRecordingUuid[it.uuid] }.toSet()
        if (walksRepresented.size < MODAL_BASELINE_FLOOR_WALKS) return null
        val totalWords = qualifying.sumOf { it.wordCount }
        if (totalWords <= 0) return null

        return ModalFamily.entries.associateWith { family ->
            val words = MarkerLexicons.modalFamilies[family].orEmpty()
            val total = qualifying.sumOf { context -> words.sumOf { context.markers.modalCounts[it] ?: 0 } }
            ModalBaselineEntry(
                rate = total.toDouble() / totalWords,
                averagePerWalk = total.toDouble() / walksRepresented.size,
            )
        }
    }

    /**
     * At most one clause, naming the dominant modal family and word — a
     * state signal, not a topic. Silent by default: fires only when the
     * dominant family is both large on its own terms
     * ([MODAL_REMARKABLE_MIN_COUNT]) AND elevated against the walker's
     * own per-walk baseline rate ([MODAL_REMARKABLE_RATE_MULTIPLE]). No
     * baseline at all means silence, never a fallback phrasing — first
     * walks never speak here (BEH-45).
     */
    private fun modalLeanLine(
        currentRecordings: List<Pair<TranscriptContext, Double?>>,
        allContexts: List<TranscriptContext>,
        walkIdByRecordingUuid: Map<String, Long>,
        currentWalkId: Long,
    ): String? {
        val summary = modalLeanSummary(currentRecordings.map { it.first })
            ?.takeIf { it.familyCount >= MODAL_REMARKABLE_MIN_COUNT }
            ?: return null
        val baseline = modalBaseline(allContexts, walkIdByRecordingUuid, currentWalkId) ?: return null
        val entry = baseline[summary.family]?.takeIf { it.rate > 0 } ?: return null
        if (summary.familyRate < MODAL_REMARKABLE_RATE_MULTIPLE * entry.rate) return null
        return "modal lean: ${summary.family.name.lowercase(Locale.ROOT)} — '${summary.word}' " +
            "×${summary.count} (your usual ~${entry.averagePerWalk.roundToInt()} per walk)"
    }

    /**
     * The full dossier text for one walk, or `null` when there are no
     * current recordings — [ThreadsDossierBuilder] never calls this when
     * the toggle is off; the empty-recordings guard is this function's
     * own. Fixed section order: header + per-recording marker lines +
     * optional modal-lean trailer, then optional "Threads across recent
     * walks", then optional "Quiet this walk" — concatenated with `\n\n`
     * between top-level sections (UI-38).
     */
    fun dossier(
        currentRecordings: List<Pair<TranscriptContext, Double?>>,
        allContexts: List<TranscriptContext>,
        threads: Threads,
        currentWalkId: Long,
        backfillComplete: Boolean,
        walkIdByRecordingUuid: Map<String, Long> = emptyMap(),
    ): String? {
        if (currentRecordings.isEmpty()) return null
        val baseline = personalBaseline(allContexts)

        val section = StringBuilder("**Thought threads (on-device linguistic analysis):**")
        currentRecordings.forEachIndexed { index, (context, _) ->
            section.append("\nRecording ${index + 1}: ").append(markerLine(context, baseline))
        }
        modalLeanLine(currentRecordings, allContexts, walkIdByRecordingUuid, currentWalkId)?.let {
            section.append("\n").append(it)
        }

        val activeThreads = threads.active.filter { thread -> thread.appearances.any { it.walkId == currentWalkId } }
        if (activeThreads.isNotEmpty()) {
            section.append("\n\n**Threads across recent walks:**")
            for (thread in activeThreads) {
                section.append(threadLine(thread, currentRecordings, currentWalkId, backfillComplete))
            }
        }

        if (backfillComplete) {
            quietLines(threads.active, currentWalkId)?.let { quiet ->
                section.append("\n\n**Quiet this walk:**").append(quiet)
            }
        }
        return section.toString()
    }

    /**
     * Per-thread line: quoted term + up to four independently-optional
     * clauses with literal connectors (UI-39/EDG-76). `backfillComplete`
     * gates BOTH the "(first spoken …)" clause here and the whole Quiet
     * section for the same reason — an absence claim is as risky as an
     * origin claim.
     */
    private fun threadLine(
        thread: ActiveThread,
        currentRecordings: List<Pair<TranscriptContext, Double?>>,
        currentWalkId: Long,
        backfillComplete: Boolean,
    ): String {
        val line = StringBuilder("\n'${thread.displayTerm}'")
        when (val status = ThreadStore.status(thread, currentWalkId, backfillComplete)) {
            is ThreadStatus.FirstTime -> line.append(" — first appearance in the record")
            is ThreadStatus.Recurring -> {
                val walks = status.walksInWindow
                line.append(" — $walks walk${if (walks == 1) "" else "s"} in the last 30 days")
            }
            null -> Unit
        }
        ThreadStore.salienceDirection(thread)?.let { direction ->
            line.append(", ${direction.name.lowercase(Locale.ROOT)} across appearances")
        }
        if (backfillComplete) {
            thread.appearances.firstOrNull()?.let { origin ->
                line.append(" (first spoken ${shortDateFormatter.format(origin.date.atZone(ZoneId.systemDefault()))})")
            }
        }
        paceCorrelation(thread, currentRecordings)?.let { line.append(it) }
        return line.toString()
    }

    /**
     * Absence is a history claim ("this recurred without you") — as
     * risky as an origin claim, so it waits on the same backfill gate
     * (checked by the caller). Qualification: present in ≥
     * [MINIMUM_ABSENCE_WALKS] of the last [ABSENCE_WINDOW] (30-day)
     * walks while absent from the current one, capped at
     * [MAX_ABSENCE_LINES], selected by the tuple-swap (walks desc, lemma
     * asc) comparator (EDG-77).
     */
    private fun quietLines(threads: List<ActiveThread>, currentWalkId: Long): String? {
        val allAppearances = threads.flatMap { it.appearances }
        val anchor = allAppearances.filter { it.walkId == currentWalkId }.maxOfOrNull { it.date } ?: return null
        val windowStart = anchor.minus(ABSENCE_WINDOW)

        val absent = threads
            .filter { thread -> thread.appearances.none { it.walkId == currentWalkId } }
            .mapNotNull { thread ->
                val walksInWindow = thread.appearances
                    .filter { it.date >= windowStart && it.date <= anchor }
                    .map { it.walkId }
                    .toSet()
                    .size
                if (walksInWindow >= MINIMUM_ABSENCE_WALKS) thread to walksInWindow else null
            }
            .sortedWith(compareByDescending<Pair<ActiveThread, Int>> { it.second }.thenBy { it.first.lemma })
            .take(MAX_ABSENCE_LINES)

        if (absent.isEmpty()) return null
        return absent.joinToString(separator = "") { (thread, walks) ->
            "\nNotably quiet this walk: '${thread.displayTerm}' — present in $walks of the walker's recent walks."
        }
    }

    /**
     * Mechanical, not editorial: a relative gap between the theme
     * group's mean pace and the rest of the walk's, with NO numbers in
     * the phrasing (trajectory/correlation language stays dossier-only —
     * spec principle 1). Guards first: both sides non-empty, then a
     * strictly-positive rest mean before dividing.
     */
    private fun paceCorrelation(thread: ActiveThread, recordings: List<Pair<TranscriptContext, Double?>>): String? {
        val inTheme = recordings
            .filter { (context, _) -> context.themes.any { it.lemma == thread.lemma } }
            .mapNotNull { it.second }
        val rest = recordings
            .filter { (context, _) -> context.themes.none { it.lemma == thread.lemma } }
            .mapNotNull { it.second }
        if (inTheme.isEmpty() || rest.isEmpty()) return null

        val themeMean = inTheme.sum() / inTheme.size
        val restMean = rest.sum() / rest.size
        if (restMean <= 0.0) return null
        val change = (themeMean - restMean) / restMean

        return when {
            change <= -PACE_DIFFERENCE_THRESHOLD -> ", spoken more slowly than the rest of this walk"
            change >= PACE_DIFFERENCE_THRESHOLD -> ", spoken more quickly than the rest of this walk"
            else -> null
        }
    }
}
