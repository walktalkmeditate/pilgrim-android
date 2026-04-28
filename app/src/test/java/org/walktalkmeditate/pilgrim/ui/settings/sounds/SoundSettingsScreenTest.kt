// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.sounds

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
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
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Compose tests for [SoundSettingsScreen]. Constructs a real
 * [SoundSettingsViewModel] (no Hilt-in-tests harness) backed by
 * [FakeSoundsPreferencesRepository] + a real [AudioManifestService]
 * with a seeded local cache. Verifies:
 *  - All six section headers render when sounds are enabled
 *  - Walk / Meditation / Volume / Storage hidden when disabled
 *  - Tapping a bell row opens the BellPickerSheet (title visible)
 *  - Tapping the breath rhythm row opens the BreathRhythmPickerSheet
 *  - Selecting a bell in the sheet writes the VM setter
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

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
        server.enqueue(MockResponse().setResponseCode(503))
        val unique = "test_sound_settings_screen_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        selectionRepo = SoundscapeSelectionRepository(dataStore, dataStoreScope)
        fileStore = SoundscapeFileStore(context)
        manifestScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        manifestCacheFile = File(context.filesDir, "audio_manifest.json")
        seedManifestCache(
            listOf(
                bellAsset("bell.evening", "Evening bell"),
                bellAsset("bell.morning", "Morning bell"),
                soundscapeAsset("scape.rain", "Soft rain"),
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
        File(context.filesDir, "audio/soundscape").deleteRecursively()
        manifestCacheFile.delete()
        Dispatchers.resetMain()
    }

    private fun newVm(soundsEnabled: Boolean = true): SoundSettingsViewModel {
        soundsRepo = FakeSoundsPreferencesRepository(initialSoundsEnabled = soundsEnabled)
        return SoundSettingsViewModel(
            soundsPreferences = soundsRepo,
            soundscapeSelection = selectionRepo,
            manifestService = manifestService,
            fileStore = fileStore,
            downloadScheduler = NoOpScheduler,
        )
    }

    private object NoOpScheduler : org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadScheduler {
        override fun enqueue(assetId: String) = Unit
        override fun retry(assetId: String) = Unit
        override fun cancel(assetId: String) = Unit
        override fun observe(assetId: String) =
            kotlinx.coroutines.flow.flowOf<org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress?>(null)
    }

    @Test
    fun `renders all six section headers when sounds enabled`() {
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        // LazyColumn lazily composes — each section that lives below
        // the viewport must be scrolled into view before the assertion.
        // "Sounds" appears twice (centered title + master toggle label),
        // both intentional. The Walk row is in the viewport at start;
        // the rest scroll in turn. Each scrollToNode is also a smoke
        // test that the section is reachable.
        composeRule.onAllNodesWithTextFilter("Sounds").assertCountEquals(2)
        composeRule.onNodeWithText("Walk").assertExists()
        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Meditation"))
        composeRule.onNodeWithText("Meditation").assertExists()
        list.performScrollToNode(hasText("Volume"))
        composeRule.onNodeWithText("Volume").assertExists()
        list.performScrollToNode(hasText("Storage"))
        composeRule.onNodeWithText("Storage").assertExists()
    }

    @Test
    fun `walk meditation volume storage hidden when sounds disabled`() {
        val vm = newVm(soundsEnabled = false)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.onNodeWithText("Walk").assertDoesNotExist()
        composeRule.onNodeWithText("Meditation").assertDoesNotExist()
        composeRule.onNodeWithText("Volume").assertDoesNotExist()
        composeRule.onNodeWithText("Storage").assertDoesNotExist()
    }

    @Test
    fun `tapping start bell row opens bell picker sheet`() {
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        // "Start bell" appears in BOTH walk + meditation sections; pick
        // the first by performing a click on a unique label-free path:
        // both rows fire the picker when tapped, and we just need the
        // sheet's "Choose bell" title to appear.
        composeRule.onAllNodesWithTextFilter("Start bell")[0].performClick()
        composeRule.onNodeWithText("Choose bell").assertIsDisplayed()
    }

    @Test
    fun `tapping breath rhythm row opens breath rhythm picker sheet`() {
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        // Breath rhythm row sits inside the meditation card which may
        // sit below the Robolectric viewport; scroll the LazyColumn
        // first so the row is composed AND visible to clicks.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Breath rhythm"))
        composeRule.onNodeWithText("Breath rhythm").performClick()
        composeRule.onNodeWithText("Choose breath rhythm").assertIsDisplayed()
    }

    @Test
    fun `selecting a bell in the picker writes to repo`() = runBlocking {
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        // Open the WalkStart picker (first "Start bell" row).
        composeRule.onAllNodesWithTextFilter("Start bell")[0].performClick()
        composeRule.onNodeWithText("Choose bell").assertIsDisplayed()
        // Tap the "Evening bell" entry in the sheet.
        composeRule.onNodeWithText("Evening bell").performClick()
        // The sheet animates out asynchronously; wait for the VM
        // setter's StateFlow update.
        soundsRepo.walkStartBellId.first { it == "bell.evening" }
        assertEquals("bell.evening", soundsRepo.walkStartBellId.first())
    }

    @Test
    fun `dragging bell volume slider invokes setBellVolume`() {
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        // Volume sliders sit below the viewport — scroll first.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Bells"))
        composeRule.onNodeWithTag(SOUNDS_BELL_VOLUME_SLIDER_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeRule.runOnIdle {
            // Slider's setProgress writes through onValueChange which
            // calls setBellVolume on the VM; persisted value should now
            // be ~0.5 (clamped).
        }
        runBlocking {
            soundsRepo.bellVolume.first { it in 0.45f..0.55f }
        }
    }

    @Test
    fun `tapping clear all downloads invokes VM clearAllDownloads`() = runBlocking {
        val rain = soundscapeAsset("scape.rain", "Soft rain")
        val f = fileStore.fileFor(rain)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(rain.fileSizeBytes.toInt()))
        val vm = newVm(soundsEnabled = true)
        composeRule.setContent {
            PilgrimTheme {
                SoundSettingsScreen(
                    onAction = {},
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Clear all downloads"))
        composeRule.onNodeWithText("Clear all downloads").performClick()
        // After the VM's clearAll runs on Main, the file is gone.
        vm.totalDiskUsageBytes.first { it == 0L }
        assertEquals(false, f.exists())
    }

    private fun bellAsset(id: String, displayName: String) = AudioAsset(
        id = id,
        type = AudioAssetType.BELL,
        name = id,
        displayName = displayName,
        durationSec = 3.0,
        r2Key = "bell/$id.aac",
        fileSizeBytes = 8_000,
    )

    private fun soundscapeAsset(id: String, displayName: String) = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = id,
        displayName = displayName,
        durationSec = 600.0,
        r2Key = "soundscape/$id.aac",
        fileSizeBytes = 200_000,
    )

    private fun seedManifestCache(assets: List<AudioAsset>) {
        val manifest = AudioManifest(version = "v1", assets = assets)
        manifestCacheFile.writeText(json.encodeToString(manifest))
    }

    /**
     * Convenience that mirrors AndroidX `onAllNodesWithText` without
     * requiring the `androidx.compose.ui.test.onAllNodesWithText`
     * import (which collides with `onNodeWithText` ergonomics in
     * some IDE auto-imports).
     */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextFilter(
        text: String,
    ) = onAllNodes(androidx.compose.ui.test.hasText(text))
}
