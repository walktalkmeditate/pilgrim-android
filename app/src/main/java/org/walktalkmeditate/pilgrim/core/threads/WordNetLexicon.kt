// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The three WordNet parts of speech the Thought Threads substrate models. */
enum class PosClass { NOUN, VERB, ADJECTIVE }

/**
 * Princeton WordNet 3.1 lemmatizer + part-of-speech dictionary, derived at
 * build time by `tools/threads/derive_nlp_assets.py` into gzip'd flat
 * files under `assets/threads/` (manifest + provenance:
 * `app/src/main/assets/threads/manifest.json`).
 *
 * The constructor does no I/O — assets are parsed once, lazily, on the
 * first call to any query method (`by lazy`'s default
 * [LazyThreadSafetyMode.SYNCHRONIZED] serializes concurrent first-callers
 * onto a single load rather than racing). Hilt may construct this
 * `@Singleton` eagerly on any thread; only the first real query pays the
 * asset-parse cost, and unit tests that never call a query method never
 * touch the filesystem (Stage 5-C's init-block-I/O test-poisoning trap).
 *
 * [lemmatize] implements Morphy exactly: an exact hit in the irregular-form
 * exception list wins outright; otherwise every suffix-detachment rule for
 * [pos] that matches is tried, and the first of (surface, then each rule's
 * output in rule order) that is itself a listed word of [pos] wins — this
 * mirrors Princeton's own morphy.c (independently verified against NLTK's
 * `WordNetCorpusReader.morphy`, run against this exact pinned WordNet 3.1
 * archive; see WordNetLexiconTest).
 */
@Singleton
class WordNetLexicon @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val data: LoadedData by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

    /**
     * Resolves [surface] to a base form listed under [pos], or `null` if
     * no candidate (the surface itself, or any suffix-rule output) is a
     * word WordNet lists for that part of speech.
     */
    fun lemmatize(surface: String, pos: PosClass): String? {
        val entries = data.entries(pos)
        val exceptionForms = data.exceptions(pos)[surface]
        val candidates = exceptionForms ?: applySuffixRules(surface, data.rules(pos))
        if (surface in entries) return surface
        return candidates.firstOrNull { it in entries }
    }

    /** Whether [lemma] is a word WordNet 3.1 lists under [pos] (after this
     * substrate's own abbreviation/reachability exclusions — see the
     * derivation script's module docstring). */
    fun isListed(lemma: String, pos: PosClass): Boolean = lemma in data.entries(pos)

    /**
     * Opaque synset identifiers for [lemma] (noun and verb synsets
     * combined), or an empty array if [lemma] belongs to none. These are
     * NOT raw WordNet byte offsets — noun and verb offsets occupy
     * colliding numeric ranges in the source data, so verb offsets are
     * shipped pre-biased by the derivation script to keep the two spaces
     * disjoint. Only ever compare values returned from this method against
     * each other (as [TranscriptNlp.related] does); never decode them back
     * to a real WordNet offset. The returned array is the same backing
     * instance held internally (no defensive copy, to keep this cheap for
     * calls inside a loop) — treat it as read-only; mutating it would
     * corrupt every future call for the same lemma.
     */
    fun synsets(lemma: String): IntArray = data.synsets[lemma] ?: EMPTY_OFFSETS

    private fun applySuffixRules(surface: String, rules: List<Pair<String, String>>): List<String> =
        rules.mapNotNull { (suffix, replacement) ->
            surface.takeIf { it.endsWith(suffix) }?.let { it.dropLast(suffix.length) + replacement }
        }

    private fun load(): LoadedData {
        val nouns = readLineSet("nouns.txt.gzip")
        val verbs = readLineSet("verbs.txt.gzip")
        val adjectives = readLineSet("adjectives.txt.gzip")
        val nounExceptions = readExceptions("noun-exceptions.txt.gzip")
        val verbExceptions = readExceptions("verb-exceptions.txt.gzip")
        val adjectiveExceptions = readExceptions("adjective-exceptions.txt.gzip")
        val synsets = readSynsets("synsets.txt.gzip")
        val rules = readMorphologyRules("morphology-rules.json.gzip")
        return LoadedData(
            nouns = nouns,
            verbs = verbs,
            adjectives = adjectives,
            nounExceptions = nounExceptions,
            verbExceptions = verbExceptions,
            adjectiveExceptions = adjectiveExceptions,
            synsets = synsets,
            nounRules = rules["noun"].orEmpty(),
            verbRules = rules["verb"].orEmpty(),
            adjectiveRules = rules["adjective"].orEmpty(),
        )
    }

    /**
     * [name] must end in `.gzip`, never `.gz` — AGP's asset merge step
     * auto-decompresses `.gz`-suffixed assets and strips the suffix
     * before packaging (confirmed empirically; see
     * `tools/threads/derive_nlp_assets.py`'s module docstring), which
     * would silently rename every file this reads.
     */
    private fun openGzipAsset(name: String): BufferedReader =
        GZIPInputStream(context.assets.open("$ASSET_ROOT/$name")).bufferedReader()

    private fun readLineSet(name: String): Set<String> =
        openGzipAsset(name).use { reader -> reader.lineSequence().filterTo(HashSet()) { it.isNotEmpty() } }

    private fun readExceptions(name: String): Map<String, List<String>> =
        openGzipAsset(name).use { reader ->
            reader.lineSequence()
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val (key, bases) = line.split("\t", limit = 2)
                    key to bases.split(",")
                }
        }

    private fun readSynsets(name: String): Map<String, IntArray> =
        openGzipAsset(name).use { reader ->
            reader.lineSequence()
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val (lemma, offsets) = line.split("\t", limit = 2)
                    lemma to offsets.split(",").map { it.toInt() }.toIntArray()
                }
        }

    private fun readMorphologyRules(name: String): Map<String, List<Pair<String, String>>> {
        val text = openGzipAsset(name).use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        return root.mapValues { (_, rulesJson) ->
            rulesJson.jsonArray.map { pair ->
                val (suffix, replacement) = pair.jsonArray
                suffix.jsonPrimitive.content to replacement.jsonPrimitive.content
            }
        }
    }

    private class LoadedData(
        private val nouns: Set<String>,
        private val verbs: Set<String>,
        private val adjectives: Set<String>,
        private val nounExceptions: Map<String, List<String>>,
        private val verbExceptions: Map<String, List<String>>,
        private val adjectiveExceptions: Map<String, List<String>>,
        val synsets: Map<String, IntArray>,
        private val nounRules: List<Pair<String, String>>,
        private val verbRules: List<Pair<String, String>>,
        private val adjectiveRules: List<Pair<String, String>>,
    ) {
        fun entries(pos: PosClass): Set<String> = when (pos) {
            PosClass.NOUN -> nouns
            PosClass.VERB -> verbs
            PosClass.ADJECTIVE -> adjectives
        }

        fun exceptions(pos: PosClass): Map<String, List<String>> = when (pos) {
            PosClass.NOUN -> nounExceptions
            PosClass.VERB -> verbExceptions
            PosClass.ADJECTIVE -> adjectiveExceptions
        }

        fun rules(pos: PosClass): List<Pair<String, String>> = when (pos) {
            PosClass.NOUN -> nounRules
            PosClass.VERB -> verbRules
            PosClass.ADJECTIVE -> adjectiveRules
        }
    }

    private companion object {
        const val ASSET_ROOT = "threads"
        val EMPTY_OFFSETS = IntArray(0)
    }
}
