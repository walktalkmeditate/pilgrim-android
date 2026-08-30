// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The single-tokenizer contract (counts, offsets, repeated-token cursor
 * advancement) plus [TranscriptNlp.contentLemmaMentions] and
 * [TranscriptNlp.related]'s own composition logic. Real WordNet data
 * (installed in [setUp]) backs the lexicon-dependent tests; the
 * brief-pinned relatedness/lemma fixtures tied to specific asset content
 * live in [NlpAssetPinTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranscriptNlpTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        TranscriptNlp.install(WordNetLexicon(context, json))
    }

    @Test
    fun `wordTokens lowercases and splits on non-letter runs, dropping empties`() {
        assertEquals(
            listOf("hello", "world"),
            TranscriptNlp.wordTokens("  Hello, WORLD!! "),
        )
    }

    @Test
    fun `wordTokens drops digits and punctuation-only fragments entirely`() {
        assertEquals(listOf("i", "walked", "km"), TranscriptNlp.wordTokens("I walked 5.2 km"))
    }

    @Test
    fun `wordCount matches wordTokens size`() {
        val text = "The quiet morning walk continues"
        assertEquals(TranscriptNlp.wordTokens(text).size, TranscriptNlp.wordCount(text))
    }

    @Test
    fun `wordTokenOffsets locates each token by forward search from the previous match`() {
        val text = "the cat sat, the cat ran"
        val offsets = TranscriptNlp.wordTokenOffsets(text)
        assertEquals(
            listOf(
                WordToken("the", 0),
                WordToken("cat", 4),
                WordToken("sat", 8),
                WordToken("the", 13),
                WordToken("cat", 17),
                WordToken("ran", 21),
            ),
            offsets,
        )
    }

    @Test
    fun `wordTokenOffsets advances past a repeated token instead of re-finding the first occurrence`() {
        val offsets = TranscriptNlp.wordTokenOffsets("cat cat cat")
        assertEquals(listOf(0, 4, 8), offsets.map { it.start })
    }

    @Test
    fun `contentLemmaMentions with default classes matches noun, verb, and adjective content words`() {
        val mentions = TranscriptNlp.contentLemmaMentions("I was grieving about my thoughts for many days")

        assertEquals(
            listOf(
                "was" to "be",
                "grieving" to "grieve",
                "about" to "about",
                "thoughts" to "thought",
                "many" to "many",
                "days" to "days",
            ),
            mentions.map { it.surface to it.lemma },
        )
    }

    @Test
    fun `contentLemmaMentions excludes surface forms of 2 characters or fewer`() {
        val mentions = TranscriptNlp.contentLemmaMentions("I was grieving about my thoughts for many days")
        assertTrue(mentions.none { it.surface == "i" || it.surface == "my" })
    }

    @Test
    fun `contentLemmaMentions offsets and lengths match the source surface positions`() {
        val mentions = TranscriptNlp.contentLemmaMentions("I was grieving about my thoughts for many days")
        val grieving = mentions.single { it.surface == "grieving" }
        assertEquals(6, grieving.start)
        assertEquals("grieving".length, grieving.length)
    }

    @Test
    fun `contentLemmaMentions with classes restricted to NOUN only returns noun-class mentions`() {
        val mentions = TranscriptNlp.contentLemmaMentions(
            "I was grieving about my thoughts for many days",
            classes = setOf(PosClass.NOUN),
        )
        assertEquals(listOf("thoughts" to "thought", "days" to "days"), mentions.map { it.surface to it.lemma })
    }

    @Test
    fun `contentLemmaMentions with classes restricted to VERB only returns verb-class mentions`() {
        val mentions = TranscriptNlp.contentLemmaMentions(
            "I was grieving about my thoughts for many days",
            classes = setOf(PosClass.VERB),
        )
        assertEquals(listOf("was" to "be", "grieving" to "grieve"), mentions.map { it.surface to it.lemma })
    }

    // --- Structural immunity to the iOS letterCore bug class (2026-08-28 field report) ---
    // iOS's NLTagger `.word` tokens can swallow a sentence-final period when
    // a lowercase word follows — the shape Whisper writes — so "yeah." and
    // "garden.the" reached lemma identity with punctuation inside them
    // (field-confirmed: a Recurring chip printing "yeah."). This tokenizer
    // splits on non-letter RUNS before any lemma lookup, so that bug class
    // is structurally impossible here; these tests PIN the property.

    @Test
    fun `punctuation-glued 'yeah' yields the bare token — pins structural immunity to the iOS letterCore bug class`() {
        assertEquals(listOf("yeah"), TranscriptNlp.wordTokens("yeah."))
        assertEquals(
            listOf("yeah", "yeah", "i", "think", "so", "yeah"),
            TranscriptNlp.wordTokens("Yeah. Yeah. I think so, yeah."),
        )
    }

    @Test
    fun `no-space glue 'garden,the' splits and extracts garden — pins structural immunity to the iOS letterCore bug class`() {
        assertEquals(listOf("garden", "the"), TranscriptNlp.wordTokens("garden.the"))

        val mentions = TranscriptNlp.contentLemmaMentions("I keep circling the garden.the garden holds it")

        assertEquals(2, mentions.count { it.lemma == "garden" })
        assertTrue(
            "no lemma identity may carry punctuation: $mentions",
            mentions.none { mention ->
                mention.lemma.any { !it.isLetter() } || mention.surface.any { !it.isLetter() }
            },
        )
    }

    @Test
    fun `related is true for identical strings regardless of language`() {
        assertTrue(TranscriptNlp.related("marche", "marche", "fr"))
    }

    @Test
    fun `related is false for different strings in a non-English language`() {
        assertFalse(TranscriptNlp.related("chagrin", "tristesse", "fr"))
    }

    @Test
    fun `related is true for two lemmas sharing a WordNet synset in English`() {
        assertTrue(TranscriptNlp.related("grief", "sorrow", "en"))
    }

    @Test
    fun `related is false for two lemmas with no shared synset in English`() {
        assertFalse(TranscriptNlp.related("grief", "bicycle", "en"))
    }
}
