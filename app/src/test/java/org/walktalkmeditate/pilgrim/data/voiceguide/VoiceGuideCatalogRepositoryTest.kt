// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [VoiceGuideCatalogRepository]'s join of manifest +
 * filesystem + selection + scheduler against real DataStore +
 * FileStore + a fake [VoiceGuideDownloadScheduler] whose progress
 * flow we drive directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideCatalogRepositoryTest {

    private lateinit var context: Application
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var selection: VoiceGuideSelectionRepository
    private lateinit var fileStore: VoiceGuideFileStore
    private lateinit var scheduler: FakeDownloadScheduler
    private lateinit var manifestService: VoiceGuideManifestService
    private lateinit var scope: CoroutineScope
    private lateinit var manifestScope: CoroutineScope
    private lateinit var selectionScope: CoroutineScope
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json

    private val promptsRoot: File
        get() = File(context.filesDir, "voice_guide_prompts")
    private val manifestCache: File
        get() = File(context.filesDir, "voice_guide_manifest.json")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        promptsRoot.deleteRecursively()
        manifestCache.delete()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )
        server = MockWebServer().also { it.start() }
        httpClient = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = VoiceGuideFileStore(context)
        scheduler = FakeDownloadScheduler()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selection = VoiceGuideSelectionRepository(dataStore, selectionScope)
    }

    @After fun tearDown() {
        scope.cancel()
        manifestScope.cancel()
        selectionScope.cancel()
        server.shutdown()
        promptsRoot.deleteRecursively()
        manifestCache.delete()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    private fun prompt(r2Key: String, size: Long) = VoiceGuidePrompt(
        id = r2Key, seq = 1, durationSec = 1.0,
        fileSizeBytes = size, r2Key = r2Key,
    )

    private fun pack(id: String, prompts: List<VoiceGuidePrompt> = emptyList()) =
        VoiceGuidePack(
            id = id, version = "1", name = id, tagline = "", description = "",
            theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
            scheduling = PromptDensity(0, 0, 0, 0, 0),
            totalDurationSec = 0.0, totalSizeBytes = 0L,
            prompts = prompts,
        )

    private fun seedManifest(packs: List<VoiceGuidePack>) {
        manifestCache.writeText(
            json.encodeToString(VoiceGuideManifest(version = "v1", packs = packs)),
        )
        manifestService = VoiceGuideManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        // Wait for async init load.
        kotlinx.coroutines.runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun buildRepo() = VoiceGuideCatalogRepository(
        manifestService = manifestService,
        fileStore = fileStore,
        selection = selection,
        scheduler = scheduler,
        scope = scope,
    )

    @Test fun `empty manifest yields empty list`() = runTest {
        seedManifest(emptyList())
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) { repo.packStates.first() }
        }
        assertEquals(emptyList<VoiceGuidePackState>(), observed)
    }

    @Test fun `pack with missing files is NotDownloaded`() = runTest {
        val pk = pack("p", listOf(prompt("p/a.aac", 5)))
        seedManifest(listOf(pk))
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.packStates.first { it.isNotEmpty() }
            }
        }
        assertTrue(observed.first() is VoiceGuidePackState.NotDownloaded)
    }

    @Test fun `pack with all files present is Downloaded`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val pk = pack("p", listOf(pr))
        fileStore.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        seedManifest(listOf(pk))
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.packStates.first { it.isNotEmpty() }
            }
        }
        assertTrue(observed.first() is VoiceGuidePackState.Downloaded)
    }

    @Test fun `selection flag reflected in isSelected`() = runTest {
        val pk = pack("p", listOf(prompt("p/a.aac", 5)))
        seedManifest(listOf(pk))
        selection.select("p")

        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.packStates.first { it.isNotEmpty() && it.first().isSelected }
            }
        }
        assertTrue(observed.first().isSelected)
    }

    @Test fun `scheduler Running progress surfaces as Downloading`() = runTest {
        val pk = pack("p", listOf(prompt("p/a.aac", 5)))
        seedManifest(listOf(pk))
        val repo = buildRepo()

        // Subscribe and drain until we see NotDownloaded for "p", THEN
        // drive progress — otherwise the scheduler emission can race the
        // upstream combine's initial wiring.
        repo.packStates.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current.firstOrNull() !is VoiceGuidePackState.NotDownloaded) {
                current = awaitItem()
            }

            scheduler.emit("p", DownloadProgress(DownloadProgress.State.Running, 1, 3))

            var running = awaitItem()
            while (running.firstOrNull() !is VoiceGuidePackState.Downloading) {
                running = awaitItem()
            }
            val dl = running.first() as VoiceGuidePackState.Downloading
            assertEquals(1, dl.completed)
            assertEquals(3, dl.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Exercises `applyProgress(Succeeded)`'s filesystem re-read branch:
     * without it the catalog is stuck on `NotDownloaded` after the
     * worker writes files. Scheduler stub emits `Succeeded` AFTER the
     * prompt files are on disk — we expect `Downloaded` to surface.
     */
    @Test fun `scheduler Succeeded after files-on-disk surfaces as Downloaded`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val pk = pack("p", listOf(pr))
        seedManifest(listOf(pk))
        val repo = buildRepo()

        repo.packStates.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current.firstOrNull() !is VoiceGuidePackState.NotDownloaded) {
                current = awaitItem()
            }

            // Worker would do this: write the prompt file then
            // transition WorkInfo to SUCCEEDED.
            fileStore.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
            scheduler.emit("p", DownloadProgress(DownloadProgress.State.Succeeded, 1, 1))

            var terminal = awaitItem()
            while (terminal.firstOrNull() !is VoiceGuidePackState.Downloaded) {
                terminal = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `delete clears selection if deleted pack was selected`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val pk = pack("p", listOf(pr))
        fileStore.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        seedManifest(listOf(pk))
        selection.select("p")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedPackId.first { it == "p" }
            }
        }

        val repo = buildRepo()
        repo.delete(pk)

        val cleared = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedPackId.first { it == null }
            }
        }
        assertEquals(null, cleared)
    }

    @Test fun `delete keeps other selection intact`() = runTest {
        val prA = prompt("a/a.aac", 5)
        val prB = prompt("b/b.aac", 5)
        val pkA = pack("a", listOf(prA))
        val pkB = pack("b", listOf(prB))
        fileStore.fileForPrompt(prA.r2Key).writeBytes(ByteArray(5))
        fileStore.fileForPrompt(prB.r2Key).writeBytes(ByteArray(5))
        seedManifest(listOf(pkA, pkB))
        selection.select("b")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedPackId.first { it == "b" }
            }
        }

        val repo = buildRepo()
        repo.delete(pkA)

        assertEquals("b", selection.selectedPackId.value)
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "voice-guide-catalog-test"
        const val AWAIT_TIMEOUT_MS = 3_000L
    }

    /** Per-pack progress drivers + request capture. */
    private class FakeDownloadScheduler : VoiceGuideDownloadScheduler {
        private val flows = mutableMapOf<String, MutableStateFlow<DownloadProgress?>>()
        val enqueued = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun enqueue(packId: String) { enqueued += packId }
        override fun retry(packId: String) { enqueued += packId }
        override fun cancel(packId: String) { cancelled += packId }

        override fun observe(packId: String): Flow<DownloadProgress?> =
            flows.getOrPut(packId) { MutableStateFlow(null) }

        fun emit(packId: String, progress: DownloadProgress?) {
            flows.getOrPut(packId) { MutableStateFlow(null) }.value = progress
        }
    }
}
