// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient

/**
 * A real [TranscriptContextAnalyzer] over a real Robolectric-backed
 * [TranscriptContextStore] + [ThreadsAnalysisEnvironment] (real WordNet/
 * VADER assets), with only the language client faked (always reports
 * English at high confidence). [TranscriptContextAnalyzer] is not `open`,
 * so tests that merely need a working collaborator — not a place to
 * fake analysis behavior — construct one of these rather than a bespoke
 * fake per file (the U7 edit-path wiring's shared write path, BEH-59
 * carry, needed this in four separate ViewModel test files).
 */
fun realTranscriptContextAnalyzerForTests(
    context: Context,
    preferences: ThreadsPreferencesRepository = FakeThreadsPreferencesRepository(),
): TranscriptContextAnalyzer {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val store = TranscriptContextStore(context, json)
    val environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
    val languageClient = MlKitLanguageIdClient(
        object : LanguageIdentifierGateway {
            override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                listOf(LanguageGuess("en", 0.99f))
        },
    )
    return TranscriptContextAnalyzer(store, environment, languageClient, preferences)
}
