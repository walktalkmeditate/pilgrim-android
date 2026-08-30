// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlinx.serialization.Serializable

/** One occurrence of a content word in a transcript, offsets in UTF-16 code units. */
@Serializable
data class LemmaMention(val lemma: String, val surface: String, val start: Int, val length: Int)

/** One tokenizer output, offset in UTF-16 code units (see [TranscriptNlp]). */
data class WordToken(val token: String, val start: Int)

/**
 * The single tokenizer for every word count, density, and offset the
 * Thought Threads feature computes — a second implementation anywhere
 * downstream (ThemeExtractor's word floor, MarkerAnalyzer's densities, the
 * dossier formatter's density floor) would diverge the denominators behind
 * every absolutist %, self-focus %, and salience figure.
 *
 * Offsets throughout this object are Kotlin `String` indices — UTF-16 code
 * units. This is a deliberate, documented divergence from iOS (which
 * counts extended grapheme clusters): the requirement is consistency
 * *within* Android, not numeric parity with Swift, so every consumer that
 * stores or compares an offset from this object (mention offsets,
 * flagged-range containment) must stay on this same unit.
 *
 * [PosClass]-aware members ([contentLemmaMentions], [related]) read from a
 * [WordNetLexicon] installed via [install]. A Kotlin `object` cannot be a
 * Hilt injection target, so the lexicon is handed in once by whichever
 * caller wires this feature into the app's DI graph (see
 * [WordNetLexicon]'s KDoc for why that lexicon's own construction is cheap
 * and safe to force early); tests call [install] directly with a
 * Robolectric-backed or fixture-backed instance before exercising those
 * members.
 */
object TranscriptNlp {

    @Volatile
    private var lexicon: WordNetLexicon? = null

    /** Wires the lexicon [contentLemmaMentions] and [related] read from. */
    fun install(lexicon: WordNetLexicon) {
        this.lexicon = lexicon
    }

    /** Lowercase runs of Unicode letters; everything else is a separator. */
    fun wordTokens(text: String): List<String> =
        text.lowercase().split(NON_LETTER_RUN).filterNot { it.isEmpty() }

    fun wordCount(text: String): Int = wordTokens(text).size

    /**
     * Offsets for [wordTokens]' own output, located by forward search
     * through the lowercased text with a cursor advanced past each match —
     * deliberately not a second tokenizer (a regex scan run independently
     * of [wordTokens] could disagree with it on a boundary) and
     * deliberately not a plain `indexOf` per token (which would re-find
     * the first occurrence of every repeated word instead of successive
     * ones).
     */
    fun wordTokenOffsets(text: String): List<WordToken> {
        val lowered = text.lowercase()
        var cursor = 0
        val tokens = mutableListOf<WordToken>()
        for (token in wordTokens(text)) {
            val start = lowered.indexOf(token, cursor)
            if (start < 0) continue
            tokens += WordToken(token = token, start = start)
            cursor = start + token.length
        }
        return tokens
    }

    /**
     * Content-word mentions whose part of speech (per [WordNetLexicon]'s
     * dictionary, not contextual tagging — Android has no on-device
     * contextual POS tagger) is one of [classes]. A token shorter than 3
     * characters is never a candidate, matching iOS's `surface.count > 2`
     * floor exactly (`>= 2` would leak 1-2-letter words into every
     * downstream theme/marker count). Ties between multiple matching
     * classes (e.g. a word listed as both noun and verb) resolve in
     * [PosClass] declaration order (NOUN, VERB, ADJECTIVE), independent of
     * [classes]' own iteration order, so results are deterministic
     * regardless of how the caller built that set.
     */
    fun contentLemmaMentions(
        text: String,
        classes: Set<PosClass> = setOf(PosClass.NOUN, PosClass.VERB, PosClass.ADJECTIVE),
    ): List<LemmaMention> {
        val lex = requireLexicon()
        val mentions = mutableListOf<LemmaMention>()
        for (token in wordTokenOffsets(text)) {
            val surface = token.token
            if (surface.length <= MIN_CONTENT_SURFACE_LENGTH) continue
            val lemma = PosClass.entries
                .firstNotNullOfOrNull { pos -> if (pos in classes) lex.lemmatize(surface, pos) else null }
                ?: continue
            mentions += LemmaMention(lemma = lemma, surface = surface, start = token.start, length = surface.length)
        }
        return mentions
    }

    /**
     * Whether [a] and [b] are the same lemma or share a WordNet synset —
     * the Android replacement for iOS's `NLEmbedding` cosine-distance
     * relatedness (the 0.95 ceiling that tuned belongs to that embedding
     * space and does not transfer to synset membership). English-only in
     * v1 (R5): any other [languageCode] falls back to plain string
     * equality, matching iOS's own exact-match short-circuit, which iOS
     * evaluates before it even looks at the language.
     *
     * No additional caching here beyond [WordNetLexicon]'s own one-time
     * asset load: iOS caches per-language `NLEmbedding` instances because
     * loading one is expensive and language-keyed; this substrate loads
     * its (single, language-independent) synset map once, and every
     * [WordNetLexicon.synsets] call after that is a plain map lookup, so
     * there is no reload cost left to guard against.
     */
    fun related(a: String, b: String, languageCode: String): Boolean {
        if (a == b) return true
        if (languageCode != ENGLISH) return false
        val lex = requireLexicon()
        val synsetsA = lex.synsets(a)
        if (synsetsA.isEmpty()) return false
        val synsetsB = lex.synsets(b)
        if (synsetsB.isEmpty()) return false
        val bLookup = synsetsB.toHashSet()
        return synsetsA.any { it in bLookup }
    }

    private fun requireLexicon(): WordNetLexicon =
        lexicon ?: error(
            "TranscriptNlp.install(lexicon) must run before contentLemmaMentions()/related() " +
                "are called — see this object's KDoc.",
        )

    private const val MIN_CONTENT_SURFACE_LENGTH = 2
    private const val ENGLISH = "en"
    private val NON_LETTER_RUN = Regex("[^\\p{L}]+")
}
