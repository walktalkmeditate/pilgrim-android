// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient

/**
 * Orchestrates analyze-and-store: themes from the FULL transcript,
 * markers from a hallucination-scrubbed text, gated on the toggle (R12)
 * and on English (R5).
 *
 * Every unit's work sits behind [ThreadsPreferencesRepository.threadsAfterWalks] —
 * this analyzer checks it itself rather than trusting every future
 * caller to check first. English-only v1: a non-English (or
 * undetectable) language writes NOTHING, matching the plan's R5 —
 * Android's own tightening beyond iOS, which still computes themes for
 * any language and only nulls the markers field (MarkerAnalyzer here is
 * never even called for non-English, since WordNet has no non-English
 * substrate to extract themes from in the first place).
 *
 * [detectLanguage] is exposed separately from [analyzeAndStore] so a
 * caller (the transcription runner) can log whether analysis proceeds
 * even when it's not English and nothing gets written — this costs one
 * extra ML Kit inference per recording versus threading a single
 * detection result through both, but ML Kit's on-device language-id
 * model is a fast, local, no-network call, so the duplication is a
 * deliberate simplicity trade, not an oversight.
 */
@Singleton
class TranscriptContextAnalyzer @Inject constructor(
    private val store: TranscriptContextStore,
    private val environment: ThreadsAnalysisEnvironment,
    private val languageIdClient: MlKitLanguageIdClient,
    private val preferences: ThreadsPreferencesRepository,
) {

    suspend fun detectLanguage(transcript: String): String? = languageIdClient.detect(transcript)

    /**
     * Returns the freshly computed, durably-saved context, or `null`
     * when: the toggle is off, the detected language isn't English, or
     * the store's write genuinely failed (as opposed to a tombstone
     * block, which [TranscriptContextStore.save] itself reports as a
     * successful no-op) — a deliberate simplification of iOS's
     * `(context, saved)` tuple return: nothing in this port currently
     * needs an in-memory context object that isn't actually on disk, so
     * a non-null return here always means "this is what's on disk now."
     */
    suspend fun analyzeAndStore(
        uuid: String,
        transcript: String,
        flaggedRanges: List<IntRange> = emptyList(),
    ): TranscriptContext? {
        if (!preferences.threadsAfterWalks.value) return null

        val languageCode = languageIdClient.detect(transcript)
        if (languageCode != ENGLISH) return null

        environment.ensureInstalled()

        val themes = ThemeExtractor.themes(transcript).filter { theme ->
            flaggedRanges.isEmpty() ||
                theme.mentions.any { mention -> flaggedRanges.none { range -> mention.start in range } }
        }
        val markerText = scrubFlaggedFragments(transcript, flaggedRanges)
        val markers = MarkerAnalyzer.compute(markerText, languageCode)

        val context = TranscriptContext(
            uuid = uuid,
            languageCode = languageCode,
            wordCount = TranscriptNlp.wordCount(transcript),
            themes = themes,
            markers = markers,
            transcriptHash = TranscriptContext.hashTranscript(transcript),
            analysisVersion = TranscriptContext.ANALYSIS_VERSION,
        )
        return context.takeIf { store.save(context) }
    }

    /**
     * BEH-59 carry: the shared manual-edit / retranscribe-clear write path
     * — `WalkSummaryViewModel` (manual edit save, single retranscribe's
     * null-clearing step) and `RecordingsListViewModel`'s equivalents call
     * this AFTER a successful transcription-column write, in place of
     * calling [analyzeAndStore] directly.
     *
     * Toggle ON with real, non-blank [transcription]: eagerly (re)analyzes
     * with EMPTY flagged ranges (BEH-86 — a hand-edited transcript is
     * trusted verbatim; segment-level ASR-quality flags don't exist for
     * text the user typed). Anything else — toggle OFF, or a null/blank
     * [transcription] (the retranscribe-clear step has nothing worth
     * analyzing yet) — removes the stored context WITHOUT a tombstone
     * (BEH-20), so a later backfill or the real re-transcription that
     * follows a retranscribe-clear can still freely re-analyze this
     * [uuid]; a tombstone here would silently block that.
     */
    suspend fun analyzeOrForget(uuid: String, transcription: String?) {
        if (preferences.threadsAfterWalks.value && !transcription.isNullOrBlank()) {
            analyzeAndStore(uuid, transcription, emptyList())
        } else {
            store.removeContext(uuid)
        }
    }

    /**
     * Marker text = [transcript] with each flagged range's own substring
     * replaced EVERYWHERE it occurs, one fragment at a time — not just at
     * its originating range. This deliberately scrubs any coincidentally
     * matching legitimate text elsewhere too (EDG-49): a range-based
     * excision instead would leave different, non-iOS-faithful marker
     * counts on any transcript with hallucination flags.
     */
    private fun scrubFlaggedFragments(transcript: String, flaggedRanges: List<IntRange>): String {
        if (flaggedRanges.isEmpty()) return transcript
        return flaggedRanges.fold(transcript) { acc, range ->
            val fragment = transcript.substringForRange(range)
            if (fragment.isEmpty()) acc else acc.replace(fragment, " ")
        }
    }

    private fun String.substringForRange(range: IntRange): String {
        val start = range.first.coerceIn(0, length)
        val endExclusive = (range.last + 1).coerceIn(start, length)
        return substring(start, endExclusive)
    }

    companion object {
        /**
         * Every occurrence of each flagged fragment's text within
         * [transcript], as UTF-16-index-unit ranges (this port's pinned
         * offset unit) — not just the first: repeated hallucination is
         * the canonical Whisper failure shape (EDG-47/BEH-23). Callers
         * (the transcription runner) compute this from the flagged
         * segments' own cleaned text before calling [analyzeAndStore].
         */
        fun flaggedRanges(transcript: String, fragments: List<String>): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            for (fragment in fragments) {
                if (fragment.isEmpty()) continue
                var searchStart = 0
                while (true) {
                    val index = transcript.indexOf(fragment, searchStart)
                    if (index < 0) break
                    ranges += index until (index + fragment.length)
                    searchStart = index + fragment.length
                }
            }
            return ranges
        }

        private const val ENGLISH = "en"
    }
}
