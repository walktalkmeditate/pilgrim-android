// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Deterministic pattern detection over a walk's context (verbatim port
 * of iOS `AttentionDirectives@9a418e4`). The assembler hands the
 * downstream model a dossier; these directives tell it what is
 * remarkable about *this* walk — the difference between handing someone
 * documents and handing them documents plus "compare page 3 to page 9".
 */
object AttentionDirectives {

    private const val MOVING_THRESHOLD = 0.3
    private const val MAX_DIRECTIVES = 4

    fun detect(context: ActivityContext): List<String> =
        listOfNotNull(
            stillness(context),
            paceShift(context),
            intentionEcho(context),
            recurringWord(context),
            firstVersusLast(context),
        ).take(MAX_DIRECTIVES)

    /**
     * A sustained still stretch that neither a logged meditation nor a
     * recorded pause accounts for — otherwise the directive would
     * re-brand the walk's own Pauses line as mystery. Sample spacing is
     * unknown here, so minutes are estimated from the run's share of all
     * samples — imprecise, honest enough to point at. Negative speeds
     * are invalid GPS fixes, not stillness.
     */
    private fun stillness(context: ActivityContext): String? {
        val speeds = context.routeSpeeds
        if (speeds.size < 30 || context.durationSeconds <= 0L) return null

        var longestRun = 0
        var currentRun = 0
        for (speed in speeds) {
            currentRun = if (speed >= 0.0 && speed < MOVING_THRESHOLD) currentRun + 1 else 0
            longestRun = maxOf(longestRun, currentRun)
        }

        val estimatedMinutes =
            context.durationSeconds.toDouble() * (longestRun.toDouble() / speeds.size) / 60.0
        val explainedMinutes = (
            context.meditations.sumOf { it.durationSeconds } +
                context.pauses.sumOf { it.durationSeconds }
            ) / 60.0
        if (estimatedMinutes < 3.0 || estimatedMinutes <= explainedMinutes) return null

        return "The route shows about ${estimatedMinutes.roundToInt()} minutes of stillness " +
            "in one place — ask what held the walker there."
    }

    /** Average moving speed of the final third against the first third. */
    private fun paceShift(context: ActivityContext): String? {
        val moving = context.routeSpeeds.filter { it >= MOVING_THRESHOLD }
        if (moving.size < 30) return null

        val third = moving.size / 3
        val first = moving.take(third).sum() / third
        val last = moving.takeLast(third).sum() / third
        if (first <= 0.0) return null

        val change = (last - first) / first
        if (abs(change) < 0.2) return null

        val percent = (abs(change) * 100.0).roundToInt()
        return if (change < 0) {
            "The walker's pace slowed by $percent% in the final third — something slowed them; notice what."
        } else {
            "The walker's pace quickened by $percent% in the final third — something carried them; notice what."
        }
    }

    /**
     * A word from the stated intention resurfacing in the walker's own
     * spoken words.
     */
    private fun intentionEcho(context: ActivityContext): String? {
        val intention = context.intention ?: return null
        if (!context.hasSpeech) return null
        val spoken = contentWords(context.recordings.joinToString(separator = " ") { it.text })
        val echoed = contentWords(intention).firstOrNull { spoken.contains(it) } ?: return null
        return "The walker's intention spoke of '$echoed', and '$echoed' surfaces again " +
            "in their spoken words — trace how it traveled."
    }

    /**
     * The most-repeated content word across all recordings, excluding
     * any word the intention-echo directive already claimed. Ties break
     * to the lexicographically smallest word (spec P5's decoding of the
     * Swift tuple comparator).
     */
    private fun recurringWord(context: ActivityContext): String? {
        if (!context.hasSpeech) return null
        val intentionWords = context.intention?.let { contentWords(it).toSet() } ?: emptySet()

        val counts = mutableMapOf<String, Int>()
        for (word in contentWords(context.recordings.joinToString(separator = " ") { it.text })) {
            if (word in intentionWords) continue
            counts[word] = (counts[word] ?: 0) + 1
        }

        val (word, count) = counts.entries
            .filter { it.value >= 3 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull() ?: return null

        return "The word '$word' returns $count times across the recordings — it may be doing quiet work."
    }

    private fun firstVersusLast(context: ActivityContext): String? {
        if (context.recordings.size < 2) return null
        return "Compare the first recording with the last — measure what changed in the walker between them."
    }

    private val stopwords: Set<String> = setOf(
        "the", "and", "that", "this", "with", "from", "have", "what", "your",
        "them", "they", "been", "were", "will", "would", "could", "should",
        "about", "into", "just", "like", "know", "then", "there", "when",
        "where", "which", "while", "because", "again", "back", "keep",
        "still", "very", "really", "today", "cannot", "something",
    )

    private val nonLetters = Regex("\\P{L}+")

    private fun contentWords(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(nonLetters)
            .filter { it.length > 3 && it !in stopwords }
}
