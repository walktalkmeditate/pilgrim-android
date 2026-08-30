// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore

/**
 * [WhisperCppEngine.transcribeWithSegments] at the Kotlin JNI seam
 * ([FakeWhisperNative]) — the additive U5 segment surface. The real
 * native library needs a device; U12 covers on-device verification. This
 * class also pins [WhisperEngine.transcribeWithSegments]'s default
 * (delegate-to-[WhisperEngine.transcribe], empty segments) for any engine
 * that never overrides it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhisperSegmentBridgeTest {

    private lateinit var context: Application
    private lateinit var native: FakeWhisperNative
    private lateinit var store: WhisperModelStore
    private lateinit var engine: WhisperCppEngine
    private lateinit var scope: CoroutineScope

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    private val wavPath = Paths.get("/tmp/does-not-matter.wav")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelRoot.deleteRecursively()
        native = FakeWhisperNative()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = WhisperModelStore(
            context = context,
            workSource = object : ModelDownloadWorkSource {
                override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
            },
            unmeteredProbe = { true },
            scope = scope,
        )
        engine = WhisperCppEngine(store, native)
        installVerifiedBase()
    }

    @After
    fun tearDown() {
        scope.cancel()
        modelRoot.deleteRecursively()
    }

    private fun writeSparse(file: File, length: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    private fun installVerifiedBase() {
        writeSparse(File(modelRoot, "base/ggml-base.bin"), WhisperModelConfig.EXPECTED_BYTES)
        File(modelRoot, "base/ggml-base.bin.sha256").writeText(WhisperModelConfig.EXPECTED_SHA256)
    }

    private val basePathString: String
        get() = WhisperModelConfig.baseModelPath(context.filesDir.toPath()).absolutePathString()

    @Test
    fun `transcribeWithSegments returns the segments and their joined text`() = runTest {
        native.resultSegments = arrayOf(
            WhisperSegment(text = "hello ", t0Ms = 0L, t1Ms = 500L, noSpeechProb = 0.01f),
            WhisperSegment(text = "world", t0Ms = 500L, t1Ms = 900L, noSpeechProb = 0.02f),
        )

        val outcome = engine.transcribeWithSegments(wavPath)

        assertTrue("expected success, was $outcome", outcome.isSuccess)
        val result = outcome.getOrThrow()
        assertEquals("hello world", result.text)
        assertEquals(2, result.segments.size)
        assertEquals(500L, result.segments[0].t1Ms)
        assertEquals(0.02f, result.segments[1].noSpeechProb)
    }

    @Test
    fun `transcribeWithSegments loads the same resolved model path as transcribe`() = runTest {
        native.resultSegments = arrayOf(WhisperSegment("x", 0L, 0L, 0f))

        engine.transcribeWithSegments(wavPath)

        assertEquals(listOf(basePathString), native.initPaths)
    }

    @Test
    fun `no model resolves to ModelLoadFailed without touching native`() = runTest {
        modelRoot.deleteRecursively()

        val outcome = engine.transcribeWithSegments(wavPath)

        assertTrue(outcome.exceptionOrNull() is WhisperError.ModelLoadFailed)
        assertTrue(native.initPaths.isEmpty())
    }

    @Test
    fun `native init failure is ModelLoadFailed`() = runTest {
        native.failInit = true

        val outcome = engine.transcribeWithSegments(wavPath)

        assertTrue(outcome.exceptionOrNull() is WhisperError.ModelLoadFailed)
    }

    @Test
    fun `a null segments array from native is InferenceFailed`() = runTest {
        native.resultSegments = null
        val nullReturningNative = object : WhisperNative by native {
            override fun transcribeSegments(handle: Long, wavPath: String): Array<WhisperSegment>? = null
        }
        val engineWithNullNative = WhisperCppEngine(store, nullReturningNative)

        val outcome = engineWithNullNative.transcribeWithSegments(wavPath)

        assertTrue(
            "expected InferenceFailed, was $outcome",
            outcome.exceptionOrNull() is WhisperError.InferenceFailed,
        )
    }

    @Test
    fun `an empty segments array (no speech) is a success with empty text`() = runTest {
        val emptyNative = object : WhisperNative by native {
            override fun transcribeSegments(handle: Long, wavPath: String): Array<WhisperSegment> = emptyArray()
        }
        val engineWithEmptyNative = WhisperCppEngine(store, emptyNative)

        val outcome = engineWithEmptyNative.transcribeWithSegments(wavPath)

        assertTrue("expected success, was $outcome", outcome.isSuccess)
        val result = outcome.getOrThrow()
        assertEquals("", result.text)
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `same model resolution across both entry points loads only once`() = runTest {
        native.resultSegments = arrayOf(WhisperSegment("x", 0L, 0L, 0f))

        engine.transcribe(wavPath)
        engine.transcribeWithSegments(wavPath)

        assertEquals(
            "ensureLoaded must recognize the already-loaded path across both entry points",
            listOf(basePathString),
            native.initPaths,
        )
    }

    // whisper-jni.cpp resolves this exact constructor by raw descriptor
    // `(Ljava/lang/String;JJF)V` (nativeTranscribeSegments), and
    // proguard-rules.pro keeps it by the same signature — neither gets a
    // compile error if WhisperSegment's parameters change, so this pins
    // the shape where a refactor WILL fail a JVM test instead of
    // returning null jclass/jmethodID on-device.
    @Test
    fun `WhisperSegment keeps the constructor shape the JNI descriptor resolves`() {
        assertNotNull(
            WhisperSegment::class.java.getConstructor(
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ),
        )
    }

    // ---- WhisperEngine's default transcribeWithSegments (any non-overriding engine) ----

    private class MinimalEngine(private val delegate: FakeWhisperEngine) : WhisperEngine {
        override suspend fun transcribe(wavPath: java.nio.file.Path) = delegate.transcribe(wavPath)
        override fun unloadModel() = delegate.unloadModel()
    }

    @Test
    fun `the default transcribeWithSegments delegates to transcribe with no segments`() = runTest {
        val fake = FakeWhisperEngine(resultText = "delegated text")
        val minimal = MinimalEngine(fake)

        val outcome = minimal.transcribeWithSegments(wavPath)

        assertTrue(outcome.isSuccess)
        val result = outcome.getOrThrow()
        assertEquals("delegated text", result.text)
        assertTrue("the default must not fabricate segments", result.segments.isEmpty())
        assertEquals(1, fake.transcribeCalls.size)
    }
}
