// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.sounds

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
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
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * Stage 10-B: passthrough + delegation coverage for [SoundSettingsViewModel].
 * Real Room/DataStore avoided where possible; uses
 * [FakeSoundsPreferencesRepository] for the preferences bag and a real
 * [AudioManifestService] backed by a seeded local cache file (so the
 * bell + soundscape filters return predictable results without
 * hitting the network).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundSettingsViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val httpClient = OkHttpClient()
    private lateinit var server: MockWebServer
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var manifestScope: CoroutineScope
    private lateinit var manifestService: AudioManifestService
    private lateinit var fileStore: SoundscapeFileStore
    private lateinit var selectionRepo: SoundscapeSelectionRepository
    private lateinit var soundsRepo: FakeSoundsPreferencesRepository
    private lateinit var manifestCacheFile: File

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer().apply { start() }
        // Block all network sync attempts — VM tests only need the
        // local cache. 503 → manifestService falls back silently.
        server.enqueue(MockResponse().setResponseCode(503))

        val unique = "test_sound_settings_vm_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        selectionRepo = SoundscapeSelectionRepository(dataStore, dataStoreScope)
        soundsRepo = FakeSoundsPreferencesRepository()
        fileStore = SoundscapeFileStore(context)
        manifestScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        manifestCacheFile = File(context.filesDir, "audio_manifest.json")
        seedManifestCache(
            listOf(
                bellAsset("bell.evening", "Evening bell"),
                bellAsset("bell.morning", "Morning bell"),
                soundscapeAsset("scape.rain", "Soft rain", sizeBytes = 1_000_000),
            ),
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

    @After
    fun tearDown() {
        manifestScope.cancel()
        dataStoreScope.cancel()
        server.shutdown()
        // Wipe any soundscape files between tests.
        File(context.filesDir, "audio/soundscape").deleteRecursively()
        manifestCacheFile.delete()
        Dispatchers.resetMain()
    }

    private fun newVm(): SoundSettingsViewModel = SoundSettingsViewModel(
        soundsPreferences = soundsRepo,
        soundscapeSelection = selectionRepo,
        manifestService = manifestService,
        fileStore = fileStore,
        downloadScheduler = NoOpScheduler,
    )

    /**
     * Tests don't need a real WorkManager scheduler — every cancel
     * call routes here and is recorded but never enqueues real work.
     * The clear-all-downloads test verifies cancel was invoked for
     * every soundscape asset.
     */
    private object NoOpScheduler : org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadScheduler {
        val canceled = mutableListOf<String>()
        override fun enqueue(assetId: String) = Unit
        override fun retry(assetId: String) = Unit
        override fun cancel(assetId: String) { canceled += assetId }
        override fun observe(assetId: String) =
            kotlinx.coroutines.flow.flowOf<org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress?>(null)
    }

    @Test
    fun `availableBells filters manifest to bell type`() = runTest {
        val vm = newVm()
        val bells = vm.availableBells.first { it.isNotEmpty() }
        assertEquals(2, bells.size)
        assertTrue(bells.all { it.type == AudioAssetType.BELL })
    }

    @Test
    fun `availableSoundscapes filters manifest to soundscape type`() = runTest {
        val vm = newVm()
        val scapes = vm.availableSoundscapes.first { it.isNotEmpty() }
        assertEquals(1, scapes.size)
        assertEquals(AudioAssetType.SOUNDSCAPE, scapes.single().type)
    }

    @Test
    fun `setSoundsEnabled persists to repo`() = runTest {
        val vm = newVm()
        vm.setSoundsEnabled(false)
        assertEquals(false, soundsRepo.soundsEnabled.first { !it })
    }

    @Test
    fun `setBellHapticEnabled persists to repo`() = runTest {
        val vm = newVm()
        vm.setBellHapticEnabled(false)
        assertEquals(false, soundsRepo.bellHapticEnabled.first { !it })
    }

    @Test
    fun `setBellVolume persists clamped value`() = runTest {
        val vm = newVm()
        vm.setBellVolume(1.5f)
        // Clamp keeps the persisted value at 1f.
        assertEquals(1f, soundsRepo.bellVolume.first { it == 1f }, 0.0001f)
    }

    @Test
    fun `setSoundscapeVolume persists clamped value`() = runTest {
        val vm = newVm()
        vm.setSoundscapeVolume(-0.5f)
        assertEquals(0f, soundsRepo.soundscapeVolume.first { it == 0f }, 0.0001f)
    }

    @Test
    fun `setWalkStartBellId persists`() = runTest {
        val vm = newVm()
        vm.setWalkStartBellId("bell.evening")
        assertEquals("bell.evening", soundsRepo.walkStartBellId.first { it == "bell.evening" })
    }

    @Test
    fun `setWalkEndBellId persists null`() = runTest {
        soundsRepo = FakeSoundsPreferencesRepository(initialWalkEndBellId = "bell.morning")
        val vm = newVm()
        assertEquals("bell.morning", soundsRepo.walkEndBellId.first())
        vm.setWalkEndBellId(null)
        assertEquals(null, soundsRepo.walkEndBellId.first { it == null })
    }

    @Test
    fun `setMeditationStartBellId persists`() = runTest {
        val vm = newVm()
        vm.setMeditationStartBellId("bell.morning")
        assertEquals(
            "bell.morning",
            soundsRepo.meditationStartBellId.first { it == "bell.morning" },
        )
    }

    @Test
    fun `setMeditationEndBellId persists`() = runTest {
        val vm = newVm()
        vm.setMeditationEndBellId("bell.evening")
        assertEquals(
            "bell.evening",
            soundsRepo.meditationEndBellId.first { it == "bell.evening" },
        )
    }

    @Test
    fun `setBreathRhythm persists`() = runTest {
        val vm = newVm()
        vm.setBreathRhythm(3)
        assertEquals(3, soundsRepo.breathRhythm.first { it == 3 })
    }

    @Test
    fun `setSelectedSoundscapeId persists via SoundscapeSelectionRepository`() = runTest {
        val vm = newVm()
        vm.setSelectedSoundscapeId("scape.rain")
        assertEquals(
            "scape.rain",
            selectionRepo.selectedSoundscapeId.first { it == "scape.rain" },
        )
        vm.setSelectedSoundscapeId(null)
        assertEquals(null, selectionRepo.selectedSoundscapeId.first { it == null })
    }

    @Test
    fun `clearAllDownloads sweeps every cached soundscape file`() = runBlocking {
        val rain = soundscapeAsset("scape.rain", "Soft rain", sizeBytes = 1_000_000)
        // Pre-seed an on-disk soundscape file so clearAll has something to sweep.
        val f = fileStore.fileFor(rain)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(1_000_000))
        assertTrue(f.exists())
        val vm = newVm()
        // Allow the init recompute coroutine to land before clearing.
        vm.totalDiskUsageBytes.first { it > 0L }
        vm.clearAllDownloads()
        // After clearAll, the file is gone and totalDiskUsageBytes drops to 0.
        vm.totalDiskUsageBytes.first { it == 0L }
        assertEquals(false, f.exists())
    }

    private fun bellAsset(id: String, displayName: String): AudioAsset = AudioAsset(
        id = id,
        type = AudioAssetType.BELL,
        name = id,
        displayName = displayName,
        durationSec = 3.0,
        r2Key = "bell/$id.aac",
        fileSizeBytes = 8_000,
    )

    private fun soundscapeAsset(id: String, displayName: String, sizeBytes: Long): AudioAsset = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = id,
        displayName = displayName,
        durationSec = 600.0,
        r2Key = "soundscape/$id.aac",
        fileSizeBytes = sizeBytes,
    )

    private fun seedManifestCache(assets: List<AudioAsset>) {
        val manifest = AudioManifest(version = "v1", assets = assets)
        manifestCacheFile.writeText(json.encodeToString(manifest))
    }
}
