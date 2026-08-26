// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        VaderSentiment.install(FIXTURE_LEXICON)
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
    fun `negation within 3 tokens flips good from positive to negative`() {
        // "not" here is NEGATION_WORDS, not a dampener — this pins the same
        // negation branch as "I do not love this" above, for a second
        // lexicon word (good, 1.9) so both positive fixture values are
        // covered under negation.
        assertEquals(-0.3412376512543242, VaderSentiment.score("This is not good")!!, TOLERANCE)
    }

    @Test
    fun `a dampener word within 3 tokens softens a positive score toward zero`() {
        // "somewhat" is BOOSTER_WORDS' dampener half (-BOOST). valenceFor's
        // positive-valence branch applies the increment directly:
        // 1.9 + (-0.293) = 1.607, normalize(1.607) = 0.38324473176419577.
        val dampened = VaderSentiment.score("This is somewhat good")!!
        val plain = VaderSentiment.score("This is good")!!
        assertEquals(0.38324473176419577, dampened, TOLERANCE)
        assertTrue("dampener should soften magnitude toward zero", dampened < plain)
    }

    @Test
    fun `a dampener word within 3 tokens softens a negative score toward zero`() {
        // Same dampener, but on a negative base valence: valenceFor's
        // `valence < 0` branch negates the increment before applying it,
        // so the dampener's -0.293 becomes +0.293 here:
        // -2.5 + 0.293 = -2.207, normalize(-2.207) = -0.4951013626154884.
        val dampened = VaderSentiment.score("This is somewhat bad")!!
        val plain = VaderSentiment.score("This is bad")!!
        assertEquals(-0.4951013626154884, dampened, TOLERANCE)
        assertTrue("dampener should soften magnitude toward zero", dampened > plain)
    }

    @Test
    fun `score is null for text with no lexicon coverage`() {
        assertNull(VaderSentiment.score("the quick brown fox"))
    }

    @Test
    fun `score is null for empty text`() {
        assertNull(VaderSentiment.score(""))
    }

    @Test
    fun `score before install throws IllegalStateException naming VaderSentiment install`() {
        resetLexicon()
        try {
            val exception = assertThrows(IllegalStateException::class.java) {
                VaderSentiment.score("I love this")
            }
            assertTrue(
                "exception message should name VaderSentiment.install: ${exception.message}",
                exception.message.orEmpty().contains("VaderSentiment.install"),
            )
        } finally {
            VaderSentiment.install(FIXTURE_LEXICON)
        }
    }

    /**
     * [VaderSentiment] is a bare singleton `object`, not something a fresh
     * classloader isolates per test — `setUp()`'s own `install(...)` call
     * runs before this method's body too, so the only way to exercise the
     * pre-install path deterministically is to reach in and null the
     * backing field back out, then restore it (`finally`, above) so later
     * tests in this class — and any other class sharing this JVM test
     * worker — see the object installed again.
     */
    private fun resetLexicon() {
        val field = VaderSentiment::class.java.getDeclaredField("lexicon")
        field.isAccessible = true
        field.set(VaderSentiment, null)
    }

    private companion object {
        const val TOLERANCE = 1e-9

        val FIXTURE_LEXICON = mapOf(
            "love" to 3.2,
            "hate" to -2.7,
            "good" to 1.9,
            "bad" to -2.5,
        )
    }
}
