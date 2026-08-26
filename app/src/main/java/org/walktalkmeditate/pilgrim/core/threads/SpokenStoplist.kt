// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * Two stoplists ported verbatim from `Pilgrim/Models/Threads/TranscriptNLP.swift`
 * at the frozen iOS pin (`0172e2b`), plus one Android-original addition.
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
 * [androidGerundExtension] is the one Android-original list with no iOS
 * counterpart at all — see its own KDoc for why the theme path needs it
 * and why its neighboring gerunds deliberately do not join it.
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
}
