// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * Two stoplists ported verbatim from `Pilgrim/Models/Threads/TranscriptNLP.swift`
 * at the frozen iOS pin (`0172e2b`), plus two Android-original additions.
 * Collapsing the two ported lists into one over- or under-suppresses
 * depending on the caller: [lightNouns] feeds noun-only theme extraction;
 * [scaffoldLemmas] feeds the verb-inclusive recurring-word attention
 * directive (a later unit) and — Android-original compensation, since this
 * substrate's dictionary POS has no contextual disambiguation — the
 * noun-only theme path as well, in [ThemeExtractor].
 *
 * Both ported lists exist because spoken-English scaffolding a tagger can
 * admit as a content word is reached for out of habit, not meaning: a
 * field-confirmed bug once let "was", "have", "can", "think", and "will"
 * surface as real-device themes. Omitting either list here reintroduces
 * that exact bug.
 *
 * [androidGerundExtension] and [androidHomographNounSuppression] are the
 * two Android-original lists with no iOS counterpart at all — see each
 * one's own KDoc for why the theme path needs it and which neighboring
 * words deliberately do not join it.
 */
object SpokenStoplist {

    /** Filed on the noun/theme side only; joined by `day`/`days`/`area` at
     * the iOS schema v3->v4 ship gate (2026-08-25). */
    val lightNouns: Set<String> = setOf(
        "thing", "things", "stuff", "kind", "sort", "lot", "bit", "way", "ways",
        "one", "ones", "something", "anything", "everything", "nothing",
        "day", "days", "area",
    )

    /** Verb-inclusive; every single-token modal word from
     * [MarkerLexicons.modalFamilies] is deliberately included here too —
     * modals still keep per-word identity in the markers channel, they are
     * only stoplisted from theme/recurring-word naming. */
    val scaffoldLemmas: Set<String> = setOf(
        "be", "have", "do", "get", "go", "come", "make", "take", "know",
        "think", "say", "see", "want", "mean", "feel", "need", "let", "put",
        "keep", "kind", "thing", "stuff", "way", "lot", "bit",
        "can", "could", "should", "would", "must", "might", "may", "will", "ought", "wish",
    )

    /**
     * Android-only, theme path only: these five gerund surface forms are
     * noun-listed in WordNet in their own right (Morphy's own-form-first
     * lookup keeps them as themselves — see [ThemeExtractor]'s KDoc), where
     * iOS's contextual tagger would tag the same words verbs in ordinary
     * spoken narration and so never needed to suppress them. Suppressing
     * them here is the same class of Android-original compensation that
     * already made [scaffoldLemmas] mandatory on the theme path.
     *
     * The wider ambiguous class — "thinking", "feeling", "being",
     * "looking", "seeing", "talking", "asking" — is deliberately NOT
     * suppressed here, pending the U12 real-transcript field read.
     * "living" and "working" are deliberately admitted, not suppressed:
     * both read as plausible real themes in their own right (cf. iOS's own
     * canonical "the move" suggestion, itself built from a gerund-shaped
     * lemma).
     */
    val androidGerundExtension: Set<String> = setOf("going", "getting", "saying", "coming", "telling")

    /**
     * Android-only, theme path only: WordNet noun-lists both of these
     * surface forms in their own right — "felt" as the fabric, "whole" as
     * the entirety — so dictionary POS admits them as noun candidates
     * exactly where iOS's contextual tagger would tag the same words verb
     * ("felt", the ordinary past tense of "feel") or adjective ("whole",
     * modifying a noun) instead. "I felt..." is ubiquitous reflective
     * speech; twice in one recording is already enough to surface it as a
     * theme without this suppression.
     *
     * [scaffoldLemmas] already lists "feel" as a lemma, but that does not
     * help here: lemma-string filtering can't see through "felt" as an
     * independently-listed inflected surface form — the same substrate gap
     * [androidGerundExtension]'s "going"/"go" pair closes for gerunds, here
     * for an irregular past tense instead. "whole" loses nothing by
     * suppression: it is still counted by [MarkerLexicons.absolutist] in
     * the marker channel, just not named as a theme.
     *
     * Discovered via the U11 golden-fixture capture. "open" surfaced
     * alongside these two in that same capture but is deliberately NOT
     * suppressed here: it is a poetic-plausible noun in its own right for a
     * walking app, and — unlike "whole" — no other channel backstops it,
     * so it stays on the U12 real-transcript field-read watchlist instead
     * of being suppressed pre-emptively.
     */
    val androidHomographNounSuppression: Set<String> = setOf("felt", "whole")
}
