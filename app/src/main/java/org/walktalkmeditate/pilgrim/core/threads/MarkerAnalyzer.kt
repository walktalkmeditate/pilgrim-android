// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlinx.serialization.Serializable

/**
 * How a transcript's future- vs past-tense word density leans — see
 * [MarkerAnalyzer.compute] for the exact floor-and-dominance rule. The
 * source this was ported from (`Pilgrim/Models/Threads/MarkerAnalyzer.swift`)
 * has no "present" marker word list and calls this third state "balanced";
 * [PRESENT] is this port's name for that same state, not a new signal.
 */
@Serializable
enum class TemporalLean { PAST, PRESENT, FUTURE }

/**
 * One recording's linguistic-marker profile — descriptive signals only,
 * never a diagnosis. [temporalLean] and [sentiment] are the two
 * interpretive, English-tuned fields: both are null whenever
 * [MarkerAnalyzer.compute]'s `languageCode` isn't English. Every other
 * field is a plain lexicon-membership count, computed the same way
 * regardless of language or transcript length.
 *
 * [modalCounts] and [sentiment] carry default values so a future field
 * addition (or a pre-existing stored file missing a field this version
 * added) decodes leniently instead of throwing (BEH-11/EDG-32) — the
 * stale-orphan sweep must be able to decode every schema version it
 * cleans up, not just the current one.
 */
@Serializable
data class TranscriptMarkers(
    val wordCount: Int,
    val absolutistCount: Int,
    val firstPersonCount: Int,
    val insightCount: Int,
    val causationCount: Int,
    val discrepancyCount: Int,
    val temporalLean: TemporalLean?,
    val modalCounts: Map<String, Int> = emptyMap(),
    val sentiment: Double? = null,
)

/**
 * Ported from `Pilgrim/Models/Threads/MarkerAnalyzer.swift` at the frozen
 * iOS pin (`0172e2b`), with one Android-original divergence: iOS returns a
 * nil marker pack outright when the detected language isn't English; this
 * port always returns a fully populated [TranscriptMarkers] and instead
 * gates only the two interpretive fields on English (see that data
 * class's KDoc). [compute] has no transcript-length floor — markers are
 * computed for every transcribed recording regardless of length, unlike
 * [ThemeExtractor]'s [ThemeExtractor.MINIMUM_WORDS] gate.
 */
object MarkerAnalyzer {

    fun compute(text: String, languageCode: String?): TranscriptMarkers {
        val words = TranscriptNlp.wordTokens(text)
        val futureCount = words.count { it in MarkerLexicons.futureMarkers }
        val pastCount = words.count { it in MarkerLexicons.pastMarkers }
        val isEnglish = languageCode == ENGLISH

        return TranscriptMarkers(
            wordCount = words.size,
            absolutistCount = words.count { it in MarkerLexicons.absolutist },
            firstPersonCount = words.count { it in MarkerLexicons.firstPersonSingular },
            insightCount = words.count { it in MarkerLexicons.insight },
            causationCount = words.count { it in MarkerLexicons.causation },
            discrepancyCount = words.count { it in MarkerLexicons.discrepancy },
            temporalLean = if (isEnglish) temporalLean(futureCount, pastCount) else null,
            modalCounts = modalCounts(words),
            sentiment = if (isEnglish) VaderSentiment.score(text) else null,
        )
    }

    private fun modalCounts(words: List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (word in words) {
            if (word in MarkerLexicons.modalWords) {
                counts[word] = counts.getOrDefault(word, 0) + 1
            }
        }
        return counts
    }

    /** Needs both a floor of [TEMPORAL_LEAN_FLOOR] occurrences AND
     * [TEMPORAL_LEAN_DOMINANCE]x dominance over the other tense — a
     * ratio-only rule would flip a low-count transcript (e.g. 2 future, 0
     * past) to a lean neither iOS nor this port intends to claim. */
    private fun temporalLean(futureCount: Int, pastCount: Int): TemporalLean = when {
        futureCount >= TEMPORAL_LEAN_FLOOR && futureCount >= pastCount * TEMPORAL_LEAN_DOMINANCE -> TemporalLean.FUTURE
        pastCount >= TEMPORAL_LEAN_FLOOR && pastCount >= futureCount * TEMPORAL_LEAN_DOMINANCE -> TemporalLean.PAST
        else -> TemporalLean.PRESENT
    }

    private const val ENGLISH = "en"
    private const val TEMPORAL_LEAN_FLOOR = 3
    private const val TEMPORAL_LEAN_DOMINANCE = 2
}
