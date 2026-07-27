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
 * Pins [WhisperCppEngine]'s U10 load-state machine — resolution through
 * [WhisperModelStore.readyModelPath], load keyed on the resolved path,
 * reload on identity change — against a real Robolectric filesDir and
 * [FakeWhisperNative] at the JNI seam. Model files are sparse
 * ([RandomAccessFile.setLength]) so the production size constants are
 * exercised without allocating hundreds of MB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhisperCppEngineTest {

    private lateinit var context: Application
    private lateinit var native: FakeWhisperNative
    private lateinit var store: WhisperModelStore
    private lateinit var engine: WhisperCppEngine
    private lateinit var scope: CoroutineScope

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    private val wavPath = Paths.get("/tmp/does-not-matter.wav")

    @Before fun setUp() {
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
    }

    @After fun tearDown() {
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

    private fun installLegacyTiny() {
        writeSparse(File(modelRoot, "ggml-tiny.en.bin"), WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES)
    }

    private val basePathString: String
        get() = WhisperModelConfig.baseModelPath(context.filesDir.toPath()).absolutePathString()

    private val tinyPathString: String
        get() = WhisperModelConfig.legacyTinyPath(context.filesDir.toPath()).absolutePathString()

    @Test fun `base Ready loads the base path`() = runTest {
        installVerifiedBase()

        val outcome = engine.transcribe(wavPath)

        assertTrue("expected success, was $outcome", outcome.isSuccess)
        assertEquals(listOf(basePathString), native.initPaths)
    }

    @Test fun `tiny-only transitionally loads the tiny path`() = runTest {
        installLegacyTiny()

        val outcome = engine.transcribe(wavPath)

        assertTrue("expected success, was $outcome", outcome.isSuccess)
        assertEquals(listOf(tinyPathString), native.initPaths)
    }

    @Test fun `no model resolves to ModelLoadFailed without touching native`() = runTest {
        val outcome = engine.transcribe(wavPath)

        assertTrue(
            "expected ModelLoadFailed, was $outcome",
            outcome.exceptionOrNull() is WhisperError.ModelLoadFailed,
        )
        assertTrue("native must never be reached", native.initPaths.isEmpty())
    }

    @Test fun `same resolution across transcribes loads once`() = runTest {
        installVerifiedBase()

        engine.transcribe(wavPath)
        engine.transcribe(wavPath)

        assertEquals(listOf(basePathString), native.initPaths)
        assertTrue("nothing to free while the identity is stable", native.freedHandles.isEmpty())
    }

    // The U10 switch: tiny is loaded, then base verifies and the store
    // deletes the tiny — the next batch must free the stale context and
    // reload on the new identity, not keep transcribing on tiny forever.
    @Test fun `resolution change frees the stale context and reloads`() = runTest {
        installLegacyTiny()
        engine.transcribe(wavPath)

        installVerifiedBase()
        store.onBaseVerified()
        val outcome = engine.transcribe(wavPath)

        assertTrue("expected success, was $outcome", outcome.isSuccess)
        assertEquals(listOf(tinyPathString, basePathString), native.initPaths)
        assertEquals("the tiny context must be freed exactly once", 1, native.freedHandles.size)
        assertEquals(
            "the freed handle is the tiny's, not the base's",
            native.transcribedHandles.first(),
            native.freedHandles.first(),
        )
    }

    @Test fun `unloadModel frees and the next transcribe reloads`() = runTest {
        installVerifiedBase()
        engine.transcribe(wavPath)

        engine.unloadModel()
        engine.transcribe(wavPath)

        assertEquals(listOf(basePathString, basePathString), native.initPaths)
        assertEquals(1, native.freedHandles.size)
    }

    // A PRESENT model that fails native init is a genuine load failure —
    // the existing ModelLoadFailed escalation contract, unchanged by the
    // self-heal path (U10 spec L1).
    @Test fun `native init failure on a present model is ModelLoadFailed`() = runTest {
        installVerifiedBase()
        native.failInit = true

        val outcome = engine.transcribe(wavPath)

        assertTrue(
            "expected ModelLoadFailed, was $outcome",
            outcome.exceptionOrNull() is WhisperError.ModelLoadFailed,
        )
    }
}
