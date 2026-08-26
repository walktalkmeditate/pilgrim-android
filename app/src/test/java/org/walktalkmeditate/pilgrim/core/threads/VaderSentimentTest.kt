// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exact-score fixtures for the documented VADER-lite subset (see
 * [VaderSentiment]'s KDoc for exactly what's implemented and omitted).
 * Installs a small hand-built lexicon fixture (values pinned from the real
 * `vader-lexicon.txt.gz` asset, but not loaded from it) so this test is a
 * pure, fast JUnit test that exercises the scoring algorithm in isolation
 * from asset I/O — [NlpAssetPinTest] separately guards the shipped asset's
 * integrity.
 */
class VaderSentimentTest {

    @Before
    fun setUp() {
        VaderSentiment.install(
            mapOf(
                "love" to 3.2,
                "hate" to -2.7,
                "good" to 1.9,
                "bad" to -2.5,
            ),
        )
    }

    @Test
    fun `score is positive for a simple positive sentence`() {
        assertEquals(0.6369499429264264, VaderSentiment.score("I love this")!!, TOLERANCE)
    }

    @Test
    fun `score is negative for a simple negative sentence`() {
        assertEquals(-0.5718850320700721, VaderSentiment.score("I hate this")!!, TOLERANCE)
    }

    @Test
    fun `negation within 3 tokens flips a positive word negative`() {
        assertEquals(-0.5216387489026343, VaderSentiment.score("I do not love this")!!, TOLERANCE)
    }

    @Test
    fun `a booster word within 3 tokens amplifies the score`() {
        val boosted = VaderSentiment.score("I really love this")!!
        val plain = VaderSentiment.score("I love this")!!
        assertEquals(0.6697392619941973, boosted, TOLERANCE)
        assertTrue("booster should increase magnitude", boosted > plain)
    }

    @Test
    fun `a dampener word within 3 tokens softens a negative score toward zero`() {
        assertEquals(-0.3412376512543242, VaderSentiment.score("This is not good")!!, TOLERANCE)
    }

    @Test
    fun `score is null for text with no lexicon coverage`() {
        assertNull(VaderSentiment.score("the quick brown fox"))
    }

    @Test
    fun `score is null for empty text`() {
        assertNull(VaderSentiment.score(""))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
