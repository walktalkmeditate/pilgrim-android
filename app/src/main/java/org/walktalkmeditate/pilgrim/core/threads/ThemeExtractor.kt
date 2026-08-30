// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlinx.serialization.Serializable

/**
 * One recurring topic surfaced from a transcript's noun-class content
 * words. [salience] = [mentionCount] / the transcript's total word count
 * (via [TranscriptNlp.wordCount]), computed once at extraction time —
 * normalizing by transcript length so a short and a long transcript
 * sharing the same raw mention count don't read as equally salient.
 *
 * [salience] is FROZEN into [TranscriptContext]'s stored JSON at
 * extraction time (DAT-4) — a future stoplist/tokenizer change must not
 * retroactively recompute it from stored fields, or historical dossiers
 * would silently drift.
 */
@Serializable
data class Theme(
    val lemma: String,
    val displayTerm: String,
    val mentionCount: Int,
    val salience: Double,
    val mentions: List<LemmaMention>,
)

/**
 * Noun-only theme extraction, ported from
 * `Pilgrim/Models/Threads/ThemeExtractor.swift` at the frozen iOS pin
 * (`0172e2b`). Thread identity is exact-lemma in v1, deliberately: an
 * earlier iOS iteration's per-transcript synonym merging split cross-walk
 * identity (a lemma folding into a neighbor in one walk read as a false
 * "first time" in the next) and was reverted — do not reintroduce
 * clustering here.
 *
 * Android-original compensation: candidates are also filtered through
 * [SpokenStoplist.scaffoldLemmas], on top of [walkingDomain],
 * [SpokenStoplist.lightNouns], and [SpokenStoplist.filler] (iOS's three
 * theme-side filters). iOS never needs the scaffold filter here because
 * its contextual tagger never admits a scaffold verb ("think", "have",
 * "will", ...) as a noun in the first place; this substrate's dictionary
 * POS does, since WordNet lists real noun senses for those exact surface
 * forms.
 *
 * A second, narrower Android-original filter,
 * [SpokenStoplist.androidGerundExtension], suppresses a handful of gerunds
 * ("going", "getting", "saying", "coming", "telling") that WordNet also
 * lists as themselves rather than folding to a verb lemma — the same
 * substrate gap, applied to a different word class; iOS's contextual
 * tagger would resolve these as verbs in ordinary spoken narration and so
 * never needed this list either. The wider ambiguous class ("thinking",
 * "feeling", "being", "looking", "seeing", "talking", "asking") is
 * deliberately left unsuppressed pending a real-transcript field read
 * (U12); "living" and "working" are deliberately admitted as themes
 * outright — both read as plausible real topics, not scaffolding (cf.
 * iOS's own canonical "the move" suggestion).
 *
 * A third Android-original filter,
 * [SpokenStoplist.androidHomographNounSuppression], suppresses "felt" and
 * "whole" — surfaced as spurious themes by the U11 golden-fixture capture,
 * since WordNet noun-lists both (felt the fabric, whole the entirety)
 * exactly where iOS's contextual tagger tags the same surface forms verb
 * or adjective in ordinary reflective narration ("I felt..."). "open"
 * surfaced in that same capture but is deliberately left unsuppressed,
 * joining the U12 real-transcript field-read watchlist above instead of
 * the suppression list — see [SpokenStoplist.androidHomographNounSuppression]'s
 * own KDoc for why.
 *
 * All five general-purpose lists are collected into [sharedStoplists];
 * [walkingDomain] stays separate, being this extractor's alone.
 */
object ThemeExtractor {

    const val MINIMUM_WORDS = 25
    const val MAX_THEMES = 6
    const val MINIMUM_MENTIONS = 2

    /** The walk's own narration vocabulary — suppressed so every walk's
     * dominant thread isn't just the walk itself. */
    val walkingDomain: Set<String> = setOf(
        "walk", "walking", "path", "trail", "hill", "uphill", "downhill",
        "road", "street", "step", "steps", "route", "mile", "kilometer",
        "minute", "left", "right",
    )

    /**
     * The general-purpose stoplists this extractor discards, named so the
     * relationship to [SpokenStoplist.nonContentLemmas] can be asserted
     * rather than trusted: [ThemeExtractorTest] pins that everything here
     * also reaches that union, so a sixth stoplist added to this filter
     * cannot silently stop short of the recurring-word directive, the
     * subject-shift lemma sets, and intention lineage the way
     * [SpokenStoplist.filler] and the two Android-original lists did.
     *
     * [walkingDomain] is deliberately absent: it is this feature's own
     * vocabulary ("path", "trail"), meaningless to a prompt-time consumer,
     * and belongs to the extractor alone.
     */
    val sharedStoplists: Set<String> = SpokenStoplist.lightNouns +
        SpokenStoplist.filler +
        SpokenStoplist.scaffoldLemmas +
        SpokenStoplist.androidGerundExtension +
        SpokenStoplist.androidHomographNounSuppression

    /**
     * A transcript under [MINIMUM_WORDS] total words (by
     * [TranscriptNlp.wordCount], the single tokenizer every word-count and
     * density in this feature shares) yields zero themes, silently — never
     * a degenerate single-word theme. This floor applies to theme
     * formation only; [MarkerAnalyzer.compute] has no such floor.
     */
    fun themes(text: String): List<Theme> {
        val wordCount = TranscriptNlp.wordCount(text)
        if (wordCount < MINIMUM_WORDS) return emptyList()

        // The noun class is necessary but not sufficient: on iOS NLTagger
        // also calls 'yeah' a noun, and here WordNet noun-lists 'okay' —
        // [SpokenStoplist.filler] carries what lexical class cannot.
        //
        // Deliberately not [SpokenStoplist.nonContentLemmas], the union the
        // live prompt-time consumers share. Themes are persisted and pinned
        // to [TranscriptContext.ANALYSIS_VERSION], so what this filter
        // discards is schema: reading the union would ship a version bump's
        // worth of change the first time the two sets diverge, without one.
        val candidates = TranscriptNlp.contentLemmaMentions(text, classes = setOf(PosClass.NOUN))
            .filterNot { it.lemma in walkingDomain || it.lemma in sharedStoplists }

        val eligible = candidates.groupBy { it.lemma }.filterValues { it.size >= MINIMUM_MENTIONS }
        val themes = eligible.map { (lemma, mentions) -> toTheme(lemma, mentions, wordCount) }

        return themes
            .sortedWith(compareByDescending<Theme> { it.salience }.thenBy { it.lemma })
            .take(MAX_THEMES)
    }

    /**
     * Display term = the cohort's most frequent surface form, ties broken
     * by the lexicographically SMALLEST surface string — mirrors iOS's
     * `.min { ($0.value, $1.key) > ($1.value, $0.key) }` tuple-swap
     * exactly: descending by count, ascending by key. [mentions] is
     * guaranteed non-empty here (the [MINIMUM_MENTIONS] filter above
     * already ran), matching the safety condition iOS's force-unwrap
     * relies on — an empty group reaching this function is a caller bug,
     * not a case to degrade gracefully from.
     */
    private fun toTheme(lemma: String, mentions: List<LemmaMention>, wordCount: Int): Theme {
        val surfaceCounts = mentions.groupingBy { it.surface }.eachCount()
        val displayTerm = surfaceCounts.entries
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
            ?: error("toTheme called with an empty mention list for lemma \"$lemma\"")
        return Theme(
            lemma = lemma,
            displayTerm = displayTerm,
            mentionCount = mentions.size,
            salience = mentions.size.toDouble() / wordCount,
            mentions = mentions,
        )
    }
}
