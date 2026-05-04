// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.photo.FakeBitmapLoader

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoContextAnalyzerTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val testUri: Uri = Uri.parse("content://media/external/images/media/42")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "photo-context-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun redBitmap(): Bitmap = Bitmap
        .createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.RED) }

    private fun newAnalyzer(
        bitmapLoader: FakeBitmapLoader = FakeBitmapLoader(bitmap = redBitmap()),
        imageLabeler: FakeImageLabelerClient = FakeImageLabelerClient(),
        textRecognizer: FakeTextRecognizerClient = FakeTextRecognizerClient(),
        faceDetector: FakeFaceDetectorClient = FakeFaceDetectorClient(),
    ): PhotoContextAnalyzer = PhotoContextAnalyzer(
        dataStore = dataStore,
        json = json,
        bitmapLoader = bitmapLoader,
        imageLabeler = imageLabeler,
        textRecognizer = textRecognizer,
        faceDetector = faceDetector,
    )

    @Test
    fun `cache miss runs all pipelines and persists`() = runTest(dispatcher) {
        val labeler = FakeImageLabelerClient(
            nextResult = listOf(LabeledTag("Plant", 0.9f)),
        )
        val recognizer = FakeTextRecognizerClient(nextResult = listOf("morning fog"))
        val detector = FakeFaceDetectorClient(nextResult = 1)
        val analyzer = newAnalyzer(
            imageLabeler = labeler,
            textRecognizer = recognizer,
            faceDetector = detector,
        )

        val first = analyzer.analyze(testUri)
        assertEquals(1, labeler.calls)
        assertEquals(1, recognizer.calls)
        assertEquals(1, detector.calls)
        assertEquals(listOf("plant"), first.tags)
        assertEquals(listOf("morning fog"), first.detectedText)
        assertEquals(1, first.people)

        val second = analyzer.analyze(testUri)
        assertEquals("cache hit must not re-run labeler", 1, labeler.calls)
        assertEquals("cache hit must not re-run recognizer", 1, recognizer.calls)
        assertEquals("cache hit must not re-run detector", 1, detector.calls)
        assertEquals(first, second)
    }

    @Test
    fun `labels filtered by confidence and capped at 8`() = runTest(dispatcher) {
        val labels = (1..12).map { i ->
            // i=1..12: confidences 0.10, 0.20, ..., 1.20 — six below 0.3, six above
            LabeledTag(text = "label$i", confidence = i * 0.1f)
        }
        val analyzer = newAnalyzer(imageLabeler = FakeImageLabelerClient(nextResult = labels))

        val result = analyzer.analyze(testUri)

        assertEquals(8, result.tags.size)
        // Highest confidence first.
        assertEquals("label12", result.tags.first())
        // Lowest taken (0.5): label5. Anything below 0.3 must be excluded.
        assertTrue("label1 (0.1) must be excluded", result.tags.none { it == "label1" })
        assertTrue("label2 (0.2) must be excluded", result.tags.none { it == "label2" })
        assertTrue("label3 (0.3) must be excluded (threshold > 0.3)", result.tags.none { it == "label3" })
    }

    @Test
    fun `tags lowercased`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            imageLabeler = FakeImageLabelerClient(nextResult = listOf(LabeledTag("Sky", 0.9f))),
        )

        val result = analyzer.analyze(testUri)

        assertEquals(listOf("sky"), result.tags)
    }

    @Test
    fun `text recognition filters personal info`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            textRecognizer = FakeTextRecognizerClient(
                nextResult = listOf(
                    "415-555-1234",
                    "user@example.com",
                    "https://example.com",
                    "www.example.com",
                    "morning fog",
                ),
            ),
        )

        val result = analyzer.analyze(testUri)

        assertEquals(listOf("morning fog"), result.detectedText)
    }

    @Test
    fun `text recognition drops over 50 chars`() = runTest(dispatcher) {
        val short = "short line"
        val long = "x".repeat(51)
        val analyzer = newAnalyzer(
            textRecognizer = FakeTextRecognizerClient(nextResult = listOf(short, long)),
        )

        val result = analyzer.analyze(testUri)

        assertEquals(listOf(short), result.detectedText)
    }

    @Test
    fun `text recognition takes first 5 lines`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            textRecognizer = FakeTextRecognizerClient(
                nextResult = (1..8).map { "line $it" },
            ),
        )

        val result = analyzer.analyze(testUri)

        assertEquals(5, result.detectedText.size)
        assertEquals("line 1", result.detectedText.first())
        assertEquals("line 5", result.detectedText.last())
    }

    @Test
    fun `face count passes through`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(faceDetector = FakeFaceDetectorClient(nextResult = 3))

        val result = analyzer.analyze(testUri)

        assertEquals(3, result.people)
    }

    @Test
    fun `outdoor true on outdoor tag intersection`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            imageLabeler = FakeImageLabelerClient(
                nextResult = listOf(
                    LabeledTag("rock", 0.9f),
                    LabeledTag("Mountain", 0.7f),
                ),
            ),
        )

        val result = analyzer.analyze(testUri)

        assertTrue("mountain in OUTDOOR_TAG_SET → outdoor=true", result.outdoor)
    }

    @Test
    fun `outdoor false on indoor-only tags`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            imageLabeler = FakeImageLabelerClient(
                nextResult = listOf(
                    LabeledTag("chair", 0.9f),
                    LabeledTag("lamp", 0.8f),
                ),
            ),
        )

        val result = analyzer.analyze(testUri)

        assertFalse(result.outdoor)
    }

    @Test
    fun `dominant color matches solid red bitmap`() = runTest(dispatcher) {
        val analyzer = newAnalyzer(
            bitmapLoader = FakeBitmapLoader(bitmap = redBitmap()),
        )

        val result = analyzer.analyze(testUri)

        assertEquals("#FF0000", result.dominantColor)
    }

    @Test
    fun `pipeline failure returns fallback and does not cache`() = runTest(dispatcher) {
        val labeler = FakeImageLabelerClient(throwOnLabel = RuntimeException("simulated failure"))
        val analyzer = newAnalyzer(imageLabeler = labeler)

        val first = analyzer.analyze(testUri)
        assertEquals(emptyList<String>(), first.tags)
        assertEquals(emptyList<String>(), first.detectedText)
        assertEquals(0, first.people)
        assertFalse(first.outdoor)
        assertEquals(1, labeler.calls)

        // Second call must re-run pipeline (failure was NOT cached).
        val second = analyzer.analyze(testUri)
        assertEquals("failure result must not be cached", 2, labeler.calls)
        assertEquals(first, second)
    }

    @Test
    fun `cancellation propagates without cache write`() = runTest(dispatcher) {
        val labeler = FakeImageLabelerClient(throwOnLabel = CancellationException("cancelled"))
        val analyzer = newAnalyzer(imageLabeler = labeler)

        try {
            analyzer.analyze(testUri)
            fail("expected CancellationException")
        } catch (ce: CancellationException) {
            // expected
            assertNotNull(ce)
        }

        // A subsequent analyze with a working labeler must hit the
        // pipeline (cache stayed empty after the cancellation).
        val nextLabeler = FakeImageLabelerClient(nextResult = listOf(LabeledTag("plant", 0.9f)))
        val analyzer2 = newAnalyzer(imageLabeler = nextLabeler)
        val result = analyzer2.analyze(testUri)
        assertEquals(1, nextLabeler.calls)
        assertEquals(listOf("plant"), result.tags)
    }
}

private class FakeImageLabelerClient(
    var nextResult: List<LabeledTag> = emptyList(),
    var throwOnLabel: Throwable? = null,
) : ImageLabelerClient {
    var calls: Int = 0
        private set

    override suspend fun label(bitmap: Bitmap): List<LabeledTag> {
        calls++
        throwOnLabel?.let { throw it }
        return nextResult
    }
}

private class FakeTextRecognizerClient(
    var nextResult: List<String> = emptyList(),
    var throwOnRecognize: Throwable? = null,
) : TextRecognizerClient {
    var calls: Int = 0
        private set

    override suspend fun recognize(bitmap: Bitmap): List<String> {
        calls++
        throwOnRecognize?.let { throw it }
        return nextResult
    }
}

private class FakeFaceDetectorClient(
    var nextResult: Int = 0,
    var throwOnDetect: Throwable? = null,
) : FaceDetectorClient {
    var calls: Int = 0
        private set

    override suspend fun detect(bitmap: Bitmap): Int {
        calls++
        throwOnDetect?.let { throw it }
        return nextResult
    }
}
