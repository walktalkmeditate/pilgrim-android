// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verbatim fidelity for both spoken stoplists — see [SpokenStoplist]'s KDoc
 * for why the two lists must stay separate and why omitting either
 * reintroduces a field-confirmed bug.
 */
class SpokenStoplistTest {

    @Test
    fun `lightNouns is the exact 18-word verbatim set, including the day, days, area additions`() {
        assertEquals(
            setOf(
                "thing", "things", "stuff", "kind", "sort", "lot", "bit", "way", "ways",
                "one", "ones", "something", "anything", "everything", "nothing",
                "day", "days", "area",
            ),
            SpokenStoplist.lightNouns,
        )
        assertEquals(18, SpokenStoplist.lightNouns.size)
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
    fun `lightNouns and scaffoldLemmas are distinct lists sharing some entries`() {
        // Deliberately overlapping (kind, thing, stuff, way, lot, bit) — see SpokenStoplist's
        // KDoc: they are two different consumers' lists, not one list under two names.
        assertTrue(SpokenStoplist.lightNouns != SpokenStoplist.scaffoldLemmas)
        assertTrue(SpokenStoplist.lightNouns.contains("day"))
        assertTrue(!SpokenStoplist.scaffoldLemmas.contains("day"))
    }
}
