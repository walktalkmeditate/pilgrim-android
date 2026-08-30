// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the committed `assets/threads/` derived artifacts against silent
 * drift, and pins the specific WordNet-derived outcomes the U3 task brief
 * calls out by name.
 *
 * A note on the "days" fixture: the brief's illustrative wording expected
 * `days` to lemmatize to `day` (the ordinary plural rule). Against the
 * REAL, checksum-verified WordNet 3.1 data, `days` is independently listed
 * as its own noun sense (offset 15166019, gloss "the time during which
 * someone's life continues" — e.g. "the monarch's last days") alongside
 * "hours", "years", "times", and "hearts", so Morphy's own-form-first
 * priority (decision 2: "matching Princeton Morphy semantics", verified
 * here against both a hand-derived reference and NLTK's
 * `WordNetCorpusReader.morphy` run against this exact archive) correctly
 * leaves it unchanged. "thoughts" -> "thought" below demonstrates the same
 * regular-plural rule the brief's example was pointing at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NlpAssetPinTest {

    private lateinit var context: Application
    private lateinit var lexicon: WordNetLexicon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        lexicon = WordNetLexicon(context, json)
    }

    @Test
    fun `manifest sha256 matches every committed asset byte-for-byte`() {
        val manifest = readManifest()
        val assets = manifest.jsonObject.getValue("assets").jsonObject
        for ((name, entry) in assets) {
            val bytes = context.assets.open("threads/$name").use { it.readBytes() }
            val expectedSha256 = entry.jsonObject.getValue("sha256").jsonPrimitive.content
            assertEquals("sha256 mismatch for $name", expectedSha256, sha256Hex(bytes))
        }
    }

    @Test
    fun `manifest entry counts match the committed line-based assets`() {
        val manifest = readManifest()
        val assets = manifest.jsonObject.getValue("assets").jsonObject
        for (name in listOf("nouns.txt.gzip", "verbs.txt.gzip", "adjectives.txt.gzip", "synsets.txt.gzip")) {
            val expectedEntries = assets.getValue(name).jsonObject.getValue("entries").jsonPrimitive.content.toInt()
            val actualEntries = gunzipLines(name).size
            assertEquals("entry count mismatch for $name", expectedEntries, actualEntries)
        }
    }

    @Test
    fun `grieving lemmatizes to grieve as a verb`() {
        assertEquals("grieve", lexicon.lemmatize("grieving", PosClass.VERB))
    }

    @Test
    fun `days lemmatizes to itself as a noun, verified against real WordNet data`() {
        // See the class KDoc for why this is "days", not "day".
        assertEquals("days", lexicon.lemmatize("days", PosClass.NOUN))
    }

    @Test
    fun `thoughts lemmatizes to thought as a noun`() {
        assertEquals("thought", lexicon.lemmatize("thoughts", PosClass.NOUN))
    }

    @Test
    fun `was is not admitted as a noun`() {
        assertNull(lexicon.lemmatize("was", PosClass.NOUN))
        // The mechanism: "wa" (the two-letter Washington-state abbreviation
        // WordNet lists) is exactly what the noun "s"-stripping suffix
        // rule would reduce "was" to; excluding 1-2 character noun lemmas
        // (decision 1) is what keeps this from resolving.
        assertFalse("'wa' must be excluded (1-2 char noun exclusion)", lexicon.isListed("wa", PosClass.NOUN))
    }

    @Test
    fun `washington remains a listed noun (guards against prefix-match bugs)`() {
        assertTrue(lexicon.isListed("washington", PosClass.NOUN))
    }

    @Test
    fun `grief and sorrow share a WordNet synset`() {
        val shared = lexicon.synsets("grief").toHashSet().intersect(lexicon.synsets("sorrow").toSet())
        assertTrue(shared.isNotEmpty())
    }

    @Test
    fun `grief and bicycle share no WordNet synset`() {
        val shared = lexicon.synsets("grief").toHashSet().intersect(lexicon.synsets("bicycle").toSet())
        assertTrue(shared.isEmpty())
    }

    @Test
    fun `nasa is excluded as an abbreviation-only noun entry`() {
        assertFalse(lexicon.isListed("nasa", PosClass.NOUN))
    }

    private fun readManifest() =
        Json.parseToJsonElement(context.assets.open("threads/manifest.json").bufferedReader().use { it.readText() })

    private fun gunzipLines(name: String): List<String> =
        GZIPInputStream(context.assets.open("threads/$name")).bufferedReader().use { reader ->
            reader.lineSequence().filter { it.isNotEmpty() }.toList()
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
