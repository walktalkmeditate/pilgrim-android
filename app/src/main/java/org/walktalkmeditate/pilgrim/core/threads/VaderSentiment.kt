// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlin.math.sqrt

/**
 * A deliberately coarse subset of VADER (Hutto & Gilbert, 2014) — the iOS
 * parity bar for this signal is "prompt-side only, optional slot", so
 * determinism and fixture-stability matter more than reproducing every
 * clause of canonical VADER's `sentiment_valence`. Ported from the
 * canonical `vaderSentiment` lexicon (MIT; provenance in
 * `tools/threads/derive_nlp_assets.py` and
 * `app/src/main/assets/threads/manifest.json`).
 *
 * Implemented:
 *  - Per-token lexicon lookup over [TranscriptNlp.wordTokens] (the same
 *    single tokenizer every other Thought Threads count uses).
 *  - Negation: if any of the 3 tokens before a lexicon hit is a negation
 *    word, its value is multiplied by [NEGATION_SCALAR] (canonical
 *    VADER's constant).
 *  - Boosters/dampeners: any of the 3 tokens before a lexicon hit that is
 *    a booster/dampener word adds its (sign-matched) increment; multiple
 *    boosters in the window sum, applied flat (no distance-based 0.95/0.9
 *    damping).
 *  - Compound-score normalization, alpha=15, exactly as canonical VADER's
 *    `normalize()`.
 *
 * Deliberately omitted (none of these affect voice-transcript prose in a
 * way worth the added surface): ALL-CAPS emphasis, exclamation/question
 * punctuation emphasis, the "never so/this" and "without doubt" special
 * negation cases, the "least" check, "but"-contrastive re-weighting,
 * idiom/special-case phrase lists, and the emoji lexicon. Numeric parity
 * with canonical VADER's compound score is explicitly NOT a goal (Android
 * implementation notes, Open question 18) — determinism given the same
 * input is.
 *
 * Both word lists below are filtered to single lowercase-letter tokens
 * only: canonical VADER also lists a few multi-word ("kind of", "sort of",
 * "just enough") and hyphenated ("kind-of", "sort-of", "uh-uh") entries,
 * but [TranscriptNlp.wordTokens] can never produce a token containing a
 * space or hyphen, so those entries could never match and are left out
 * rather than shipped as dead weight.
 */
object VaderSentiment {

    @Volatile
    private var lexicon: Map<String, Double>? = null

    /** Wires the token -> mean-sentiment lexicon [score] reads from
     * (parsed from `assets/threads/vader-lexicon.txt.gzip` by whichever
     * caller installs this feature's DI graph — see [TranscriptNlp]'s
     * KDoc for why a bare Kotlin `object` needs this rather than Hilt
     * constructor injection). */
    fun install(lexicon: Map<String, Double>) {
        this.lexicon = lexicon
    }

    /**
     * Compound sentiment score in roughly [-1, 1], or `null` if [text] has
     * no token the lexicon covers (including empty/blank text).
     */
    fun score(text: String): Double? {
        val lex = requireLexicon()
        val tokens = TranscriptNlp.wordTokens(text)
        var sum = 0.0
        var covered = false
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token in BOOSTER_WORDS) continue
            val base = lex[token] ?: continue
            covered = true
            sum += valenceFor(tokens, i, base)
        }
        if (!covered) return null
        return normalize(sum)
    }

    private fun requireLexicon(): Map<String, Double> =
        lexicon ?: error(
            "VaderSentiment.install(lexicon) must run before score() is called — see this object's KDoc.",
        )

    private fun valenceFor(tokens: List<String>, index: Int, base: Double): Double {
        val negated = (1..NEGATION_WINDOW).any { distance ->
            val precedingIndex = index - distance
            precedingIndex >= 0 && tokens[precedingIndex] in NEGATION_WORDS
        }
        var valence = if (negated) base * NEGATION_SCALAR else base
        for (distance in 1..NEGATION_WINDOW) {
            val precedingIndex = index - distance
            if (precedingIndex < 0) continue
            val increment = BOOSTER_WORDS[tokens[precedingIndex]] ?: continue
            valence += if (valence < 0) -increment else increment
        }
        return valence
    }

    private fun normalize(sum: Double): Double {
        val normalized = sum / sqrt(sum * sum + ALPHA)
        return normalized.coerceIn(-1.0, 1.0)
    }

    private const val ALPHA = 15.0
    private const val NEGATION_SCALAR = -0.74
    private const val NEGATION_WINDOW = 3
    private const val BOOST = 0.293

    private val NEGATION_WORDS = setOf(
        "aint", "arent", "cannot", "cant", "couldnt", "darent", "didnt", "doesnt",
        "dont", "hadnt", "hasnt", "havent", "isnt", "mightnt", "mustnt", "neither",
        "neednt", "never", "none", "nope", "nor", "not", "nothing", "nowhere",
        "oughtnt", "shant", "shouldnt", "uhuh", "wasnt", "werent",
        "without", "wont", "wouldnt", "rarely", "seldom", "despite",
    )

    private val BOOSTER_WORDS: Map<String, Double> = buildMap {
        for (word in listOf(
            "absolutely", "amazingly", "awfully", "completely", "considerable", "considerably",
            "decidedly", "deeply", "effing", "enormous", "enormously", "entirely", "especially",
            "exceptional", "exceptionally", "extreme", "extremely", "fabulously", "flipping",
            "flippin", "frackin", "fracking", "fricking", "frickin", "frigging", "friggin",
            "fully", "fuckin", "fucking", "fuggin", "fugging", "greatly", "hella", "highly",
            "hugely", "incredible", "incredibly", "intensely", "major", "majorly", "more",
            "most", "particularly", "purely", "quite", "really", "remarkably", "so",
            "substantially", "thoroughly", "total", "totally", "tremendous", "tremendously",
            "uber", "unbelievably", "unusually", "utter", "utterly", "very",
        )) put(word, BOOST)
        for (word in listOf(
            "almost", "barely", "hardly", "kinda", "kindof", "less", "little", "marginal",
            "marginally", "occasional", "occasionally", "partly", "scarce", "scarcely",
            "slight", "slightly", "somewhat", "sorta", "sortof",
        )) put(word, -BOOST)
    }
}
