// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

// MARK: - Fixture JSON shape (mirrors resources/threads/golden/corpus/fixture.json)
// `ignoreUnknownKeys` covers the documentation-only fields (`_comment`,
// `label`, `sensesDesignNotes`) this test has no need to model.

@Serializable
private data class GoldenFixture(
    val buildNowEpochMs: Long,
    val backfillComplete: Boolean,
    val moonLineLastReportedLunationIndex: Int,
    val walks: List<GoldenWalk>,
)

@Serializable
private data class GoldenWalk(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val intention: String? = null,
    val weatherCondition: String? = null,
    val recordings: List<GoldenRecording>,
    val altitudeSamples: List<GoldenAltitudeSample> = emptyList(),
    val photos: List<GoldenPhoto> = emptyList(),
)

@Serializable
private data class GoldenRecording(
    val uuid: String,
    val transcriptFile: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val wordsPerMinute: Double,
    val routeFix: GoldenRouteFix? = null,
)

@Serializable
private data class GoldenRouteFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double,
)

@Serializable
private data class GoldenAltitudeSample(val offsetMs: Long, val altitudeMeters: Double)

@Serializable
private data class GoldenPhoto(val capturedAtOffsetMs: Long, val latitude: Double, val longitude: Double)

private const val CURRENT_WALK_ID = "walk-current"

