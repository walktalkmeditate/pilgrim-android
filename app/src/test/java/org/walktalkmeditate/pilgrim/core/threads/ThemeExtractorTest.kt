// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ThemeExtractor.themes] against the real WordNet-derived lexicon (via
 * [TranscriptNlp.contentLemmaMentions], which needs a real
 * [WordNetLexicon] installed — see [TranscriptNlpTest] for the same
 * harness pattern). AE1 (the field-confirmed scaffold-theme regression)
 * gets its own dedicated, natural-sentence fixtures; every other test uses
 * mechanically repeated words so mention counts are exact and auditable at
 * a glance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeExtractorTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        TranscriptNlp.install(WordNetLexicon(context, json))
    }

    // --- AE1: the field-confirmed scaffold-theme regression ---

    @Test
    fun `AE1 - pure spoken scaffolding at or above the word floor yields zero themes`() {
        // 44 words; includes "was", "have"x2, "can", "think"x3, "will" — the five words
        // AE1 pins by name. All carry WordNet noun senses (verified against the committed
        // asset) and would otherwise become theme candidates without the scaffold filter.
        val text = "I was walking and I have to say I think I can do this again and I will think " +
            "about it because I have so many things I want and need but I think I should just " +
            "keep on and see what happens next"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)
        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    @Test
    fun `AE1 - music spoken three times amid the same scaffolding yields exactly the music theme`() {
        val text = "I was walking and I have to say I think about music because I can think about " +
            "music too and I will think about music again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)

        val themes = ThemeExtractor.themes(text)

        assertEquals(1, themes.size)
        assertEquals("music", themes.single().lemma)
        assertEquals("music", themes.single().displayTerm)
        assertEquals(3, themes.single().mentionCount)
        assertEquals(3.0 / TranscriptNlp.wordCount(text), themes.single().salience, 0.0)
    }

    @Test
    fun `AE1 - a sub-floor transcript yields zero themes even though music would otherwise qualify`() {
        // 16 words; "music" appears twice (would pass minimumMentions) but the transcript
        // is under MINIMUM_WORDS, so themes() short-circuits to empty regardless.
        val text = "I love music so much and I think about music every single day of my life"
        assertTrue(TranscriptNlp.wordCount(text) < ThemeExtractor.MINIMUM_WORDS)
        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    // --- androidGerundExtension: Android-only gerund suppression (U4 review, Fix 3) ---

    @Test
    fun `going spoken three times amid scaffolding yields zero themes`() {
        // "going" is WordNet-noun-listed in its own right (Morphy keeps it as itself) and is
        // NOT covered by scaffoldLemmas (which only lists "go") — this is non-vacuous only
        // because of SpokenStoplist.androidGerundExtension.
        val text = "I was walking and I have to say I think about going because I can think about " +
            "going too and I will think about going again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)
        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    @Test
    fun `living spoken three times amid the same scaffolding is deliberately admitted as a theme`() {
        val text = "I was walking and I have to say I think about living because I can think about " +
            "living too and I will think about living again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)

        val themes = ThemeExtractor.themes(text)

        assertEquals(1, themes.size)
        assertEquals("living", themes.single().lemma)
        assertEquals(3, themes.single().mentionCount)
        assertEquals(3.0 / TranscriptNlp.wordCount(text), themes.single().salience, 0.0)
    }

    // --- androidHomographNounSuppression: Android-only homograph-noun suppression (U11 golden-fixture finding) ---

    @Test
    fun `felt spoken three times amid scaffolding yields zero themes`() {
        // "felt" is WordNet-noun-listed in its own right (the fabric) and is NOT covered by
        // scaffoldLemmas (which only lists "feel") — non-vacuous only because of
        // SpokenStoplist.androidHomographNounSuppression.
        val text = "I was walking and I have to say I think about felt because I can think about " +
            "felt too and I will think about felt again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)
        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    @Test
    fun `whole spoken three times amid scaffolding yields zero themes`() {
        // "whole" is WordNet-noun-listed in its own right (the entirety) — suppressing it here
        // costs nothing downstream: MarkerLexicons.absolutist still counts it in the marker channel.
        val text = "I was walking and I have to say I think about whole because I can think about " +
            "whole too and I will think about whole again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)
        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    @Test
    fun `open spoken three times amid the same scaffolding is deliberately admitted as a theme`() {
        // Unlike "felt"/"whole", "open" is deliberately left unsuppressed — a poetic-plausible
        // noun for a walking app, on the U12 real-transcript field-read watchlist instead.
        val text = "I was walking and I have to say I think about open because I can think about " +
            "open too and I will think about open again since I have so many things I want and need"
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)

        val themes = ThemeExtractor.themes(text)

        assertEquals(1, themes.size)
        assertEquals("open", themes.single().lemma)
        assertEquals(3, themes.single().mentionCount)
        assertEquals(3.0 / TranscriptNlp.wordCount(text), themes.single().salience, 0.0)
    }

    // --- SpokenStoplist.filler + time/person/app light nouns (iOS 2026-08-28 field-report fold-in) ---

    @Test
    fun `yeah spoken three times among real content never threads`() {
        // On this substrate "yeah" is not WordNet-listed at all, so it can't
        // even become a candidate — SpokenStoplist.filler is belt on top of
        // that structural immunity (iOS needed the list to do the whole job:
        // NLTagger classes 'yeah' as a noun in Whisper's lowercase runs).
        val text = pad(repeatedWords("yeah", 3) + repeatedWords("music", 2))

        val themes = ThemeExtractor.themes(text)

        assertEquals(listOf("music"), themes.map { it.lemma })
    }

    @Test
    fun `time, person, and app repeated enough to qualify never thread`() {
        val text = pad(
            repeatedWords("time", 3) + repeatedWords("person", 3) +
                repeatedWords("app", 3) + repeatedWords("music", 2),
        )

        val themes = ThemeExtractor.themes(text)

        assertEquals(listOf("music"), themes.map { it.lemma })
    }

    @Test
    fun `plural surfaces times and people are load-bearing stoplist members, not documentation`() {
        // Morphy's own-form-first lookup keeps "times" and "people" as their
        // own lemmas (both are WordNet noun-listed in their own right) — they
        // never fold to "time"/"person" the way iOS's NLTagger folds them, so
        // listing only the singulars would leave both threading.
        val text = pad(repeatedWords("times", 2) + repeatedWords("people", 2) + repeatedWords("music", 2))

        val themes = ThemeExtractor.themes(text)

        assertEquals(listOf("music"), themes.map { it.lemma })
    }

    @Test
    fun `a pure-filler transcript yields zero themes`() {
        // "okay" is the one filler member WordNet noun-lists in its own
        // right — without SpokenStoplist.filler it threads here.
        val text = pad(repeatedWords("okay", 3) + repeatedWords("yeah", 3) + repeatedWords("gonna", 3))
        assertTrue(TranscriptNlp.wordCount(text) >= ThemeExtractor.MINIMUM_WORDS)

        assertEquals(emptyList<Theme>(), ThemeExtractor.themes(text))
    }

    @Test
    fun `a real noun spoken three times amid filler still threads`() {
        val text = pad(repeatedWords("garden", 3) + repeatedWords("yeah", 2) + repeatedWords("okay", 2))

        val themes = ThemeExtractor.themes(text)

        assertEquals(listOf("garden"), themes.map { it.lemma })
        assertEquals(3, themes.single().mentionCount)
    }

    // --- minimumWords: exact boundary ---

    @Test
    fun `wordCount of exactly one below the floor yields zero themes`() {
        val text = wordsOfLength(ThemeExtractor.MINIMUM_WORDS - 1, listOf("music", "music"))
        assertEquals(ThemeExtractor.MINIMUM_WORDS - 1, TranscriptNlp.wordCount(text))
        assertTrue(ThemeExtractor.themes(text).isEmpty())
    }

    @Test
    fun `wordCount of exactly the floor allows themes to form`() {
        val text = wordsOfLength(ThemeExtractor.MINIMUM_WORDS, listOf("music", "music"))
        assertEquals(ThemeExtractor.MINIMUM_WORDS, TranscriptNlp.wordCount(text))

        val themes = ThemeExtractor.themes(text)

        assertEquals(1, themes.size)
        assertEquals("music", themes.single().lemma)
    }

    // --- minimumMentions: exact boundary ---

    @Test
    fun `a lemma with exactly one mention never becomes a theme`() {
        assertTrue(ThemeExtractor.themes(pad(repeatedWords("harvest", 1))).isEmpty())
    }

    @Test
    fun `a lemma with exactly minimumMentions mentions becomes a theme`() {
        val text = pad(repeatedWords("harvest", ThemeExtractor.MINIMUM_MENTIONS))
        val themes = ThemeExtractor.themes(text)
        assertEquals(1, themes.size)
        assertEquals(ThemeExtractor.MINIMUM_MENTIONS, themes.single().mentionCount)
        assertEquals(
            ThemeExtractor.MINIMUM_MENTIONS.toDouble() / TranscriptNlp.wordCount(text),
            themes.single().salience,
            0.0,
        )
    }

    // --- salience: normalized by transcript wordCount, not a raw count ---

    @Test
    fun `salience normalizes by transcript wordCount so identical mention counts diverge across lengths`() {
        val shortText = wordsOfLength(ThemeExtractor.MINIMUM_WORDS, listOf("harvest", "harvest"))
        val longText = wordsOfLength(ThemeExtractor.MINIMUM_WORDS * 2, listOf("harvest", "harvest"))

        val shortTheme = ThemeExtractor.themes(shortText).single()
        val longTheme = ThemeExtractor.themes(longText).single()

        assertEquals(2, shortTheme.mentionCount)
        assertEquals(2, longTheme.mentionCount)
        assertEquals(2.0 / TranscriptNlp.wordCount(shortText), shortTheme.salience, 0.0)
        assertEquals(2.0 / TranscriptNlp.wordCount(longText), longTheme.salience, 0.0)
        assertTrue(shortTheme.salience > longTheme.salience)
    }

    // --- walkingDomain suppression ---

    @Test
    fun `walkingDomain lemmas are suppressed even when frequent enough to otherwise qualify`() {
        val text = pad(repeatedWords("path", 3) + repeatedWords("trail", 4) + repeatedWords("music", 2))

        val themes = ThemeExtractor.themes(text)

        assertEquals(1, themes.size)
        assertEquals("music", themes.single().lemma)
        assertEquals(2, themes.single().mentionCount)
        assertEquals(2.0 / TranscriptNlp.wordCount(text), themes.single().salience, 0.0)
    }

    // --- display-term selection: max count, tie-broken by smallest surface string ---

    @Test
    fun `display term is the cohort's most frequent surface form`() {
        val text = pad(repeatedWords("mountain", 1) + repeatedWords("mountains", 2))

        val theme = ThemeExtractor.themes(text).single()

        assertEquals("mountain", theme.lemma)
        assertEquals("mountains", theme.displayTerm)
        assertEquals(3, theme.mentionCount)
        assertEquals(3.0 / TranscriptNlp.wordCount(text), theme.salience, 0.0)
    }

    @Test
    fun `display term ties are broken by the lexicographically smallest surface string`() {
        val text = pad(repeatedWords("mountain", 1) + repeatedWords("mountains", 1))

        val theme = ThemeExtractor.themes(text).single()

        assertEquals("mountain", theme.lemma)
        assertEquals("mountain", theme.displayTerm)
        assertEquals(2, theme.mentionCount)
        assertEquals(2.0 / TranscriptNlp.wordCount(text), theme.salience, 0.0)
    }

    // --- maxThemes cap + final ranking tie-break (salience desc, lemma asc) ---

    @Test
    fun `themes caps at maxThemes and breaks salience ties by lemma ascending`() {
        val text = repeatedWords("harvest", 5) +
            repeatedWords("temple", 4) +
            repeatedWords("candle", 3) +
            repeatedWords("lantern", 3) +
            repeatedWords("forest", 2) +
            repeatedWords("garden", 2) +
            repeatedWords("music", 2) +
            repeatedWords("river", 2)

        val themes = ThemeExtractor.themes(text)

        assertEquals(ThemeExtractor.MAX_THEMES, themes.size)
        assertEquals(
            listOf("harvest", "temple", "candle", "lantern", "forest", "garden"),
            themes.map { it.lemma },
        )
    }

    // --- determinism ---

    @Test
    fun `themes is deterministic for identical input`() {
        val text = repeatedWords("harvest", 5) + repeatedWords("temple", 4) + repeatedWords("music", 3)
        assertEquals(ThemeExtractor.themes(text), ThemeExtractor.themes(text))
    }

    private fun repeatedWords(word: String, times: Int): String = "the $word ".repeat(times)

    /** Pads [text] with filler tokens that are never WordNet nouns, purely to clear
     * [ThemeExtractor.MINIMUM_WORDS] without introducing any new theme candidate. */
    private fun pad(text: String): String = "$text${" and".repeat(30)}"

    private fun wordsOfLength(total: Int, content: List<String>): String =
        (content + List(total - content.size) { "and" }).joinToString(" ")
}
