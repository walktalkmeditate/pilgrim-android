// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import kotlin.math.abs
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.core.threads.LemmaMention
import org.walktalkmeditate.pilgrim.core.threads.SpokenStoplist
import org.walktalkmeditate.pilgrim.core.threads.TranscriptNlp

/**
 * Deterministic pattern detection over a walk's context — v2, lemma-based
 * (verbatim port of iOS `AttentionDirectives@0172e2b`; [firstVersusLast]
 * folds in the iOS PR #72 rewrite shipped at `e7051bc`, 2026-08-29; parity
 * spec `docs/parity/2026-08-25-threads-engine-port.md` BEH-71..73/EDG-82..86).
 * The assembler hands the downstream model a dossier; these directives
 * tell it what is remarkable about *this* walk — the difference between
 * handing someone documents and handing them documents plus "compare
 * page 3 to page 9".
 */
object AttentionDirectives {

    private const val MOVING_THRESHOLD = 0.3
    private const val MAX_DIRECTIVES = 4
    private const val RECURRING_WORD_FLOOR = 3
    private const val PACE_SHIFT_THRESHOLD = 0.15

    /**
     * `wordsPerMinute` over a handful of words is noise, not a speaking
     * rate: a five-word note clears a 15% relative delta on the rounding
     * of its own start and end timestamps. At any plausible speaking rate
     * this floor means at least ten seconds of continuous speech on each
     * side — the pace branch's answer to the subject branch's lemma floor.
     */
    private const val MINIMUM_WORDS_TO_JUDGE_PACE = 25
    private const val SUBJECT_OVERLAP_CEILING = 0.20

    /**
     * Content lemmas, after [SpokenStoplist.scaffoldLemmas] is removed. A
     * floor of 5 would sit far below where a lexical-overlap judgment
     * carries any information: a sign-off ("heading back down the hill,
     * tired but glad") clears 5 comfortably and shares nothing with a
     * long opening, so the overlap coefficient reads zero and the
     * directive fires on a walk that never changed subject. A genuinely
     * divergent long pair measures around 0.06, so there is ample
     * headroom above this floor.
     */
    private const val MINIMUM_LEMMAS_TO_JUDGE_SUBJECT = 12

    /**
     * Two recordings are only comparable as subjects when both carry
     * comparable amounts of it. Past this ratio the smaller recording is
     * a thin sample of the walk rather than its second half, and its
     * overlap against a much longer transcript says more about its
     * length than about what the walker was talking about.
     */
    private const val SUBJECT_LENGTH_RATIO_CEILING = 3.0

    /**
     * Android has no synchronous on-device language detector (ML Kit's
     * identifier is async) to mirror iOS's own inline
     * `TranscriptNLP.detectLanguage(spokenText) ?? "en"` fallback — so
     * when [detectedLanguageCode] isn't supplied, [intentionEcho]'s
     * `related()` tier assumes English rather than attempting a detection
     * pass this function structurally cannot perform. Callers that need
     * `related()` evaluated in the walker's ACTUAL detected language must
     * supply [detectedLanguageCode] themselves (e.g. via
     * [PromptGenerator.resolvedDerivations], which already ran that
     * detection once for the whole prompt-list build).
     */
    private const val DEFAULT_LANGUAGE_CODE = "en"