/**
 * The cross-platform golden dossier fixture (Task U11). Renders the SAME
 * hand-authored synthetic corpus (`app/src/test/resources/threads/golden/`)
 * through the REAL Android pipeline — [TranscriptContextAnalyzer] (real
 * WordNet lemmatization + VADER sentiment), [ThreadStore] aggregation,
 * [ThreadsDossierFormatter] rendering, and [DossierSenses] — the same way
 * [ThreadsDossierBuilderTest] does, and asserts the result against the
 * dossier text captured from the REAL iOS `ThreadsDossierBuilder.build` at
 * the `0172e2b` pin (see `golden/README.md` for the full capture procedure
 * and the corpus design rationale, including which of the eight senses
 * this corpus does and does not exercise and why).
 *
 * [normalizeSentiment] is the ONLY normalization this test applies —
 * VADER-lite (Android) and per-sentence `NLTagger` (iOS) sentiment scoring
 * are different algorithms by design (parity spec BEH-13's documented
 * Android divergence) and are not expected to agree numerically. The
 * corpus's other two acknowledged allowances (theme-set drift from the
 * lemma engine; synset-vs-embedding echo outcomes) are deliberately NOT
 * exercised by this fixture — its four theme words (river/garden/bridge/
 * mountain) are simple, unambiguous, uninflected singular nouns chosen
 * specifically to avoid lemma-engine drift, and no sense this corpus fires
 * (`placeResonance`, `moonLine`, `speechShape`) reads `related()`/synset
 * membership at all. A mismatch anywhere outside the normalized sentiment
 * clauses therefore fails this test loudly, with the full two-sided text
 * in the assertion message, not a silently-widened allowance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsGoldenDossierTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var store: TranscriptContextStore
    private lateinit var analyzer: TranscriptContextAnalyzer
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var builder: ThreadsDossierBuilder
    private lateinit var savedTimeZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        // The formatter's "(first spoken <Mon Day>)" clause and the moon
        // name's calendar-month lookup both read the JVM default zone/
        // locale when no override is passed — pinned to match the iOS
        // capture harness's `NSTimeZone.default = UTC` (see golden/README.md).
        savedTimeZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        val environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
        preferences = FakeThreadsPreferencesRepository()
        val languageClient = MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(LanguageGuess("en", 0.99f))
            },
        )
        analyzer = TranscriptContextAnalyzer(store, environment, languageClient, preferences)

        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        builder = ThreadsDossierBuilder(
            store, analyzer, preferences,
            db.voiceRecordingDao(), db.walkDao(),
            db.routeDataSampleDao(), db.walkPhotoDao(), db.altitudeSampleDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(savedTimeZone)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `the Android pipeline reproduces the iOS-captured golden dossier`() = runTest {
        val fixture = loadFixture()
        val walkIds = mutableMapOf<String, Long>()

        for (walk in fixture.walks) {
            val walkId = db.walkDao().insert(
                Walk(
                    startTimestamp = walk.startEpochMs,
                    endTimestamp = walk.endEpochMs,
                    intention = walk.intention,
                    weatherCondition = walk.weatherCondition,
                ),
            )
            walkIds[walk.id] = walkId

            for (recording in walk.recordings) {
                val startTimestamp = walk.startEpochMs + recording.startOffsetMs
                val endTimestamp = walk.startEpochMs + recording.endOffsetMs
                db.voiceRecordingDao().insert(
                    VoiceRecording(
                        uuid = recording.uuid,
                        walkId = walkId,
                        startTimestamp = startTimestamp,
                        endTimestamp = endTimestamp,
                        durationMillis = endTimestamp - startTimestamp,
                        fileRelativePath = "recordings/${recording.uuid}.wav",
                        transcription = loadTranscript(recording.transcriptFile),
                        wordsPerMinute = recording.wordsPerMinute,
                    ),
                )
                recording.routeFix?.let { fix ->
                    db.routeDataSampleDao().insert(
                        RouteDataSample(
                            walkId = walkId,
                            timestamp = startTimestamp,
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            horizontalAccuracyMeters = fix.horizontalAccuracyMeters.toFloat(),
                        ),
                    )
                }
            }

            for (sample in walk.altitudeSamples) {
                db.altitudeSampleDao().insert(
                    AltitudeSample(
                        walkId = walkId,
                        timestamp = walk.startEpochMs + sample.offsetMs,
                        altitudeMeters = sample.altitudeMeters,
                    ),
                )
            }

            for (photo in walk.photos) {
                db.walkPhotoDao().insert(
                    WalkPhoto(
                        walkId = walkId,
                        photoUri = "content://golden-fixture/${walk.id}",
                        pinnedAt = walk.startEpochMs + photo.capturedAtOffsetMs,
                        takenAt = walk.startEpochMs + photo.capturedAtOffsetMs,
                        capturedLat = photo.latitude,
                        capturedLng = photo.longitude,
                    ),
                )
            }
        }

        // Historical walks' contexts must already exist in the store before
        // build() runs — build() only self-heals the CURRENT walk's own
        // recordings (ThreadsDossierBuilderTest's established pattern).
        for (walk in fixture.walks) {
            if (walk.id == CURRENT_WALK_ID) continue
            for (recording in walk.recordings) {
                val text = loadTranscript(recording.transcriptFile)
                assertNotNull(
                    "analyzeAndStore must succeed for historical recording ${recording.uuid} " +
                        "(a null result means the corpus/analyzer disagree on English detection)",
                    analyzer.analyzeAndStore(recording.uuid, text),
                )
            }
        }

        assertTrue(fixture.backfillComplete)
        preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, preferences.importGeneration.value)
        preferences.setMoonLineLastLunationIndex(fixture.moonLineLastReportedLunationIndex)

        val currentWalkId = walkIds.getValue(CURRENT_WALK_ID)
        val now = Instant.ofEpochMilli(fixture.buildNowEpochMs)

        val block = builder.build(currentWalkId, now = now)

        assertNotNull("the golden fixture must produce a real dossier", block)
        val actual = normalizeSentiment(block!!.text)
        val expected = normalizeSentiment(loadExpectedDossier())

        assertEquals(
            "Android's real pipeline must reproduce the iOS-captured golden dossier " +
                "(section structure, order, caps, and template strings), modulo the " +
                "documented sentiment-value normalization only. See golden/README.md " +
                "for the capture procedure and this test's class KDoc for why the other " +
                "two acknowledged allowances (theme-set drift, synset-vs-embedding echo) " +
                "do not apply to this corpus.",
            expected,
            actual,
        )
    }

    /**
     * The one documented, visible allowance this test applies — see the
     * class KDoc. Applied identically to both sides before comparison so
     * the clause's presence/position/label still participate in the
     * equality check; only the platform-specific numeric value is erased.
     */
    private fun normalizeSentiment(dossier: String): String =
        SENTIMENT_PATTERN.replace(dossier) { "sentiment <NUM>" }

    private fun loadFixture(): GoldenFixture {
        val json = Json { ignoreUnknownKeys = true }
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("threads/golden/corpus/fixture.json"),
        ) { "missing test resource threads/golden/corpus/fixture.json" }
        return json.decodeFromString(GoldenFixture.serializer(), stream.bufferedReader().readText())
    }

    private fun loadTranscript(relativePath: String): String {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("threads/golden/corpus/$relativePath"),
        ) { "missing test resource threads/golden/corpus/$relativePath" }
        return stream.bufferedReader().readText().trim()
    }

    private fun loadExpectedDossier(): String {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("threads/golden/expected/current-walk-dossier.txt"),
        ) { "missing test resource threads/golden/expected/current-walk-dossier.txt" }
        return stream.bufferedReader().readText().removeSuffix("\n")
    }

    private companion object {
        val SENTIMENT_PATTERN = Regex("""sentiment -?\d+\.\d\d""")
    }
}
