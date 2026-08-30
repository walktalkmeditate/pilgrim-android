// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingsListViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var playback: FakeVoicePlaybackController
    private lateinit var scheduler: FakeTranscriptionScheduler
    private lateinit var fileSystem: VoiceRecordingFileSystem
    private lateinit var waveformCache: WaveformCache
    private lateinit var modelStoreScope: CoroutineScope
    private lateinit var modelStore: WhisperModelStore
    private lateinit var threadsStore: org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
    private lateinit var threadsPreferences: org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository
    private lateinit var threadsAnalyzer: org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer
    private val downloadWork = MutableStateFlow<ModelDownloadWork?>(null)
    private val dispatcher = UnconfinedTestDispatcher()

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Real store over the Robolectric filesDir (TranscriptionRunnerTest
        // pattern): tests that need the retranscribe gate open install a
        // sparse legacy tiny via installLegacyTiny().
        modelRoot.deleteRecursively()
        modelStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        modelStore = WhisperModelStore(
            context = context,
            workSource = object : ModelDownloadWorkSource {
                override fun observe(): Flow<ModelDownloadWork?> = downloadWork
            },
            unmeteredProbe = { true },
            scope = modelStoreScope,
        )
        // Same dispatcher-piping pattern as WalkSummaryViewModelTest:
        // pipe Room's executors through the test dispatcher so in-flight
        // queries are drained by runTest before db.close() — otherwise a
        // stranded Room coroutine throws "database is not open" in a
        // later test with a misleading stack pointer.
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

        // U7 edit-path wiring (BEH-59 carry): real store + real WordNet/
        // VADER analysis, matching TranscriptContextAnalyzerTest's
        // established pattern — only the language client is faked.
        val threadsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        threadsStore = org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore(context, threadsJson)
        threadsPreferences = org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository()
        threadsAnalyzer = org.walktalkmeditate.pilgrim.core.threads.realTranscriptContextAnalyzerForTests(
            context,
            threadsPreferences,
        )
    }

    @After
    fun tearDown() {
        modelStoreScope.cancel()
        modelRoot.deleteRecursively()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = RecordingsListViewModel(
        walkRepository = repository,
        playbackController = playback,
        transcriptionScheduler = scheduler,
        fileSystem = fileSystem,
        waveformCache = waveformCache,
        whisperModelStore = modelStore,
        threadsAnalyzer = threadsAnalyzer,
        context = context,
    )

    private fun installLegacyTiny() {
        val tiny = File(modelRoot, "ggml-tiny.en.bin")
        tiny.parentFile?.mkdirs()
        RandomAccessFile(tiny, "rw").use {
            it.setLength(WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES)
        }
    }

    /**
     * The gate flows store probe (real IO) -> VM stateIn, so bridge to
     * wall-clock on the dedicated real-clock dispatcher (house pattern
     * — never Thread.sleep on the shared Default pool).
     */
    private suspend fun awaitRetranscribeEnabled(vm: RecordingsListViewModel) {
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.retranscribeEnabled.first { it }
            }
        }
    }

    /**
     * The null-write rides viewModelScope through the repository's IO
     * hop, so the assert must poll wall-clock like the gate helper
     * above — asserting synchronously reads the pre-write row.
     */
    private suspend fun awaitTranscriptionCleared(recordingId: Long): VoiceRecording? =
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                var row = repository.getVoiceRecording(recordingId)
                while (row?.transcription != null) {
                    delay(25L)
                    row = repository.getVoiceRecording(recordingId)
                }
                row
            }
        }

    private suspend fun loaded(vm: RecordingsListViewModel): RecordingsListUiState.Loaded {
        var captured: RecordingsListUiState = RecordingsListUiState.Loading
        vm.state.test(timeout = 10.seconds) {
            captured = awaitItem()
            while (captured is RecordingsListUiState.Loading) captured = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return captured as RecordingsListUiState.Loaded
    }

    @Test
    fun `empty list yields Loaded with no sections and hasAnyRecordings false`() = runTest(dispatcher) {
        val vm = newViewModel()

        val state = loaded(vm)

        assertTrue(state.visibleSections.isEmpty())
        assertFalse(state.hasAnyRecordings)
        assertEquals("", state.searchQuery)
        assertNull(state.playingRecordingId)
        assertEquals(0f, state.playbackPositionFraction, 0f)
        assertEquals(1.0f, state.playbackSpeed, 0f)
        assertNull(state.editingRecordingId)
    }

    @Test
    fun `two walks each with one recording groups into two sections newest-first`() = runTest(dispatcher) {
        val older = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(older, endTimestamp = 60_000L)
        insertRecording(walkId = older.id, startAt = 10_000L)

        val newer = repository.startWalk(startTimestamp = 100_000L)
        repository.finishWalk(newer, endTimestamp = 160_000L)
        insertRecording(walkId = newer.id, startAt = 110_000L)

        val vm = newViewModel()
        val state = loaded(vm)

        assertEquals(2, state.visibleSections.size)
        assertEquals(newer.id, state.visibleSections[0].walk.id)
        assertEquals(older.id, state.visibleSections[1].walk.id)
        assertTrue(state.hasAnyRecordings)
    }

    @Test
    fun `recordings within a section are sorted by startTimestamp ascending`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 600_000L)
        val late = insertRecording(walkId = walk.id, startAt = 400_000L)
        val early = insertRecording(walkId = walk.id, startAt = 100_000L)

        val state = loaded(newViewModel())

        val ids = state.visibleSections.single().recordings.map { it.id }
        assertEquals(listOf(early.id, late.id), ids)
    }

    @Test
    fun `search filter is case-insensitive against transcription`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 600_000L)
        val match = insertRecording(walkId = walk.id, startAt = 100_000L, transcription = "Hello there")
        insertRecording(walkId = walk.id, startAt = 200_000L, transcription = "Goodbye now")

        val vm = newViewModel()
        vm.onSearchChange("HELLO")

        val state = loaded(vm)
        val recs = state.visibleSections.single().recordings
        assertEquals(listOf(match.id), recs.map { it.id })
        assertTrue(state.hasAnyRecordings)
    }

    @Test
    fun `search with no match yields empty visibleSections but hasAnyRecordings stays true`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 600_000L)
        insertRecording(walkId = walk.id, startAt = 100_000L, transcription = "Hello there")

        val vm = newViewModel()
        vm.onSearchChange("xyz")

        val state = loaded(vm)
        assertTrue(state.visibleSections.isEmpty())
        assertTrue(state.hasAnyRecordings)
    }

    @Test
    fun `onPlay looks up entity and delegates to controller`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L)

        val vm = newViewModel()
        vm.onPlay(rec.id)

        // viewModelScope.launch on Main(=UnconfinedTestDispatcher) runs eagerly
        assertEquals(listOf(rec.id), playback.playCalls)
    }

    @Test
    fun `onPause delegates to controller`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L)

        val vm = newViewModel()
        vm.onPlay(rec.id)
        vm.onPause()

        assertEquals(1, playback.pauseCalls.get())
    }

    @Test
    fun `onSpeedCycle cycles 1_0 to 1_5 to 2_0 to 1_0`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onSpeedCycle()
        assertEquals(1.5f, playback.playbackSpeed.value, 0f)

        vm.onSpeedCycle()
        assertEquals(2.0f, playback.playbackSpeed.value, 0f)

        vm.onSpeedCycle()
        assertEquals(1.0f, playback.playbackSpeed.value, 0f)
    }

    @Test
    fun `onSeek on the currently playing recording forwards the fraction`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L)

        val vm = newViewModel()
        vm.onPlay(rec.id)

        vm.onSeek(recordingId = rec.id, fraction = 0.5f)
        assertEquals(listOf(0.5f), playback.seekCalls)
        // Did not re-play (already playing this id).
        assertEquals(listOf(rec.id), playback.playCalls)
    }

    @Test
    fun `onSeek on inactive row starts playback then seeks`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recA = insertRecording(walkId = walk.id, startAt = 10_000L)
        val recB = insertRecording(walkId = walk.id, startAt = 20_000L)

        val vm = newViewModel()
        vm.onPlay(recA.id)

        // Tap the OTHER row's waveform — should start B and then seek.
        vm.onSeek(recordingId = recB.id, fraction = 0.4f)
        // Drain the 100ms hop so the seek lands.
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(recA.id, recB.id), playback.playCalls)
        assertEquals(listOf(0.4f), playback.seekCalls)
    }

    @Test
    fun `onTranscriptionEdit updates row and exits edit mode`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old text")

        val vm = newViewModel()
        vm.onStartEditing(rec.id)
        vm.onTranscriptionEdit(rec.id, "new text")

        val updated = repository.getVoiceRecording(rec.id)
        assertEquals("new text", updated?.transcription)

        val state = loaded(vm)
        assertNull(state.editingRecordingId)
    }

    // --- U7 edit-path wiring (BEH-59 carry) -------------------------------

    @Test
    fun `onTranscriptionEdit eagerly analyzes when the threads toggle is on`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old text")

        val vm = newViewModel()
        vm.onTranscriptionEdit(rec.id, "The quiet mountain trail held a long stillness today")

        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (!threadsStore.hasContext(rec.uuid)) delay(25L)
            }
        }
    }

    @Test
    fun `onTranscriptionEdit removes any stale context when the threads toggle is off`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old text")
        threadsAnalyzer.analyzeAndStore(rec.uuid, "prior text analyzed while the toggle was on")
        assertTrue(threadsStore.hasContext(rec.uuid))
        threadsPreferences.setThreadsAfterWalks(false)

        val vm = newViewModel()
        vm.onTranscriptionEdit(rec.id, "an edit made while the feature is off")

        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (threadsStore.hasContext(rec.uuid)) delay(25L)
            }
        }
    }

    @Test
    fun `onRetranscribe removes the stale context after the null write`() = runTest(dispatcher) {
        installLegacyTiny()
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old transcription")
        threadsAnalyzer.analyzeAndStore(rec.uuid, "old transcription")
        assertTrue(threadsStore.hasContext(rec.uuid))

        val vm = newViewModel()
        awaitRetranscribeEnabled(vm)
        vm.onRetranscribe(rec.id)

        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (threadsStore.hasContext(rec.uuid)) delay(25L)
            }
        }
    }

    /**
     * A real analyzer whose very first internal read throws — stands in
     * for any failure inside [org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer.analyzeOrForget]
     * (the class is final, so the throw is injected via its preferences
     * collaborator). [entered] counts entries so tests can prove the
     * analyzer was genuinely reached AND threw during the test body.
     */
    private class ThrowingThreadsPreferences :
        org.walktalkmeditate.pilgrim.core.threads.ThreadsPreferencesRepository
        by org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository() {
        val entered = java.util.concurrent.atomic.AtomicInteger(0)
        override val threadsAfterWalks: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() {
                entered.incrementAndGet()
                throw IllegalStateException("threads preferences read failed")
            }
    }

    private suspend fun awaitAnalyzerEntered(prefs: ThrowingThreadsPreferences) {
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (prefs.entered.get() == 0) delay(25L)
            }
        }
    }

    @Test
    fun `onTranscriptionEdit survives an analyzer failure and still commits the edit`() =
        runTest(dispatcher) {
            val throwingPrefs = ThrowingThreadsPreferences()
            threadsAnalyzer = org.walktalkmeditate.pilgrim.core.threads.realTranscriptContextAnalyzerForTests(
                context,
                throwingPrefs,
            )
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old text")

            val vm = newViewModel()
            vm.onStartEditing(rec.id)
            vm.onTranscriptionEdit(rec.id, "new text after analyzer failure")

            awaitAnalyzerEntered(throwingPrefs)
            assertEquals(
                "new text after analyzer failure",
                repository.getVoiceRecording(rec.id)?.transcription,
            )
            awaitEditingId(vm, expected = null)
        }

    @Test
    fun `onRetranscribe survives an analyzer failure with the null write and schedule intact`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val throwingPrefs = ThrowingThreadsPreferences()
            threadsAnalyzer = org.walktalkmeditate.pilgrim.core.threads.realTranscriptContextAnalyzerForTests(
                context,
                throwingPrefs,
            )
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecording(walkId = walk.id, startAt = 10_000L, transcription = "old transcription")

            val vm = newViewModel()
            awaitRetranscribeEnabled(vm)
            vm.onRetranscribe(rec.id)

            awaitAnalyzerEntered(throwingPrefs)
            assertNull(awaitTranscriptionCleared(rec.id)?.transcription)
            assertEquals(setOf(rec.id), vm.manualTranscribing.value)
            // Don't lean on the schedule preceding the analyzer call
            // inside the press coroutine; wait for it explicitly.
            awaitScheduledCount(1)
            assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
        }

    @Test
    fun `onDeleteFile removes file but keeps the row`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecordingWithFile(walkId = walk.id, walkUuid = walk.uuid, startAt = 10_000L)

        assertTrue("precondition: file exists", fileSystem.fileExists(rec.fileRelativePath))

        val vm = newViewModel()
        vm.onDeleteFile(rec.id)

        // The delete hop runs on real Dispatchers.IO, off the test
        // dispatcher's virtual time, so poll briefly. 1s is plenty —
        // it's a single in-process file delete.
        awaitFileGone(rec.fileRelativePath)
        assertNotNull("row should still be present", repository.getVoiceRecording(rec.id))
    }

    @Test
    fun `onDeleteFile of currently-playing recording stops playback first`() =
        runTest(dispatcher) {
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecordingWithFile(
                walkId = walk.id,
                walkUuid = walk.uuid,
                startAt = 10_000L,
            )

            val vm = newViewModel()
            vm.onPlay(rec.id)
            assertEquals(0, playback.stopCalls.get())

            vm.onDeleteFile(rec.id)
            awaitFileGone(rec.fileRelativePath)

            assertEquals(
                "stop() must be called before deleting the active recording",
                1,
                playback.stopCalls.get(),
            )
        }

    @Test
    fun `onDeleteFile of non-playing recording does not stop playback`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recA = insertRecordingWithFile(walkId = walk.id, walkUuid = walk.uuid, startAt = 10_000L)
        val recB = insertRecordingWithFile(walkId = walk.id, walkUuid = walk.uuid, startAt = 20_000L)

        val vm = newViewModel()
        vm.onPlay(recA.id)
        vm.onDeleteFile(recB.id)
        awaitFileGone(recB.fileRelativePath)

        assertEquals(0, playback.stopCalls.get())
    }

    @Test
    fun `onDeleteAllFiles removes every file`() = runTest(dispatcher) {
        val walkA = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walkA, endTimestamp = 60_000L)
        val recA = insertRecordingWithFile(walkId = walkA.id, walkUuid = walkA.uuid, startAt = 10_000L)

        val walkB = repository.startWalk(startTimestamp = 100_000L)
        repository.finishWalk(walkB, endTimestamp = 160_000L)
        val recB = insertRecordingWithFile(walkId = walkB.id, walkUuid = walkB.uuid, startAt = 110_000L)

        val vm = newViewModel()
        vm.onDeleteAllFiles()

        awaitFileGone(recA.fileRelativePath)
        awaitFileGone(recB.fileRelativePath)
        assertNotNull(repository.getVoiceRecording(recA.id))
        assertNotNull(repository.getVoiceRecording(recB.id))
    }

    @Test
    fun `onDeleteAllFiles stops playback before deleting`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecordingWithFile(walkId = walk.id, walkUuid = walk.uuid, startAt = 10_000L)

        val vm = newViewModel()
        vm.onPlay(rec.id)
        assertEquals(0, playback.stopCalls.get())

        vm.onDeleteAllFiles()
        awaitFileGone(rec.fileRelativePath)

        assertEquals(
            "stop() must be called once before walking the delete loop",
            1,
            playback.stopCalls.get(),
        )
    }

    @Test
    fun `onDeleteFile flips fileExistenceById to false for that recording`() =
        runTest(dispatcher) {
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecordingWithFile(
                walkId = walk.id,
                walkUuid = walk.uuid,
                startAt = 10_000L,
            )

            val vm = newViewModel()
            assertEquals(true, loaded(vm).fileExistenceById[rec.id])

            vm.onDeleteFile(rec.id)
            awaitFileGone(rec.fileRelativePath)

            // After the delete + fileSystemVersion bump, the combine
            // re-runs and the row's existence flips to false.
            // Poll briefly because the state propagation from the
            // version bump runs through the test dispatcher but the
            // file delete itself ran on real Dispatchers.IO.
            withContext(TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) {
                    while ((vm.state.value as? RecordingsListUiState.Loaded)
                            ?.fileExistenceById?.get(rec.id) != false
                    ) {
                        delay(25L)
                    }
                }
            }
            assertEquals(false, (vm.state.value as RecordingsListUiState.Loaded)
                .fileExistenceById[rec.id])
        }

    private suspend fun awaitFileGone(relativePath: String) {
        // Bridge real-time IO to virtual-time runTest by polling on
        // the dedicated real-clock dispatcher (house pattern — never
        // Thread.sleep on the shared Default pool).
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (fileSystem.fileExists(relativePath)) {
                    delay(25L)
                }
            }
        }
    }

    @Test
    fun `onRetranscribe clears transcription and reschedules once the model is ready`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecording(
                walkId = walk.id,
                startAt = 10_000L,
                transcription = "old transcription",
                wpm = 120.0,
            )

            val vm = newViewModel()
            awaitRetranscribeEnabled(vm)
            vm.onRetranscribe(rec.id)

            val updated = awaitTranscriptionCleared(rec.id)
            assertNull(updated?.transcription)
            assertNull(updated?.wordsPerMinute)
            // The schedule lands after the null write the poll above
            // observed — wait for it rather than racing it.
            awaitScheduledCount(1)
            assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
        }

    // U10 gating through the U11 window: the transitional tiny keeps
    // the gate open while base delivery work is in flight — usability
    // is split from the delivery display, so Downloading never closes
    // an upgrader's retranscribe for the whole download window.
    @Test
    fun `retranscribe gate stays enabled during the base download while the tiny serves`() =
        runTest(dispatcher) {
            installLegacyTiny()
            downloadWork.value = ModelDownloadWork.Downloading(
                bytesDownloaded = 5L,
                totalBytes = WhisperModelConfig.EXPECTED_BYTES,
            )

            val vm = newViewModel()

            awaitRetranscribeEnabled(vm)
        }

    @Test
    fun `onRetranscribe is a no-op while the model is absent`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(
            walkId = walk.id,
            startAt = 10_000L,
            transcription = "precious transcription",
            wpm = 120.0,
        )

        val vm = newViewModel()
        assertFalse("gate must start closed with no model on disk", vm.retranscribeEnabled.value)
        vm.onRetranscribe(rec.id)

        // The destructive null write must never land pre-Ready (U11
        // spec section 5 — silent data loss during the window).
        val updated = repository.getVoiceRecording(rec.id)
        assertEquals("precious transcription", updated?.transcription)
        assertTrue(scheduler.scheduledWalkIds.isEmpty())
    }

    @Test
    fun `retranscribeEnabled opens once the legacy tiny probes ready`() = runTest(dispatcher) {
        installLegacyTiny()

        val vm = newViewModel()

        awaitRetranscribeEnabled(vm)
        assertTrue(vm.retranscribeEnabled.value)
    }

    // --- Post-swipe "Transcribing…" feedback (v1.3.0 QA finding) --------

    @Test
    fun `onRetranscribe marks the row transcribing until the new transcript lands`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val rec = insertRecording(
                walkId = walk.id,
                startAt = 10_000L,
                transcription = "old transcription",
            )

            val vm = newViewModel()
            awaitRetranscribeEnabled(vm)
            vm.onRetranscribe(rec.id)

            awaitTranscriptionCleared(rec.id)
            awaitManualTranscribing(vm, setOf(rec.id))

            // A second swipe while in-flight re-schedules but the
            // marker never duplicates. Await its (idempotent) null
            // write fully before committing the fresh transcript —
            // the schedule call is the press coroutine's last step.
            vm.onRetranscribe(rec.id)
            awaitScheduledCount(2)
            assertEquals(setOf(rec.id), vm.manualTranscribing.value)
            assertEquals(listOf(walk.id, walk.id), scheduler.scheduledWalkIds)

            // Simulate the worker committing the fresh transcript.
            val row = repository.getVoiceRecording(rec.id)!!
            repository.updateVoiceRecording(row.copy(transcription = "fresh transcript"))
            awaitManualTranscribing(vm, emptySet())
        }

    @Test
    fun `onRetranscribe pre-Ready leaves the transcribing set empty`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(
            walkId = walk.id,
            startAt = 10_000L,
            transcription = "precious transcription",
        )

        val vm = newViewModel()
        assertFalse("gate must start closed with no model on disk", vm.retranscribeEnabled.value)
        vm.onRetranscribe(rec.id)

        assertTrue(vm.manualTranscribing.value.isEmpty())
    }

    /** Same real-clock polling bridge as [awaitRetranscribeEnabled]. */
    private suspend fun awaitManualTranscribing(vm: RecordingsListViewModel, expected: Set<Long>) {
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (vm.manualTranscribing.value != expected) {
                    delay(25L)
                }
            }
        }
    }

    private suspend fun awaitScheduledCount(expected: Int) {
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (scheduler.scheduledWalkIds.size < expected) {
                    delay(25L)
                }
            }
        }
    }

    @Test
    fun `onStartEditing and onStopEditing toggle editingRecordingId`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(walkId = walk.id, startAt = 10_000L)

        val vm = newViewModel()
        vm.onStartEditing(rec.id)

        // Poll: the combine block hops through real Dispatchers.IO for
        // the file-snapshot side-channel, so virtual-time runTest does
        // not observe the new emission synchronously after a state
        // mutation. Same pattern as the existence-flip test below.
        awaitEditingId(vm, expected = rec.id)
        assertEquals(rec.id, (vm.state.value as RecordingsListUiState.Loaded).editingRecordingId)

        vm.onStopEditing()
        awaitEditingId(vm, expected = null)
        assertNull((vm.state.value as RecordingsListUiState.Loaded).editingRecordingId)
    }

    private suspend fun awaitEditingId(vm: RecordingsListViewModel, expected: Long?) {
        // Bridge the real-time IO hop in fileSnapshotFlow to virtual-time
        // runTest by polling vm.state.value on the dedicated real-clock
        // dispatcher.
        withContext(TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while ((vm.state.value as? RecordingsListUiState.Loaded)
                        ?.editingRecordingId != expected
                ) {
                    delay(25L)
                }
            }
        }
    }

    // --- helpers -----------------------------------------------------

    private fun insertRecording(
        walkId: Long,
        startAt: Long,
        transcription: String? = null,
        wpm: Double? = null,
    ): VoiceRecording = runBlocking {
        val walk = repository.getWalk(walkId)!!
        val rec = VoiceRecording(
            walkId = walkId,
            startTimestamp = startAt,
            endTimestamp = startAt + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = "recordings/${walk.uuid}/rec-$startAt.wav",
            transcription = transcription,
            wordsPerMinute = wpm,
        )
        val id = repository.recordVoice(rec)
        rec.copy(id = id)
    }

    private fun insertRecordingWithFile(
        walkId: Long,
        walkUuid: String,
        startAt: Long,
    ): VoiceRecording = runBlocking {
        val rec = VoiceRecording(
            walkId = walkId,
            startTimestamp = startAt,
            endTimestamp = startAt + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = "recordings/$walkUuid/rec-$startAt.wav",
        )
        val id = repository.recordVoice(rec)
        // Materialize a real on-disk file under the file system so the
        // delete path has something to remove. Mirror what the recorder
        // emits — a few bytes of WAV-ish data is enough to verify
        // delete behaviour.
        val abs = fileSystem.absolutePath(rec.fileRelativePath)
        abs.parentFile?.mkdirs()
        abs.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))
        rec.copy(id = id)
    }
}
