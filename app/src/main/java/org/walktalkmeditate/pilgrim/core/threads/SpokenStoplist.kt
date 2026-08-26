// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * Two separate stoplists for two separate consumers, ported verbatim from
 * `Pilgrim/Models/Threads/TranscriptNLP.swift` at the frozen iOS pin
 * (`0172e2b`). Collapsing them into one list over- or under-suppresses
 * depending on the caller: [lightNouns] feeds noun-only theme extraction;
 * [scaffoldLemmas] feeds the verb-inclusive recurring-word attention
 * directive (a later unit) and — Android-original compensation, since this
 * substrate's dictionary POS has no contextual disambiguation — the
 * noun-only theme path as well, in [ThemeExtractor].
 *
 * Both lists exist because spoken-English scaffolding a tagger can admit
 * as a content word is reached for out of habit, not meaning: a
 * field-confirmed bug once let "was", "have", "can", "think", and "will"
 * surface as real-device themes. Omitting either list here reintroduces
 * that exact bug.
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
}