    /**
     * `detectedLanguageCode` defaults to null ("assume English — see this
     * object's KDoc") so direct callers stay unchanged;
     * `PromptGenerator.resolvedDerivations` passes its precomputed code so
     * the echo detector's `related()` tier reflects the walker's real
     * detected language when one is available.
     */
    fun detect(context: ActivityContext, detectedLanguageCode: String? = null): List<String> {
        // Lemmatizing the full transcript is the expensive step; do it
        // once here and share it between the two detectors that need it.
        val spokenMentions = if (context.hasSpeech) {
            TranscriptNlp.contentLemmaMentions(context.recordings.joinToString(separator = " ") { it.text })
        } else {
            emptyList()
        }
        return listOfNotNull(
            stillness(context),
            paceShift(context),
            intentionEcho(context, spokenMentions, detectedLanguageCode),
            recurringWord(context, spokenMentions),
            firstVersusLast(context, detectedLanguageCode),
        ).take(MAX_DIRECTIVES)
    }

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
     * spoken words — by exact surface first (searched across ALL spoken
     * mentions, so "worrying ... worry" still earns "again"), by shared
     * lemma second, by [TranscriptNlp.related] nearness third. Intention
     * words are tried in TEXT order, all three tiers for EACH word before
     * moving to the next — the two shapes return DIFFERENT echoed words
     * whenever an earlier word matches only at a later tier. "Again" is
     * only honest when the walker repeated the exact surface; an
     * inflection ("worrying" for "worry") or a related word quotes what
     * was actually said instead.
     */
    private fun intentionEcho(
        context: ActivityContext,
        spoken: List<LemmaMention>,
        detectedLanguageCode: String?,
    ): String? {
        val intention = context.intention ?: return null
        if (!context.hasSpeech || spoken.isEmpty()) return null
        val language = detectedLanguageCode ?: DEFAULT_LANGUAGE_CODE

        for (word in TranscriptNlp.contentLemmaMentions(intention)) {
            if (spoken.any { it.lemma == word.lemma && it.surface == word.surface }) {
                return "The walker's intention spoke of '${word.surface}', and '${word.surface}' surfaces again " +
                    "in their spoken words — trace how it traveled."
            }
            val lemmaMatch = spoken.firstOrNull { it.lemma == word.lemma }
            if (lemmaMatch != null) {
                return "The walker's intention spoke of '${word.surface}', and '${lemmaMatch.surface}' surfaces " +
                    "in their spoken words — trace how it traveled."
            }
            val relatedMatch = spoken.firstOrNull { TranscriptNlp.related(word.lemma, it.lemma, language) }
            if (relatedMatch != null) {
                return "The walker's intention spoke of '${word.surface}', and '${relatedMatch.surface}' surfaces " +
                    "in their spoken words — trace how it traveled."
            }
        }
        return null
    }

    /**
     * The most-repeated content LEMMA across all recordings, excluding
     * any lemma the intention already claimed and any spoken-scaffolding
     * lemma ([SpokenStoplist.scaffoldLemmas] — light verbs like "think"
     * that dominate raw-frequency counts without carrying meaning) — the
     * next-ranked candidate is promoted, so excluding a lemma never
     * silences the directive, only redirects it. Shown as its most
     * frequent surface form so the walker's own inflection is echoed
     * back; the tuple-swap tie-break (max count, alphabetically-smallest
     * key) is applied TWICE — once choosing the winning lemma, again
     * choosing that lemma's display surface.
     */
    private fun recurringWord(context: ActivityContext, mentions: List<LemmaMention>): String? {
        if (!context.hasSpeech) return null
        val intentionLemmas = context.intention
            ?.let { TranscriptNlp.contentLemmaMentions(it).map { mention -> mention.lemma }.toSet() }
            ?: emptySet()

        val counts = HashMap<String, Int>()
        val surfaces = HashMap<String, MutableMap<String, Int>>()
        for (mention in mentions) {
            if (mention.lemma in intentionLemmas || mention.lemma in SpokenStoplist.scaffoldLemmas) continue
            counts[mention.lemma] = (counts[mention.lemma] ?: 0) + 1
            val surfaceCounts = surfaces.getOrPut(mention.lemma) { mutableMapOf() }
            surfaceCounts[mention.surface] = (surfaceCounts[mention.surface] ?: 0) + 1
        }

        val (lemma, count) = counts.entries
            .filter { it.value >= RECURRING_WORD_FLOOR }
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.toPair()
            ?: return null
        val display = surfaces[lemma]
            ?.entries
            ?.minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
            ?: lemma

        return "The word '$display' returns $count times across the recordings — it may be doing quiet work."
    }

    /**
     * Fires only when something measurably moved between the first
     * recording and the last. The previous version fired on every walk
     * with two recordings and presupposed its own conclusion — told to
     * measure what changed, the model finds change, including on walks
     * where nothing did (the `questionDensity` failure mode, quieter).
     *
     * Marker and sentiment deltas are deliberately NOT used: they are
     * unreachable from [ActivityContext], and computing them here would
     * mean a fresh analyzer pass per recording. Pace is free
     * ([RecordingContext.wordsPerMinute] is already populated); subject
     * costs exactly two lemma passes, never N.
     *
     * Both branches fail closed. Each carries a floor sized so the signal
     * it reads is measurable at all — words for a speaking rate, content
     * lemmas for a subject — and the subject branch additionally refuses
     * non-English walks. Silence hands the [MAX_DIRECTIVES] budget to a
     * detector with something to say.
     */
    private fun firstVersusLast(context: ActivityContext, detectedLanguageCode: String?): String? {
        val first = context.recordings.firstOrNull() ?: return null
        val last = context.recordings.lastOrNull() ?: return null
        if (context.recordings.size < 2) return null

        return speakingRateShift(first, last) ?: subjectShift(first, last, detectedLanguageCode)
    }

