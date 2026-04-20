// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the "first successful download auto-selects" flow
 * implemented by [VoiceGuideDownloadObserver].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideDownloadObserverTest {

    private lateinit var context: Application
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var selection: VoiceGuideSelectionRepository
    private lateinit var fileStore: VoiceGuideFileStore
    private lateinit var scheduler: TrackingScheduler
    private lateinit var manifestService: VoiceGuideManifestService
    private lateinit var catalog: VoiceGuideCatalogRepository
    private lateinit var catalogScope: CoroutineScope
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
        scheduler = TrackingScheduler()
        catalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selection = VoiceGuideSelectionRepository(dataStore, selectionScope)
    }

    @After fun tearDown() {
        catalogScope.cancel()
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

    private fun pack(id: String, prompts: List<VoiceGuidePrompt>) = VoiceGuidePack(
        id = id, version = "1", name = id, tagline = "", description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(0, 0, 0, 0, 0),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = prompts,
    )

    private fun seedManifestAndBuild(packs: List<VoiceGuidePack>) {
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
        kotlinx.coroutines.runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
        catalog = VoiceGuideCatalogRepository(
            manifestService = manifestService,
            fileStore = fileStore,
            selection = selection,
            scheduler = scheduler,
            scope = catalogScope,
        )
    }

    @Test fun `first pack to become Downloaded auto-selects when no selection`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val pk = pack("p", listOf(pr))
        seedManifestAndBuild(listOf(pk))

        val observer = VoiceGuideDownloadObserver(catalog, selection, catalogScope)
        observer.start()

        // Wait for the observer (the catalog's first collector) to receive
        // the initial NotDownloaded emission before we transition the
        // filesystem. If we write the file before that, the observer's
        // FIRST observation would be `Downloaded` with `prev == null`, and
        // its transition guard (`prev !is Downloaded`) would short-circuit.
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                catalog.packStates.first { list ->
                    list.firstOrNull() is VoiceGuidePackState.NotDownloaded
                }
            }
        }

        // Now the transition: write the file, then broadcast an invalidation
        // so the catalog's combine re-reads the filesystem.
        fileStore.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        fileStore.deletePack(pack("nobody", emptyList()))

        val selected = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedPackId.first { it == "p" }
            }
        }
        assertEquals("p", selected)
    }

    @Test fun `does not override existing selection`() = runTest {
        val prA = prompt("a/a.aac", 5)
        val prB = prompt("b/b.aac", 5)
        val pkA = pack("a", listOf(prA))
        val pkB = pack("b", listOf(prB))
        seedManifestAndBuild(listOf(pkA, pkB))
        selection.select("a")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedPackId.first { it == "a" }
            }
        }

        val observer = VoiceGuideDownloadObserver(catalog, selection, catalogScope)
        observer.start()

        // Download pack B — should NOT override the existing "a" selection.
        fileStore.fileForPrompt(prB.r2Key).writeBytes(ByteArray(5))
        fileStore.deletePack(pack("nobody", emptyList()))

        // Give the observer a window to react, then assert selection
        // didn't flip. Use a small bounded real-time wait.
        withContext(realTimeDispatcher()) {
            kotlinx.coroutines.delay(500)
        }
        assertEquals("a", selection.selectedPackId.value)
    }

    @Test fun `already-Downloaded pack does not re-trigger selectIfUnset`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val pk = pack("p", listOf(pr))
        fileStore.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5)) // already downloaded
        seedManifestAndBuild(listOf(pk))

        val observer = VoiceGuideDownloadObserver(catalog, selection, catalogScope)
        observer.start()

        // The FIRST catalog emission with this pack is Downloaded; because
        // `previous` starts empty (prev == null), the observer SHOULD NOT
        // auto-select — becameDownloaded requires prev != null per the
        // impl (initial observation is not a transition).
        withContext(realTimeDispatcher()) {
            kotlinx.coroutines.delay(500)
        }
        assertNull(selection.selectedPackId.value)
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "voice-guide-observer-test"
        const val AWAIT_TIMEOUT_MS = 3_000L
    }

    private class TrackingScheduler : VoiceGuideDownloadScheduler {
        override fun enqueue(packId: String) = Unit
        override fun retry(packId: String) = Unit
        override fun cancel(packId: String) = Unit
        override fun observe(packId: String): Flow<DownloadProgress?> =
            kotlinx.coroutines.flow.flowOf(null)
    }
}
