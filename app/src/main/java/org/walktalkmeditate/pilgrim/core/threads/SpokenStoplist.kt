// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * Three stoplists ported verbatim from `Pilgrim/Models/Threads/TranscriptNLP.swift`
 * at the frozen iOS pin (`0172e2b`; [filler] and the `time`/`person`/`app`
 * light nouns fold in the 2026-08-28 iOS field-report delta), plus two
 * Android-original additions. Collapsing the ported lists into one over-
 * or under-suppresses depending on the caller: [lightNouns] and [filler]
 * feed noun-only theme extraction; [scaffoldLemmas] feeds the
 * verb-inclusive recurring-word attention directive (a later unit) and —
 * Android-original compensation, since this substrate's dictionary POS has
 * no contextual disambiguation — the noun-only theme path as well, in
 * [ThemeExtractor].
 *
 * The ported lists exist because spoken-English scaffolding a tagger can
 * admit as a content word is reached for out of habit, not meaning: a
 * field-confirmed bug once let "was", "have", "can", "think", and "will"
 * surface as real-device themes. Omitting a list here reintroduces
 * that exact bug class.
 *
 * [androidGerundExtension] and [androidHomographNounSuppression] are the
 * two Android-original lists with no iOS counterpart at all — see each
 * one's own KDoc for why the theme path needs it and which neighboring
 * words deliberately do not join it.
 *
 * [nonContentLemmas] unions all five for the prompt-time consumers that
 * read every lexical class. New stoplists belong inside one of the five,
 * never beside them.
 */
object SpokenStoplist {

    /**
     * Filed on the noun/theme side only; joined by `day`/`days`/`area` at
     * the iOS schema v3->v4 ship gate (2026-08-25).
     *
     * `time`/`times`, `person`/`people`, `app`/`apps` joined at the iOS
     * schema v4->v5 gate (2026-08-28), all three observed as live themes on
     * real-device history. `app` is the walker narrating Pilgrim itself —
     * meta-noise, never a life theme. On iOS the plurals are documentation
     * (NLTagger folds `people` -> `person` and `times` -> `time` before the
     * filter runs); on THIS substrate both forms do real work — Morphy's
     * own-form-first lookup keeps `people` and `times` as their own lemmas
     * (each is WordNet noun-listed in its own right), so the plurals here
     * are load-bearing, not documentation.
     */
    val lightNouns: Set<String> = setOf(
        "thing", "things", "stuff", "kind", "sort", "lot", "bit", "way", "ways",
        "one", "ones", "something", "anything", "everything", "nothing",
        "day", "days", "area",
        "time", "times", "person", "people", "app", "apps",
    )

    /**
     * Conversational filler filtered out of THEME extraction, joined at the
     * iOS schema v4->v5 gate (2026-08-28) after a field report found 'yeah'
     * threading three real walks. On iOS the noun-only restriction does not
     * stop these (NLTagger classes 'yeah' as a NOUN in Whisper's lowercase
     * sentence runs); on this substrate dictionary POS admits only `okay`
     * (WordNet noun-lists it in its own right) — the rest are not in
     * WordNet's index at all today, listed anyway so the set stays
     * iOS-verbatim and keeps protecting if the lexicon asset ever widens.
     *
     * `ok` sits beside `okay` for the same iOS-verbatim reason (NLTagger
     * lemmatizes the surface "okay" to "OK" in some positions); here Morphy
     * keeps "okay" as itself and the two-letter surface "ok" never clears
     * [TranscriptNlp]'s three-character floor. That same floor is why the
     * two-letter spellings (`um`, `uh`, `er`, `mm`) are absent on both
     * platforms — the doubled spellings Whisper actually writes are what
     * needs listing.
     *
     * Deliberately NOT here, because every word added blinds the feature to
     * that word forever: `right` (a direction on a walk — and already
     * covered by [ThemeExtractor.walkingDomain]), `sure`, `yes`, `no`,
     * `well`, `like`, `just`, `anyway`. Each can carry real weight in a
     * walker's speech; none has been seen misfiring.
     */
    val filler: Set<String> = setOf(
        "yeah", "yep", "yup", "nah", "okay", "ok",
        "uhh", "umm", "erm", "hmm", "mhm", "mmm", "huh",
        "gonna", "gotta", "wanna",
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
     *
     * "over", "out" and "here" joined on 2026-08-30 from the U12 device
     * field read, where each threaded as a theme on the first corpus that
     * exercised ordinary reflective phrasing: "turning it over and over",
     * "needed to get out", "it was here before and it will be here after".
     * WordNet noun-lists all three — the cricket "over", the baseball
     * "out", "here" as a place — so dictionary POS admits them where a
     * contextual tagger reads adverb or particle. They carry no subject at
     * any frequency, and the phrasings that produce them are too common in
     * spoken reflection to leave to chance.
     */
    val androidHomographNounSuppression: Set<String> =
        setOf("felt", "whole", "over", "out", "here")

    /**
     * The one definition of "not a content word" for every consumer of
     * [TranscriptNlp.contentLemmaMentions] that reads all three lexical
     * classes — the recurring-word directive, the subject-shift lemma
     * sets, and intention lineage.
     *
     * It exists because the five lists above were each wired into whichever
     * consumer motivated them, and the halves then disagreed about what a
     * content word is: everything except [scaffoldLemmas] reached theme
     * extraction alone, so 'okay' could still win the recurring-word
     * directive, a closing note padded with 'people', 'day' and 'going'
     * could still clear the subject branch's lemma floor, and a lineage
     * claim could still rest on 'day'. Adding a word to any of the five
     * now reaches every one of those consumers at once. Add new stoplists
     * to one of the five above rather than beside them, or the same drift
     * returns.
     *
     * Five sets where iOS unions three ([scaffoldLemmas], [lightNouns],
     * [filler]). [androidGerundExtension] and
     * [androidHomographNounSuppression] exist for exactly the reason
     * [filler] does — this substrate's dictionary POS admits them as
     * content where iOS's contextual tagger resolves them to a verb or an
     * adjective — so a word the theme layer already discards as substrate
     * noise must not be able to win the recurring-word directive or pad
     * the subject floor either. The divergence from iOS's three-set union
     * follows from the lemma engine, not from a different idea of what a
     * content word is.
     *
     * [ThemeExtractor] deliberately does NOT read this union — see
     * [ThemeExtractor.sharedStoplists]. On this substrate the two hold the
     * same words today, since the theme filter needs all five; they stay
     * two declarations because that filter additionally suppresses its own
     * [ThemeExtractor.walkingDomain], reads the noun class only, and feeds
     * a persisted derived cache pinned to
     * [TranscriptContext.ANALYSIS_VERSION], where changing what is
     * discarded is a schema change requiring a version bump and a
     * re-analysis sweep on every device. The consumers here compute live
     * per prompt and cache nothing.
     */
    val nonContentLemmas: Set<String> =
        scaffoldLemmas + lightNouns + filler + androidGerundExtension + androidHomographNounSuppression
}
