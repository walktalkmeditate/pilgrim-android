// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * [ThreadsFieldReport] (DEBUG-only ship-gate harness, `src/debug/kotlin`)
 * against real collaborators — parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`'s field-report section.
 * Uncapped, empty suppressed set, `moonState = null`, and NEVER writes
 * the real moon-line preference — these are the properties this test
 * suite exists to pin, since a QA tool that corrupts real device state
 * would be worse than no tool at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsFieldReportTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var store: TranscriptContextStore
    private lateinit var analyzer: TranscriptContextAnalyzer
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var report: ThreadsFieldReport

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        val environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
        preferences = FakeThreadsPreferencesRepository()
        val languageClient = org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient(
            object : org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String) =
                    listOf(org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess("en", 0.99f))
            },
        )
        analyzer = TranscriptContextAnalyzer(store, environment, languageClient, preferences)

        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        report = ThreadsFieldReport(
            walkDao = db.walkDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            routeDataSampleDao = db.routeDataSampleDao(),
            walkPhotoDao = db.walkPhotoDao(),
            altitudeSampleDao = db.altitudeSampleDao(),
            store = store,
            analyzer = analyzer,
            preferences = preferences,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val longText = "I was walking and I have to say I think about music because I can think about " +
        "music too and I will think about music again since I have so many things I want and need. " +
        "The river was wide and calm and I noticed the river every single time I walked beside the river " +
        "today, tracing its edge with my eyes."

    private suspend fun newWalk(startTimestamp: Long, endTimestamp: Long? = null): Long =
        db.walkDao().insert(Walk(startTimestamp = startTimestamp, endTimestamp = endTimestamp))

    private suspend fun newRecording(uuid: String, walkId: Long, transcription: String?, startTimestamp: Long = 1_000L): Long =
        db.voiceRecordingDao().insert(
            VoiceRecording(
                uuid = uuid,
                walkId = walkId,
                startTimestamp = startTimestamp,
                endTimestamp = startTimestamp + 1_000L,
                durationMillis = 1_000L,
                fileRelativePath = "recordings/$uuid.wav",
                transcription = transcription,
            ),
        )

    @Test
    fun `an empty device reports no walk history, banner and closer only`() = runTest {
        val text = report.run()

        assertTrue(text.startsWith("\n===== DOSSIER SENSES FIELD REPORT =====\n"))
        assertTrue(text.contains("\n(no walk history on this device — nothing to report)\n"))
        assertTrue(text.trimEnd().endsWith("=".repeat(39)))
    }

    @Test
    fun `walks with no transcribed recording report the no-transcribed-walks message`() = runTest {
        newWalk(startTimestamp = 1_000L)

        val text = report.run()

        assertTrue(text.contains("\n(no walk carries a transcribed recording — nothing to report)\n"))
    }

    @Test
    fun `an eligible walk gets a per-walk header, any firing lines, and a build-time line`() = runTest {
        val walkId = newWalk(startTimestamp = 0L, endTimestamp = 60_000L)
        newRecording("r1", walkId, longText, startTimestamp = 0L)

        val text = report.run(now = java.time.Instant.ofEpochMilli(60_000L))

        assertTrue(text.contains("\nWalk "))
        assertTrue(text.contains("build:"))
        assertTrue(text.contains("\nFiring rates over 1 walks with words:\n"))
        assertTrue(text.trimEnd().endsWith("=".repeat(39)))
    }

    @Test
    fun `firing rates enumerate all 8 senses, in declaration order`() = runTest {
        val walkId = newWalk(startTimestamp = 0L, endTimestamp = 60_000L)
        newRecording("r1", walkId, longText, startTimestamp = 0L)

        val text = report.run(now = java.time.Instant.ofEpochMilli(60_000L))
        val ratesSection = text.substringAfter("Firing rates over")

        val expectedOrder = listOf(
            "placeResonance", "moonLine", "markerColoring", "intentionLineage",
            "climbAnchoring", "weatherWeave", "photoAdjacency", "speechShape",
        )
        var lastIndex = -1
        for (name in expectedOrder) {
            val index = ratesSection.indexOf(name)
            assertTrue("$name must appear in the firing-rate table", index >= 0)
            assertTrue("$name must appear in declaration order", index > lastIndex)
            lastIndex = index
        }
    }

    @Test
    fun `the report never writes the real moon-line preference, even when moonLine fires`() = runTest {
        val walkId = newWalk(startTimestamp = 0L, endTimestamp = 60_000L)
        newRecording("r1", walkId, longText, startTimestamp = 0L)

        report.run(now = java.time.Instant.ofEpochMilli(60_000L))

        assertNull(
            "a QA harness run must never burn the real once-per-lunation budget",
            preferences.moonLineLastLunationIndex(),
        )
    }

    @Test
    fun `the report is uncapped and undeduped — moonState is always evaluated as null`() = runTest {
        // Pre-set a real moon-line index that would suppress moonLine in
        // PRODUCTION (moon.lastReportedIndex == moon.lunationIndex) — the
        // field report must still show it as eligible because it always
        // passes moonState: null, never the real preference value.
        val targetLunation = LunationCalendar.lunation(600)
        val now = targetLunation.end.plusSeconds(3_600)
        check(LunationCalendar.mostRecentClosed(asOf = now).index == targetLunation.index)
        preferences.setMoonLineLastLunationIndex(targetLunation.index)

        // The walk itself sits INSIDE the closed lunation and carries the
        // worded recording moonLine's wordedCount>=1 guard needs.
        val walkStart = targetLunation.start.plusSeconds(3_600)
        val walkId = newWalk(startTimestamp = walkStart.toEpochMilli(), endTimestamp = walkStart.plusSeconds(60).toEpochMilli())
        newRecording("r1", walkId, longText, startTimestamp = walkStart.toEpochMilli())

        val text = report.run(now = now)

        assertTrue(text.contains("[moonLine]"))
    }
}
