// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MarkerLexicons] fixture fidelity (verbatim word-list content, ordered
 * modal families) plus [MarkerAnalyzer.compute]'s behavior: lexicon counts
 * on crafted sentences, the temporal-lean floor-and-dominance rule, the
 * English-only gate on [TranscriptMarkers.temporalLean]/[sentiment], and
 * length-independence (no floor, unlike [ThemeExtractor]).
 */
class MarkerAnalyzerTest {

    @Before
    fun setUp() {
        VaderSentiment.install(FIXTURE_LEXICON)
    }

    // --- MarkerLexicons: verbatim word-list fixtures ---

    @Test
    fun `absolutist is the exact 19-word Al-Mosaiwi and Johnstone Table 1 set`() {
        assertEquals(
            setOf(
                "absolutely", "all", "always", "complete", "completely", "constant",
                "constantly", "definitely", "entire", "ever", "every", "everyone",
                "everything", "full", "must", "never", "nothing", "totally", "whole",
            ),
            MarkerLexicons.absolutist,
        )
        assertEquals(19, MarkerLexicons.absolutist.size)
    }

    @Test
    fun `firstPersonSingular is the exact 5-word set`() {
        assertEquals(setOf("i", "me", "my", "mine", "myself"), MarkerLexicons.firstPersonSingular)
    }

    @Test
    fun `insight is the exact 20-word set`() {
        assertEquals(
            setOf(
                "realize", "realized", "realizing", "understand", "understood",
                "understanding", "notice", "noticed", "noticing", "aware", "awareness",
                "clarity", "insight", "learn", "learned", "learning", "recognize",
                "recognized", "sense", "sensed",
            ),
            MarkerLexicons.insight,
        )
        assertEquals(20, MarkerLexicons.insight.size)
    }

    @Test
    fun `causation is the exact 14-word set`() {
        assertEquals(
            setOf(
                "because", "cause", "caused", "causes", "effect", "hence", "since",
                "therefore", "thus", "reason", "reasons", "why", "consequently", "led",
            ),
            MarkerLexicons.causation,
        )
        assertEquals(14, MarkerLexicons.causation.size)
    }

    @Test
    fun `discrepancy is the exact 14-word set`() {
        assertEquals(
            setOf(
                "should", "would", "could", "ought", "need", "needed", "want",
                "wanted", "wish", "wished", "hope", "hoped", "rather", "instead",
            ),
            MarkerLexicons.discrepancy,
        )
        assertEquals(14, MarkerLexicons.discrepancy.size)
    }

    @Test
    fun `futureMarkers is the exact 12-word set`() {
        assertEquals(
            setOf(
                "will", "shall", "gonna", "tomorrow", "soon", "later", "ahead",
                "upcoming", "future", "plan", "plans", "planning",
            ),
            MarkerLexicons.futureMarkers,
        )
        assertEquals(12, MarkerLexicons.futureMarkers.size)
    }

    @Test
    fun `pastMarkers is the exact 12-word set`() {
        assertEquals(
            setOf(
                "was", "were", "did", "had", "ago", "yesterday", "remember",
                "remembered", "used", "back", "once", "before",
            ),
            MarkerLexicons.pastMarkers,
        )
        assertEquals(12, MarkerLexicons.pastMarkers.size)
    }

    @Test
    fun `modalFamilies declares exactly six families in the pinned order`() {
        assertEquals(
            listOf(
                ModalFamily.POSSIBILITY, ModalFamily.OBLIGATION, ModalFamily.COUNTERFACTUAL,
                ModalFamily.TENTATIVE, ModalFamily.INTENTION, ModalFamily.DESIRE,
            ),
            MarkerLexicons.modalFamilies.keys.toList(),
        )
    }

    @Test
    fun `each modal family holds its exact ordered word list`() {
        assertEquals(listOf("can", "could"), MarkerLexicons.modalFamilies.getValue(ModalFamily.POSSIBILITY))
        assertEquals(listOf("should", "must", "ought"), MarkerLexicons.modalFamilies.getValue(ModalFamily.OBLIGATION))
        assertEquals(listOf("would"), MarkerLexicons.modalFamilies.getValue(ModalFamily.COUNTERFACTUAL))
        assertEquals(listOf("might", "may"), MarkerLexicons.modalFamilies.getValue(ModalFamily.TENTATIVE))
        assertEquals(listOf("will"), MarkerLexicons.modalFamilies.getValue(ModalFamily.INTENTION))
        assertEquals(listOf("want", "need", "wish"), MarkerLexicons.modalFamilies.getValue(ModalFamily.DESIRE))
    }

