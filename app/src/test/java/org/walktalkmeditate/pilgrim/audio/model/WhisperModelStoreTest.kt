// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher

/**
 * Exercises [WhisperModelStore]'s join of filesystem probe × work
 * source × unmetered probe against a real Robolectric filesDir and
 * fakes at both injectable seams. Model-sized files are written
 * sparse ([RandomAccessFile.setLength]) so the tests pin the REAL
 * production byte-size constants without allocating 148 MB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhisperModelStoreTest {

    private lateinit var context: Application
    private lateinit var workSource: FakeModelDownloadWorkSource
    private lateinit var unmetered: FakeUnmeteredNetworkProbe
    private lateinit var scope: CoroutineScope

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelRoot.deleteRecursively()
        workSource = FakeModelDownloadWorkSource()
        unmetered = FakeUnmeteredNetworkProbe()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        modelRoot.deleteRecursively()
    }

    private fun buildStore() = WhisperModelStore(
        context = context,
        workSource = workSource,
        unmeteredProbe = unmetered,
        scope = scope,
    )

    private fun writeSparse(file: File, length: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    private fun baseModelFile() = File(modelRoot, "base/ggml-base.bin")
    private fun baseMarkerFile() = File(modelRoot, "base/ggml-base.bin.sha256")
    private fun legacyTinyFile() = File(modelRoot, "ggml-tiny.en.bin")

    private fun installVerifiedBase() {
        writeSparse(baseModelFile(), WhisperModelConfig.EXPECTED_BYTES)
        baseMarkerFile().writeText(WhisperModelConfig.EXPECTED_SHA256)
    }

    private fun installLegacyTiny() {
        writeSparse(legacyTinyFile(), WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES)
    }

    private suspend fun awaitState(
        store: WhisperModelStore,
        predicate: (WhisperModelState) -> Boolean,
    ): WhisperModelState = withContext(TestRealTimeDispatcher.instance) {
        withTimeout(AWAIT_TIMEOUT_MS) { store.state.first(predicate) }
    }

    // Spelled out rather than read off constants computed elsewhere:
    // if the shipped variant, the published upstream digest, or the CDN
    // literal drifts, this should fail rather than follow it. Mirrors
    // iOS testShippedVariant_isBase and the key-literal pin convention.
    @Test fun `config pins the base variant, published size, and sha`() {
        assertEquals("base", WhisperModelConfig.VARIANT)
        assertEquals(147_951_465L, WhisperModelConfig.EXPECTED_BYTES)
        assertEquals(
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
            WhisperModelConfig.EXPECTED_SHA256,
        )
        assertEquals("https://cdn.pilgrimapp.org/models/ggml-base.bin", WhisperModelConfig.CDN_URL)
        assertEquals(77_704_715L, WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES)
    }

    /**
     * The transitional resolver must find the exact file the v1.2.0
     * asset installer wrote (`whisper-model/ggml-tiny.en.bin`, flat, no
     * variant directory) — U10 deleted the installer, so the literal is
     * pinned here.
     */
    @Test fun `legacy tiny path matches the installer's flat layout`() {
        val filesDir = context.filesDir.toPath()
        assertEquals(
            filesDir.resolve("whisper-model").resolve("ggml-tiny.en.bin"),
            WhisperModelConfig.legacyTinyPath(filesDir),
        )
    }

    @Test fun `verified base file probes Ready Base`() = runTest {
        installVerifiedBase()
        val store = buildStore()
        val state = awaitState(store) { it is WhisperModelState.Ready }
        assertEquals(WhisperModelState.Ready(WhisperModelVariant.Base), state)
    }

    // iOS parity: a model saved for another variant must never satisfy
    // the shipped variant (testResolvedModelPath_differentVariant_isNil,
    // testResolvedModelPath_legacyPathWithoutVariantKey_isNil). Android's
    // transitional divergence (spec D3) reads LegacyTiny, never Base.
    @Test fun `legacy tiny alone at the flat path probes Ready LegacyTiny, never Base`() = runTest {
        installLegacyTiny()
        val store = buildStore()
        val state = awaitState(store) { it is WhisperModelState.Ready }
        assertEquals(WhisperModelState.Ready(WhisperModelVariant.LegacyTiny), state)
    }

    @Test fun `neither file probes Absent`() = runTest {
        val store = buildStore()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    // Restore/D2D artifact: the marker survived but the model didn't.
    // iOS parity: testResolvedModelPath_missingFolder_isNil.
    @Test fun `sha marker without the model file probes Absent`() = runTest {
        baseMarkerFile().parentFile?.mkdirs()
        baseMarkerFile().writeText(WhisperModelConfig.EXPECTED_SHA256)
        val store = buildStore()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    @Test fun `model file with mismatched size probes Absent`() = runTest {
        writeSparse(baseModelFile(), WhisperModelConfig.EXPECTED_BYTES - 1)
        baseMarkerFile().writeText(WhisperModelConfig.EXPECTED_SHA256)
        val store = buildStore()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    @Test fun `sha marker mismatch probes Absent`() = runTest {
        writeSparse(baseModelFile(), WhisperModelConfig.EXPECTED_BYTES)
        baseMarkerFile().writeText("deadbeef")
        val store = buildStore()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    @Test fun `legacy tiny with wrong size probes Absent`() = runTest {
        writeSparse(legacyTinyFile(), WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES - 1)
        val store = buildStore()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    @Test fun `enqueued work without unmetered network is WaitingUnmetered`() = runTest {
        unmetered.available = false
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Enqueued)
        awaitState(store) { it == WhisperModelState.WaitingUnmetered }
    }

    @Test fun `enqueued work with unmetered network is Enqueued`() = runTest {
        unmetered.available = true
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Enqueued)
        awaitState(store) { it == WhisperModelState.Enqueued }
    }

    @Test fun `downloading work passes byte progress through`() = runTest {
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Downloading(1_000L, WhisperModelConfig.EXPECTED_BYTES))
        val first = awaitState(store) { it is WhisperModelState.Downloading }
        assertEquals(
            WhisperModelState.Downloading(1_000L, WhisperModelConfig.EXPECTED_BYTES),
            first,
        )

        workSource.emit(ModelDownloadWork.Downloading(2_000L, WhisperModelConfig.EXPECTED_BYTES))
        awaitState(store) {
            it is WhisperModelState.Downloading && it.bytesDownloaded == 2_000L
        }
    }

    // The download presentation wins over the transitional tiny: the
    // engine keeps serving tiny via readyModelPath, but the observable
    // state shows delivery progress (plan: "legacy tiny serves the
    // engine in every pre-READY state").
    @Test fun `downloading work with legacy tiny present still shows Downloading`() = runTest {
        installLegacyTiny()
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Downloading(5L, 10L))
        awaitState(store) { it is WhisperModelState.Downloading }
    }

    @Test fun `verified base wins over stale work emissions`() = runTest {
        installVerifiedBase()
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Downloading(5L, 10L))
        val state = awaitState(store) { it is WhisperModelState.Ready }
        assertEquals(WhisperModelState.Ready(WhisperModelVariant.Base), state)
    }

    @Test fun `verifying work is Verifying`() = runTest {
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Verifying)
        awaitState(store) { it == WhisperModelState.Verifying }
    }

    @Test fun `failed checksum work is FailedChecksum`() = runTest {
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Failed(ModelDownloadWork.Failed.Reason.Checksum))
        awaitState(store) { it == WhisperModelState.FailedChecksum }
    }

    @Test fun `failed storage work is FailedStorage`() = runTest {
        val store = buildStore()
        workSource.emit(ModelDownloadWork.Failed(ModelDownloadWork.Failed.Reason.Storage))
        awaitState(store) { it == WhisperModelState.FailedStorage }
    }

    // Terminal transitions re-read the filesystem (Stage 5-D staleness
    // lesson): Succeeded carries no payload — the probe alone decides.
    @Test fun `succeeded work with base on disk probes Ready Base`() = runTest {
        val store = buildStore()
        awaitState(store) { it == WhisperModelState.Absent }

        installVerifiedBase()
        workSource.emit(ModelDownloadWork.Succeeded)
        val state = awaitState(store) { it is WhisperModelState.Ready }
        assertEquals(WhisperModelState.Ready(WhisperModelVariant.Base), state)
    }

    @Test fun `clear-app-storage equivalence - deleting everything probes Absent with no stuck state`() = runTest {
        installVerifiedBase()
        val store = buildStore()
        awaitState(store) { it is WhisperModelState.Ready }

        modelRoot.deleteRecursively()
        store.invalidate()
        assertEquals(WhisperModelState.Absent, awaitState(store) { it == WhisperModelState.Absent })
    }

    @Test fun `readyModelPath prefers verified base over legacy tiny`() = runTest {
        installVerifiedBase()
        installLegacyTiny()
        val path = buildStore().readyModelPath()
        assertEquals(WhisperModelConfig.baseModelPath(context.filesDir.toPath()), path)
    }

    @Test fun `readyModelPath transitionally accepts the legacy tiny file`() = runTest {
        installLegacyTiny()
        val path = buildStore().readyModelPath()
        assertEquals(WhisperModelConfig.legacyTinyPath(context.filesDir.toPath()), path)
    }

    @Test fun `readyModelPath with no usable model is null`() = runTest {
        assertNull(buildStore().readyModelPath())
    }

    @Test fun `readyModelPath rejects an unverified base file`() = runTest {
        writeSparse(baseModelFile(), WhisperModelConfig.EXPECTED_BYTES)
        assertNull(buildStore().readyModelPath())
    }

    // iOS parity: purgeStaleModels reclaims the sibling variant only
    // after the replacement is proven (U10 spec L1). The delete goes
    // through WhisperModelConfig.legacyTinyPath — the same function the
    // resolver reads — pinned by asserting the flat-path file is gone.
    @Test fun `onBaseVerified deletes the legacy tiny and resolves base afterward`() = runTest {
        installVerifiedBase()
        installLegacyTiny()
        val store = buildStore()

        store.onBaseVerified()

        assertFalse("tiny must be deleted after the verified switch", legacyTinyFile().exists())
        assertEquals(
            WhisperModelConfig.baseModelPath(context.filesDir.toPath()),
            store.readyModelPath(),
        )
        val state = awaitState(store) { it is WhisperModelState.Ready }
        assertEquals(WhisperModelState.Ready(WhisperModelVariant.Base), state)
    }

    // Sequencing invariant (U10 spec L1): the verified base must exist
    // BEFORE the tiny is deleted — a misordered caller can never open a
    // no-model window. iOS: "never removes the working model before its
    // replacement is proven".
    @Test fun `onBaseVerified without a verified base never deletes the tiny`() = runTest {
        installLegacyTiny()
        val store = buildStore()

        store.onBaseVerified()

        assertTrue("tiny must survive while base is unproven", legacyTinyFile().exists())
        assertEquals(
            WhisperModelConfig.legacyTinyPath(context.filesDir.toPath()),
            store.readyModelPath(),
        )
    }

    @Test fun `connectivity probe with no active network reads false`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setActiveNetworkInfo(null)
        assertFalse(ConnectivityUnmeteredNetworkProbe(context).isUnmeteredAvailable())
    }

    @Test fun `connectivity probe reflects the NOT_METERED capability`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val probe = ConnectivityUnmeteredNetworkProbe(context)
        val capabilities = ShadowNetworkCapabilities.newInstance()

        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        assertTrue(probe.isUnmeteredAvailable())

        shadowOf(capabilities).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)
        assertFalse(probe.isUnmeteredAvailable())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
    }

    private class FakeModelDownloadWorkSource : ModelDownloadWorkSource {
        private val flow = MutableStateFlow<ModelDownloadWork?>(null)
        override fun observe(): Flow<ModelDownloadWork?> = flow
        fun emit(work: ModelDownloadWork?) {
            flow.value = work
        }
    }

    private class FakeUnmeteredNetworkProbe : UnmeteredNetworkProbe {
        var available = false
        override fun isUnmeteredAvailable(): Boolean = available
    }
}