    /** Speaking rate, not ground speed — [paceShift] above reads GPS. */
    private fun speakingRateShift(first: RecordingContext, last: RecordingContext): String? {
        val firstPace = first.wordsPerMinute ?: return null
        val lastPace = last.wordsPerMinute ?: return null
        if (firstPace <= 0.0) return null
        if (TranscriptNlp.wordCount(first.text) < MINIMUM_WORDS_TO_JUDGE_PACE) return null
        if (TranscriptNlp.wordCount(last.text) < MINIMUM_WORDS_TO_JUDGE_PACE) return null

        val change = (lastPace - firstPace) / firstPace
        return when {
            change >= PACE_SHIFT_THRESHOLD ->
                "The walker spoke faster by the last recording than the first — attend to what moved between them."
            change <= -PACE_SHIFT_THRESHOLD ->
                "The walker spoke more slowly by the last recording than the first — attend to what moved between them."
            else -> null
        }
    }

    /**
     * Whether the walker's vocabulary moved between the two recordings.
     *
     * Language gate — accepted divergence from iOS: there, each
     * recording's own text is language-identified and both must resolve
     * to a language the OS ships a lemma model for. This substrate's
     * lemma path is English-only (R5) and language identification is
     * walk-level, so a mid-walk language switch is not separately
     * detectable here; the walk's resolved code gates instead, using
     * [intentionEcho]'s exact null convention (assume English — see this
     * object's KDoc). The stakes match iOS's: without a lemma model an
     * inflected language yields a distinct "lemma" per inflection and
     * depresses overlap systematically — every Camino walk in Spanish,
     * French, German, Italian, or Portuguese would read as total
     * divergence. The pace branch is deliberately not gated.
     */
    private fun subjectShift(
        first: RecordingContext,
        last: RecordingContext,
        detectedLanguageCode: String?,
    ): String? {
        val resolvedLanguage = detectedLanguageCode ?: DEFAULT_LANGUAGE_CODE
        if (resolvedLanguage != DEFAULT_LANGUAGE_CODE) return null

        val firstLemmas = subjectLemmas(first.text)
        val lastLemmas = subjectLemmas(last.text)
        val smallerCount = minOf(firstLemmas.size, lastLemmas.size)
        val largerCount = maxOf(firstLemmas.size, lastLemmas.size)
        if (smallerCount < MINIMUM_LEMMAS_TO_JUDGE_SUBJECT) return null
        if (largerCount.toDouble() / smallerCount > SUBJECT_LENGTH_RATIO_CEILING) return null

        // Overlap coefficient (intersection / smaller set), not Jaccard
        // (intersection / union). Jaccard collapses to |smaller| / |larger|
        // whenever one lemma set is a subset of the other — a long opening
        // reflection (~30+ unique lemmas) followed by a short closing note
        // on the SAME subject (all repeats) can then cross the ceiling on
        // length alone, firing "shares little vocabulary" on a walk that
        // never left its subject. The overlap coefficient is 1.0 for that
        // same subset case (correctly silent) and still near 0 for
        // genuinely divergent subjects, because it measures how much of
        // the SMALLER recording is accounted for by the larger one rather
        // than penalizing a short recording for being short.
        val overlap = firstLemmas.intersect(lastLemmas).size.toDouble() / smallerCount
        if (overlap > SUBJECT_OVERLAP_CEILING) return null

        return "The walker's last recording shares little vocabulary with the first — attend to what moved between them."
    }

    /**
     * The same [SpokenStoplist.scaffoldLemmas] filter [recurringWord]
     * applies: dictionary POS admits "think", "know", "want", "keep" as
     * content, but a speaker reaches for them out of habit. Left in, a
     * closing recording made entirely of scaffolding clears the lemma
     * floor on words that carry no subject at all.
     */
    private fun subjectLemmas(text: String): Set<String> =
        TranscriptNlp.contentLemmaMentions(text).map { it.lemma }.toSet() - SpokenStoplist.scaffoldLemmas
}
