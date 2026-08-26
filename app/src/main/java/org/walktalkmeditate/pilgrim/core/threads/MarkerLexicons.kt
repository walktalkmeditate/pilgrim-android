// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

/**
 * The six modal-verb families, in the order [MarkerLexicons.modalFamilies]
 * declares them — that order is load-bearing (see the property's KDoc), so
 * this enum's declaration order must never be reshuffled or alphabetized.
 */
enum class ModalFamily { POSSIBILITY, OBLIGATION, COUNTERFACTUAL, TENTATIVE, INTENTION, DESIRE }

/**
 * Verbatim linguistic-marker word lists, ported from
 * `Pilgrim/Models/Threads/MarkerLexicons.swift` at the frozen iOS pin
 * (`0172e2b`). Every set below must stay character-for-character identical
 * to the pinned Swift source — this is a licensing constraint as much as a
 * parity one for [absolutist]: it is the Al-Mosaiwi & Johnstone (2018)
 * Table 1 open-access dictionary, and LIWC's proprietary word lists must
 * never be copied into this file under any name.
 */
object MarkerLexicons {

    val absolutist: Set<String> = setOf(
        "absolutely", "all", "always", "complete", "completely", "constant",
        "constantly", "definitely", "entire", "ever", "every", "everyone",
        "everything", "full", "must", "never", "nothing", "totally", "whole",
    )

    val firstPersonSingular: Set<String> = setOf("i", "me", "my", "mine", "myself")

    val insight: Set<String> = setOf(
        "realize", "realized", "realizing", "understand", "understood",
        "understanding", "notice", "noticed", "noticing", "aware", "awareness",
        "clarity", "insight", "learn", "learned", "learning", "recognize",
        "recognized", "sense", "sensed",
    )

    val causation: Set<String> = setOf(
        "because", "cause", "caused", "causes", "effect", "hence", "since",
        "therefore", "thus", "reason", "reasons", "why", "consequently", "led",
    )

    val discrepancy: Set<String> = setOf(
        "should", "would", "could", "ought", "need", "needed", "want",
        "wanted", "wish", "wished", "hope", "hoped", "rather", "instead",
    )

    val futureMarkers: Set<String> = setOf(
        "will", "shall", "gonna", "tomorrow", "soon", "later", "ahead",
        "upcoming", "future", "plan", "plans", "planning",
    )

    /** No present-tense list exists anywhere in this file, matching the
     * pinned iOS source exactly — a port that invents one to look
     * "symmetric" with [futureMarkers] adds a signal iOS never computes. */
    val pastMarkers: Set<String> = setOf(
        "was", "were", "did", "had", "ago", "yesterday", "remember",
        "remembered", "used", "back", "once", "before",
    )

    /**
     * Ordered arrays, not sets, so a dominant-word tie always resolves to
     * the same word downstream — [ModalFamily]'s declaration order is
     * equally load-bearing for the same reason. should/must/ought/would/
     * want/need/wish deliberately double-count into [discrepancy] too
     * ("can"/"could" do not); [discrepancy]'s own inflected forms
     * (needed/wanted/wished) are exempt here because this map is
     * single-token/uninflected only — none of that overlap is a bug to
     * dedupe.
     */
    val modalFamilies: Map<ModalFamily, List<String>> = linkedMapOf(
        ModalFamily.POSSIBILITY to listOf("can", "could"),
        ModalFamily.OBLIGATION to listOf("should", "must", "ought"),
        ModalFamily.COUNTERFACTUAL to listOf("would"),
        ModalFamily.TENTATIVE to listOf("might", "may"),
        ModalFamily.INTENTION to listOf("will"),
        ModalFamily.DESIRE to listOf("want", "need", "wish"),
    )

    val modalWords: Set<String> = modalFamilies.values.flatten().toSet()

    /**
     * Resolves [word] to its family, relying on the invariant that every
     * word in [modalFamilies] belongs to exactly one family — this is a
     * linear scan over [modalFamilies]'s own declared order, not a lookup
     * keyed by word, so a future word added to two families would make
     * the result order-dependent instead of failing loudly.
     */
    fun modalFamily(word: String): ModalFamily? =
        modalFamilies.entries.firstOrNull { (_, words) -> word in words }?.key
}