    @Test
    fun `modalWords is the flattened union of every family`() {
        assertEquals(
            setOf("can", "could", "should", "must", "ought", "would", "might", "may", "will", "want", "need", "wish"),
            MarkerLexicons.modalWords,
        )
    }

    @Test
    fun `modalFamily resolves every modal word to exactly one family, by per-word identity`() {
        assertEquals(ModalFamily.POSSIBILITY, MarkerLexicons.modalFamily("can"))
        assertEquals(ModalFamily.POSSIBILITY, MarkerLexicons.modalFamily("could"))
        assertEquals(ModalFamily.OBLIGATION, MarkerLexicons.modalFamily("should"))
        assertEquals(ModalFamily.OBLIGATION, MarkerLexicons.modalFamily("must"))
        assertEquals(ModalFamily.OBLIGATION, MarkerLexicons.modalFamily("ought"))
        assertEquals(ModalFamily.COUNTERFACTUAL, MarkerLexicons.modalFamily("would"))
        assertEquals(ModalFamily.TENTATIVE, MarkerLexicons.modalFamily("might"))
        assertEquals(ModalFamily.TENTATIVE, MarkerLexicons.modalFamily("may"))
        assertEquals(ModalFamily.INTENTION, MarkerLexicons.modalFamily("will"))
        assertEquals(ModalFamily.DESIRE, MarkerLexicons.modalFamily("want"))
        assertEquals(ModalFamily.DESIRE, MarkerLexicons.modalFamily("need"))
        assertEquals(ModalFamily.DESIRE, MarkerLexicons.modalFamily("wish"))
    }

    @Test
    fun `modalFamily returns null for a non-modal word`() {
        assertNull(MarkerLexicons.modalFamily("music"))
    }

    // --- MarkerAnalyzer.compute: lexicon counts on crafted sentences ---

    @Test
    fun `absolutistCount counts every absolutist word present, once each`() {
        val text = "I always try to be completely honest even when it feels totally impossible " +
            "because I must never forget everything and every single moment matters"
        assertEquals(7, MarkerAnalyzer.compute(text, "en").absolutistCount)
    }

    @Test
    fun `firstPersonCount counts every first-person-singular word present`() {
        val text = "I told myself that my thoughts were mine to keep, and no one could take them from me"
        assertEquals(5, MarkerAnalyzer.compute(text, "en").firstPersonCount)
    }

    @Test
    fun `insightCount counts every insight word present`() {
        val text = "Slowly I began to realize and understand what I was noticing, gaining clarity and " +
            "awareness as I learned to recognize this quiet sense of insight"
        assertEquals(9, MarkerAnalyzer.compute(text, "en").insightCount)
    }

    @Test
    fun `causationCount counts every causation word present`() {
        val text = "I stayed because of the reason, since therefore that is why it happened, and " +
            "consequently the effect caused everything, thus the cause led to this outcome"
        assertEquals(11, MarkerAnalyzer.compute(text, "en").causationCount)
    }

    @Test
    fun `discrepancyCount counts every discrepancy word present, once each`() {
        val text = "I should and would and could and ought and need and needed and want and wanted " +
            "and wish and wished and hope and hoped and rather and instead"
        assertEquals(14, MarkerAnalyzer.compute(text, "en").discrepancyCount)
    }

    @Test
    fun `modalCounts tallies each modal word by its own surface form`() {
        val text = "can can could should must must must would might may will will will want need need wish"
        val expected = mapOf(
            "can" to 2, "could" to 1, "should" to 1, "must" to 3, "would" to 1,
            "might" to 1, "may" to 1, "will" to 3, "want" to 1, "need" to 2, "wish" to 1,
        )
        assertEquals(expected, MarkerAnalyzer.compute(text, "en").modalCounts)
    }

    // --- temporalLean: floor (>= 3) AND 2x dominance, both required ---

    @Test
    fun `temporalLean is FUTURE exactly at the floor and dominance boundary`() {
        // future: will, shall, gonna, tomorrow = 4; past: was, were = 2. 4>=3 and 4>=2*2.
        val markers = MarkerAnalyzer.compute("will shall gonna tomorrow was were", "en")
        assertEquals(TemporalLean.FUTURE, markers.temporalLean)
    }

