// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verbatim fidelity for all five spoken stoplists — see [SpokenStoplist]'s
 * KDoc for why the lists must stay separate and why omitting any one
 * reintroduces a field-confirmed bug — plus the composition of the
 * [SpokenStoplist.nonContentLemmas] union they feed.
 */
class SpokenStoplistTest {

    @Test
    fun `lightNouns is the exact 24-word verbatim set, including the time, person, app additions`() {
        assertEquals(
            setOf(
                "thing", "things", "stuff", "kind", "sort", "lot", "bit", "way", "ways",
                "one", "ones", "something", "anything", "everything", "nothing",
                "day", "days", "area",
                "time", "times", "person", "people", "app", "apps",
            ),
            SpokenStoplist.lightNouns,
        )
        assertEquals(24, SpokenStoplist.lightNouns.size)
    }

    @Test
    fun `filler is the exact 16-word verbatim set`() {
        assertEquals(
            setOf(
                "yeah", "yep", "yup", "nah", "okay", "ok",
                "uhh", "umm", "erm", "hmm", "mhm", "mmm", "huh",
                "gonna", "gotta", "wanna",
            ),
            SpokenStoplist.filler,
        )
        assertEquals(16, SpokenStoplist.filler.size)
    }

    @Test
    fun `filler deliberately excludes the words that can carry real weight`() {
        // right/sure/yes/no/well/like/just/anyway — every word added blinds
        // the feature to that word forever; see SpokenStoplist.filler's KDoc.
        val deliberatelyAbsent = setOf("right", "sure", "yes", "no", "well", "like", "just", "anyway")
        assertTrue(SpokenStoplist.filler.intersect(deliberatelyAbsent).isEmpty())
    }

    @Test
    fun `scaffoldLemmas is the exact 35-word verbatim set`() {
        assertEquals(
            setOf(
                "be", "have", "do", "get", "go", "come", "make", "take", "know",
                "think", "say", "see", "want", "mean", "feel", "need", "let", "put",
                "keep", "kind", "thing", "stuff", "way", "lot", "bit",
                "can", "could", "should", "would", "must", "might", "may", "will", "ought", "wish",
            ),
            SpokenStoplist.scaffoldLemmas,
        )
        assertEquals(35, SpokenStoplist.scaffoldLemmas.size)
    }

    @Test
    fun `scaffoldLemmas includes every single-token modal word`() {
        val modalWords = setOf(
            "can", "could", "should", "must", "ought", "would", "might", "may", "will", "want", "need", "wish",
        )
        assertTrue(SpokenStoplist.scaffoldLemmas.containsAll(modalWords))
    }

    @Test
    fun `androidGerundExtension is the exact 5-word verbatim set`() {
        assertEquals(
            setOf("going", "getting", "saying", "coming", "telling"),
            SpokenStoplist.androidGerundExtension,
        )
        assertEquals(5, SpokenStoplist.androidGerundExtension.size)
    }

    @Test
    fun `androidHomographNounSuppression is the exact 5-word verbatim set`() {
        assertEquals(
            setOf("felt", "whole", "over", "out", "here"),
            SpokenStoplist.androidHomographNounSuppression,
        )
        assertEquals(5, SpokenStoplist.androidHomographNounSuppression.size)
    }

    @Test
    fun `nonContentLemmas unions all five lists and nothing else`() {
        val constituents = listOf(
            "lightNouns" to SpokenStoplist.lightNouns,
            "filler" to SpokenStoplist.filler,
            "scaffoldLemmas" to SpokenStoplist.scaffoldLemmas,
            "androidGerundExtension" to SpokenStoplist.androidGerundExtension,
            "androidHomographNounSuppression" to SpokenStoplist.androidHomographNounSuppression,
        )
        for ((name, set) in constituents) {
            assertTrue(
                "$name must reach every prompt-time consumer through nonContentLemmas",
                SpokenStoplist.nonContentLemmas.containsAll(set),
            )
        }
        // Nothing is declared beside the five: a word that appears only in
        // the union is a word no single list owns, and the "add to one of
        // the five" instruction has already been ignored.
        assertEquals(
            emptySet<String>(),
            SpokenStoplist.nonContentLemmas - constituents.flatMap { it.second }.toSet(),
        )
    }

    @Test
    fun `lightNouns and scaffoldLemmas are distinct lists sharing some entries`() {
        // Deliberately overlapping (kind, thing, stuff, way, lot, bit) — see SpokenStoplist's
        // KDoc: they are two different consumers' lists, not one list under two names.
        assertTrue(SpokenStoplist.lightNouns != SpokenStoplist.scaffoldLemmas)
        assertTrue(SpokenStoplist.lightNouns.contains("day"))
        assertTrue(!SpokenStoplist.scaffoldLemmas.contains("day"))
    }
}
