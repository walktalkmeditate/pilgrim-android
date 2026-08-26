// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase

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

/**
 * A real [ThreadsDossierBuilder] over a real analyzer/store (see
 * [realTranscriptContextAnalyzerForTests]) plus the Room DAOs [db]
 * already provides — for call sites that need a working collaborator
 * (a Hilt-shaped constructor parameter, a genuine end-to-end wiring
 * test), not a place to fake dossier behavior. [preferences] defaults to
 * the production default (`threadsAfterWalks` true) — pass a toggled-off
 * fake for call sites that specifically want the dossier to stay null.
 */
fun realThreadsDossierBuilderForTests(
    context: Context,
    db: PilgrimDatabase,
    preferences: ThreadsPreferencesRepository = FakeThreadsPreferencesRepository(),
): ThreadsDossierBuilder {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val store = TranscriptContextStore(context, json)
    val analyzer = realTranscriptContextAnalyzerForTests(context, preferences)
    return ThreadsDossierBuilder(
        store = store,
        analyzer = analyzer,
        preferences = preferences,
        voiceRecordingDao = db.voiceRecordingDao(),
        walkDao = db.walkDao(),
        routeDataSampleDao = db.routeDataSampleDao(),
        walkPhotoDao = db.walkPhotoDao(),
        altitudeSampleDao = db.altitudeSampleDao(),
    )
}