    @Test
    fun `temporalLean is PAST exactly at the floor and dominance boundary`() {
        // past: was, were, did, had = 4; future: will, shall = 2. 4>=3 and 4>=2*2.
        val markers = MarkerAnalyzer.compute("was were did had will shall", "en")
        assertEquals(TemporalLean.PAST, markers.temporalLean)
    }

    @Test
    fun `temporalLean is PRESENT when neither tense reaches the floor of 3`() {
        val markers = MarkerAnalyzer.compute("will shall", "en")
        assertEquals(TemporalLean.PRESENT, markers.temporalLean)
    }

    @Test
    fun `temporalLean is PRESENT at the floor but short of 2x dominance`() {
        // future: will, shall, gonna = 3; past: was, were = 2. 3>=3 but 3 is not >= 2*2.
        val markers = MarkerAnalyzer.compute("will shall gonna was were", "en")
        assertEquals(TemporalLean.PRESENT, markers.temporalLean)
    }

    // --- English-only gate on temporalLean/sentiment; everything else is language-independent ---

    @Test
    fun `sentiment is populated via VaderSentiment when languageCode is en`() {
        val markers = MarkerAnalyzer.compute("I love this walk", "en")
        assertEquals(VaderSentiment.score("I love this walk"), markers.sentiment)
        assertTrue("expected a non-null sentiment for covered text", markers.sentiment != null)
    }

    @Test
    fun `temporalLean and sentiment are null when languageCode is not en`() {
        val text = "will shall gonna tomorrow I love this"
        val markers = MarkerAnalyzer.compute(text, "fr")
        assertNull(markers.temporalLean)
        assertNull(markers.sentiment)
    }

    @Test
    fun `temporalLean and sentiment are null when languageCode is null`() {
        val markers = MarkerAnalyzer.compute("will shall gonna tomorrow", null)
        assertNull(markers.temporalLean)
        assertNull(markers.sentiment)
    }

    @Test
    fun `lexicon counts and modalCounts are identical regardless of languageCode`() {
        val text = "I always think I can and should remember because I will plan ahead"
        val english = MarkerAnalyzer.compute(text, "en")
        val french = MarkerAnalyzer.compute(text, "fr")

        assertEquals(english.wordCount, french.wordCount)
        assertEquals(english.absolutistCount, french.absolutistCount)
        assertEquals(english.firstPersonCount, french.firstPersonCount)
        assertEquals(english.insightCount, french.insightCount)
        assertEquals(english.causationCount, french.causationCount)
        assertEquals(english.discrepancyCount, french.discrepancyCount)
        assertEquals(english.modalCounts, french.modalCounts)
    }

    @Test
    fun `compute never touches VaderSentiment when languageCode is not en`() {
        resetVaderSentimentLexicon()
        try {
            val markers = MarkerAnalyzer.compute("will shall gonna I love this", "fr")
            assertNull(markers.sentiment)
        } finally {
            VaderSentiment.install(FIXTURE_LEXICON)
        }
    }

    // --- no length floor: markers compute for every recording regardless of length ---

    @Test
    fun `compute returns full markers for a transcript far under ThemeExtractor's word floor`() {
        val markers = MarkerAnalyzer.compute("I always love this", "en")
        assertEquals(4, markers.wordCount)
        assertTrue("wordCount is under ThemeExtractor.MINIMUM_WORDS", markers.wordCount < ThemeExtractor.MINIMUM_WORDS)
        assertEquals(1, markers.absolutistCount)
    }

    @Test
    fun `wordCount matches the single tokenizer's count exactly`() {
        val text = "The quiet morning walk continues onward"
        assertEquals(TranscriptNlp.wordCount(text), MarkerAnalyzer.compute(text, "en").wordCount)
    }

    // --- determinism ---

    @Test
    fun `compute is deterministic for identical input`() {
        val text = "I will always remember because I should have known, and I think I can"
        assertEquals(MarkerAnalyzer.compute(text, "en"), MarkerAnalyzer.compute(text, "en"))
    }

    private fun resetVaderSentimentLexicon() {
        val field = VaderSentiment::class.java.getDeclaredField("lexicon")
        field.isAccessible = true
        field.set(VaderSentiment, null)
    }

    private companion object {
        val FIXTURE_LEXICON = mapOf("love" to 3.2, "hate" to -2.7)
    }
}
