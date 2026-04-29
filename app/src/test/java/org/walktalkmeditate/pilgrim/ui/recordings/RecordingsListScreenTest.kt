// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Robolectric Compose tests for [RecordingsListScreen].
 *
 * Coverage focus is the iOS-faithful surface that the screen owns
 * directly: empty state, populated sections, search no-match, section
 * header tap, delete-all button + confirmation dialog, and search-bar
 * wiring.
 *
 * Swipe-action tests (StartToEnd → retranscribe, EndToStart → delete
 * dialog) are deliberately deferred to manual device QA in Task 14.
 * Material 3's [androidx.compose.material3.SwipeToDismissBox] in BOM
 * 2026.03.01 routes its gesture handling through `AnchoredDraggable`,
 * which expects real drag deltas + a frame-clock-driven settle that
 * Robolectric's headless Compose harness does not synthesize reliably
 * in unit tests. Per Stage 5-G's "test the wiring, not the gesture
 * infrastructure" lesson, we cover the wiring via [RecordingsListViewModelTest]
 * (Task 11) and exercise the actual swipe surface on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingsListScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var playback: FakeVoicePlaybackController
    private lateinit var scheduler: FakeTranscriptionScheduler
    private lateinit var fileSystem: VoiceRecordingFileSystem
    private lateinit var waveformCache: WaveformCache
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        playback = FakeVoicePlaybackController()
        scheduler = FakeTranscriptionScheduler()
        fileSystem = VoiceRecordingFileSystem(context)
        waveformCache = WaveformCache()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = RecordingsListViewModel(
        walkRepository = repository,
        playbackController = playback,
        transcriptionScheduler = scheduler,
        fileSystem = fileSystem,
        waveformCache = waveformCache,
        context = context,
    )

    private fun seedWalkWithRecording(
        startTimestamp: Long,
        recordingStart: Long,
        transcription: String? = null,
    ): Pair<Walk, VoiceRecording> = runBlocking {
        val walk = repository.startWalk(startTimestamp = startTimestamp)
        repository.finishWalk(walk, endTimestamp = startTimestamp + 60_000L)
        val recording = VoiceRecording(
            walkId = walk.id,
            startTimestamp = recordingStart,
            endTimestamp = recordingStart + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = "recordings/${walk.uuid}/rec-$recordingStart.wav",
            transcription = transcription,
        )
        val id = repository.recordVoice(recording)
        walk to recording.copy(id = id)
    }

    @Test
    fun `empty state shows the empty title and no section headers`() {
        val vm = newViewModel()

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = {},
                    viewModel = vm,
                )
            }
        }

        // The VM's `fileSnapshotFlow` hops to real Dispatchers.IO, which
        // Compose's idling-resource tracking does not observe. Wait
        // explicitly for the Loaded state to appear (Loading paints an
        // empty Box; the empty-state copy only renders once the combine
        // settles after the IO hop returns).
        composeRule.waitUntil(timeoutMillis = 2_000L) {
            composeRule.onAllNodesWithText("Your voice recordings will appear here")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Your voice recordings will appear here").assertIsDisplayed()
        composeRule.onNodeWithText("Delete All Recording Files").assertDoesNotExist()
    }

    @Test
    fun `populated state renders section header and Recording row`() {
        // Use a deterministic walk timestamp so we can match the date
        // string ("MMMM d, h:mm a", Locale.US, system zone).
        val (_, _) = seedWalkWithRecording(
            startTimestamp = 1_000L,
            recordingStart = 10_000L,
            transcription = "the path winds through cedar",
        )
        val vm = newViewModel()

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = {},
                    viewModel = vm,
                )
            }
        }

        // The recording is the first (and only) one in its section.
        composeRule.onNodeWithText("Recording 1").assertIsDisplayed()
        // Section subtitle: durationMillis = 5_000 → "0:05".
        composeRule.onNodeWithText("0:05 of recordings").assertIsDisplayed()
        // Delete-all button is visible because hasAnyRecordings == true.
        composeRule.onNodeWithText("Delete All Recording Files").assertIsDisplayed()
        // Empty state text is hidden.
        composeRule.onNodeWithText("Your voice recordings will appear here").assertDoesNotExist()
    }

    @Test
    fun `search with no match shows the no-match label`() {
        seedWalkWithRecording(
            startTimestamp = 1_000L,
            recordingStart = 10_000L,
            transcription = "the path winds through cedar",
        )
        val vm = newViewModel()

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.onNodeWithText("Search transcriptions").performTextInput("nothingmatches")
        composeRule.onNodeWithText("No recordings match").assertIsDisplayed()
        composeRule.onNodeWithText("Recording 1").assertDoesNotExist()
    }

    @Test
    fun `tap section header fires onWalkClick with the walk id`() {
        val (walk, _) = seedWalkWithRecording(
            startTimestamp = 1_000L,
            recordingStart = 10_000L,
        )
        val vm = newViewModel()
        var clicked: Long? = null

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = { clicked = it },
                    viewModel = vm,
                )
            }
        }

        // The section header subtitle is unique to the header row;
        // matching it isolates the click target from the recording row
        // chrome below.
        composeRule.onNodeWithText("0:05 of recordings").performClick()
        composeRule.runOnIdle {
            assertEquals(walk.id, clicked)
        }
    }

    @Test
    fun `delete-all button opens dialog and confirm calls onDeleteAllFiles`() {
        seedWalkWithRecording(
            startTimestamp = 1_000L,
            recordingStart = 10_000L,
        )
        val vm = newViewModel()

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = {},
                    viewModel = vm,
                )
            }
        }

        composeRule.onNodeWithText("Delete All Recording Files").performClick()
        // Dialog title visible.
        composeRule.onNodeWithText(
            "Delete all recording files? Transcriptions will be kept.",
        ).assertIsDisplayed()
        // Confirm.
        composeRule.onNodeWithText("Delete All").performClick()

        // The VM's onDeleteAllFiles fires viewModelScope.launch on the
        // test dispatcher; allWalks() is awaited and walked. Verify by
        // observing that the editing/dialog state has reset (dialog
        // dismissed) — direct VM-method spying isn't available without
        // a Fake VM, so we rely on side effect: dialog is gone.
        composeRule.onNodeWithText(
            "Delete all recording files? Transcriptions will be kept.",
        ).assertDoesNotExist()
    }

    @Test
    fun `typing in search bar wires through to the view model`() {
        seedWalkWithRecording(
            startTimestamp = 1_000L,
            recordingStart = 10_000L,
            transcription = "Hello there",
        )
        val vm = newViewModel()

        composeRule.setContent {
            PilgrimTheme {
                RecordingsListScreen(
                    onBack = {},
                    onWalkClick = {},
                    viewModel = vm,
                )
            }
        }

        // VM seeds with empty query.
        assertEquals("", (vm.state.value as RecordingsListUiState.Loaded).searchQuery)

        composeRule.onNodeWithText("Search transcriptions").performTextInput("hello")
        composeRule.runOnIdle {
            assertEquals("hello", (vm.state.value as RecordingsListUiState.Loaded).searchQuery)
        }

        // Clear-X icon resets to empty.
        composeRule.onNodeWithContentDescription("Clear search").performClick()
        composeRule.runOnIdle {
            assertEquals("", (vm.state.value as RecordingsListUiState.Loaded).searchQuery)
        }
        // The matched recording is now back in the list.
        composeRule.onNodeWithText("Recording 1").assertIsDisplayed()
    }

    // --- assertions guarded for fast failure --------------------------

    @Suppress("unused")
    private fun assertEmptyEditingState(state: RecordingsListUiState) {
        val loaded = state as RecordingsListUiState.Loaded
        assertNull(loaded.editingRecordingId)
        assertTrue(loaded.searchQuery.isEmpty())
    }
}
