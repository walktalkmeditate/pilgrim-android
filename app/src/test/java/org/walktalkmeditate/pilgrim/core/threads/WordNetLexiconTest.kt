// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
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
 * Morphy algorithm coverage against the real committed WordNet 3.1
 * assets — exception-list lookups, suffix-detachment rules across all
 * three parts of speech, and the [isListed]/[synsets] contract. The
 * brief-pinned fixture words (grieving/thoughts/days/was) live in
 * [NlpAssetPinTest] alongside the manifest/hash integrity checks; this
 * file covers the lemmatizer's general behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WordNetLexiconTest {

    private lateinit var lexicon: WordNetLexicon

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        lexicon = WordNetLexicon(context, json)
    }

    @Test
    fun `lemmatize resolves irregular plurals via the noun exception list`() {
        assertEquals("goose", lexicon.lemmatize("geese", PosClass.NOUN))
    }

    @Test
    fun `lemmatize resolves irregular comparatives via the adjective exception list`() {
        assertEquals("happy", lexicon.lemmatize("happier", PosClass.ADJECTIVE))
        assertEquals("happy", lexicon.lemmatize("happiest", PosClass.ADJECTIVE))
    }

    @Test
    fun `lemmatize resolves regular verbs via suffix-detachment rules`() {
        assertEquals("walk", lexicon.lemmatize("walked", PosClass.VERB))
        assertEquals("run", lexicon.lemmatize("running", PosClass.VERB))
    }

    @Test
    fun `lemmatize resolves regular nouns via suffix-detachment rules`() {
        assertEquals("box", lexicon.lemmatize("boxes", PosClass.NOUN))
    }

    @Test
    fun `lemmatize resolves regular adjectives via suffix-detachment rules`() {
        assertEquals("strong", lexicon.lemmatize("stronger", PosClass.ADJECTIVE))
    }

    @Test
    fun `lemmatize returns the surface itself when already a base form`() {
        assertEquals("walk", lexicon.lemmatize("walk", PosClass.VERB))
    }

    @Test
    fun `lemmatize returns null when no candidate is listed for that part of speech`() {
        assertNull(lexicon.lemmatize("zzquux", PosClass.NOUN))
    }

    @Test
    fun `isListed is true for a common word of the matching part of speech`() {
        assertTrue(lexicon.isListed("grieve", PosClass.VERB))
        assertTrue(lexicon.isListed("thought", PosClass.NOUN))
    }

    @Test
    fun `isListed is false for a word not admitted under that part of speech`() {
        assertFalse(lexicon.isListed("zzquux", PosClass.NOUN))
    }

    @Test
    fun `synsets is empty for a lemma with no synsets`() {
        assertTrue(lexicon.synsets("zzquux").isEmpty())
    }

    @Test
    fun `synsets is non-empty for a common lemma`() {
        assertTrue(lexicon.synsets("grief").isNotEmpty())
    }
}
