// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifest
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeCatalogRepositoryTest {

    private lateinit var context: Application
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var selection: SoundscapeSelectionRepository
    private lateinit var fileStore: SoundscapeFileStore
    private lateinit var scheduler: FakeScheduler
    private lateinit var manifestService: AudioManifestService
    private lateinit var scope: CoroutineScope
    private lateinit var manifestScope: CoroutineScope
    private lateinit var selectionScope: CoroutineScope
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json

    private val soundscapeRoot: File
        get() = File(context.filesDir, "audio/soundscape")
    private val manifestCache: File
        get() = File(context.filesDir, "audio_manifest.json")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        soundscapeRoot.deleteRecursively()
        manifestCache.delete()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )
        server = MockWebServer().also { it.start() }
        httpClient = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = SoundscapeFileStore(context)
        scheduler = FakeScheduler()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        selection = SoundscapeSelectionRepository(dataStore, selectionScope)
    }

    @After fun tearDown() {
        scope.cancel()
        manifestScope.cancel()
        selectionScope.cancel()
        server.shutdown()
        soundscapeRoot.deleteRecursively()
        manifestCache.delete()
        context.preferencesDataStoreFile(DATASTORE_NAME).delete()
    }

    private fun asset(id: String, type: String = AudioAssetType.SOUNDSCAPE, size: Long = 10L) =
        AudioAsset(
            id = id, type = type, name = id, displayName = id,
            durationSec = 120.0, r2Key = "$type/$id.aac", fileSizeBytes = size,
        )

    private fun seedManifest(assets: List<AudioAsset>) {
        manifestCache.writeText(
            json.encodeToString(AudioManifest(version = "v1", assets = assets)),
        )
        manifestService = AudioManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun buildRepo() = SoundscapeCatalogRepository(
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
            withTimeout(AWAIT_TIMEOUT_MS) { repo.soundscapeStates.first() }
        }
        assertEquals(emptyList<SoundscapeState>(), observed)
    }

    @Test fun `bell-typed assets are filtered out`() = runTest {
        seedManifest(listOf(
            asset("b1", AudioAssetType.BELL),
            asset("s1", AudioAssetType.SOUNDSCAPE),
            asset("b2", AudioAssetType.BELL),
        ))
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.soundscapeStates.first { it.isNotEmpty() }
            }
        }
        assertEquals(1, observed.size)
        assertEquals("s1", observed.first().asset.id)
    }

    @Test fun `asset without file is NotDownloaded`() = runTest {
        seedManifest(listOf(asset("s1")))
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.soundscapeStates.first { it.isNotEmpty() }
            }
        }
        assertTrue(observed.first() is SoundscapeState.NotDownloaded)
    }

    @Test fun `asset with file present is Downloaded`() = runTest {
        val a = asset("s1")
        fileStore.fileFor(a).writeBytes(ByteArray(a.fileSizeBytes.toInt()))
        seedManifest(listOf(a))
        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.soundscapeStates.first { it.isNotEmpty() }
            }
        }
        assertTrue(observed.first() is SoundscapeState.Downloaded)
    }

    @Test fun `selection flag reflected in isSelected`() = runTest {
        val a = asset("s1")
        fileStore.fileFor(a).writeBytes(ByteArray(a.fileSizeBytes.toInt()))
        seedManifest(listOf(a))
        selection.select("s1")

        val repo = buildRepo()
        val observed = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                repo.soundscapeStates.first { it.isNotEmpty() && it.first().isSelected }
            }
        }
        assertTrue(observed.first().isSelected)
    }

    @Test fun `scheduler Running progress surfaces as Downloading`() = runTest {
        seedManifest(listOf(asset("s1")))
        val repo = buildRepo()

        repo.soundscapeStates.test {
            var current = awaitItem()
            while (current.firstOrNull() !is SoundscapeState.NotDownloaded) {
                current = awaitItem()
            }

            scheduler.emit("s1", DownloadProgress(DownloadProgress.State.Running, 0, 0))
            var running = awaitItem()
            while (running.firstOrNull() !is SoundscapeState.Downloading) {
                running = awaitItem()
            }
            assertTrue(running.first() is SoundscapeState.Downloading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `scheduler Succeeded after files-on-disk surfaces as Downloaded`() = runTest {
        val a = asset("s1")
        seedManifest(listOf(a))
        val repo = buildRepo()

        repo.soundscapeStates.test {
            var current = awaitItem()
            while (current.firstOrNull() !is SoundscapeState.NotDownloaded) {
                current = awaitItem()
            }
            // Worker: write file + transition to SUCCEEDED.
            fileStore.fileFor(a).writeBytes(ByteArray(a.fileSizeBytes.toInt()))
            scheduler.emit("s1", DownloadProgress(DownloadProgress.State.Succeeded, 0, 0))

            var terminal = awaitItem()
            while (terminal.firstOrNull() !is SoundscapeState.Downloaded) {
                terminal = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `delete clears selection if deleted asset was selected`() = runTest {
        val a = asset("s1")
        fileStore.fileFor(a).writeBytes(ByteArray(a.fileSizeBytes.toInt()))
        seedManifest(listOf(a))
        selection.select("s1")
        withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedSoundscapeId.first { it == "s1" }
            }
        }

        val repo = buildRepo()
        repo.delete(a)

        val cleared = withContext(realTimeDispatcher()) {
            withTimeout(AWAIT_TIMEOUT_MS) {
                selection.selectedSoundscapeId.first { it == null }
            }
        }
        assertEquals(null, cleared)
        assertTrue(scheduler.cancelled.contains("s1"))
    }

    private fun realTimeDispatcher() = Dispatchers.Default.limitedParallelism(1)

    private companion object {
        const val DATASTORE_NAME = "soundscape-catalog-test"
        const val AWAIT_TIMEOUT_MS = 3_000L
    }

    private class FakeScheduler : SoundscapeDownloadScheduler {
        private val flows = mutableMapOf<String, MutableStateFlow<DownloadProgress?>>()
        val enqueued = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun enqueue(assetId: String) { enqueued += assetId }
        override fun retry(assetId: String) { enqueued += assetId }
        override fun cancel(assetId: String) { cancelled += assetId }

        override fun observe(assetId: String): Flow<DownloadProgress?> =
            flows.getOrPut(assetId) { MutableStateFlow(null) }

        fun emit(assetId: String, progress: DownloadProgress?) {
            flows.getOrPut(assetId) { MutableStateFlow(null) }.value = progress
        }
    }
}
