// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
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
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
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
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    while ((vm.state.value as? RecordingsListUiState.Loaded)
                            ?.fileExistenceById?.get(rec.id) != false
                    ) {
                        Thread.sleep(25L)
                    }
                }
            }
            assertEquals(false, (vm.state.value as RecordingsListUiState.Loaded)
                .fileExistenceById[rec.id])
        }

    private suspend fun awaitFileGone(relativePath: String) {
        // Bridge real-time IO to virtual-time runTest by polling on a
        // real dispatcher. `Default` rather than `IO` because we're
        // not blocking — Thread.sleep'ing 25ms a few times is cheap.
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                while (fileSystem.fileExists(relativePath)) {
                    Thread.sleep(25L)
                }
            }
        }
    }

    @Test
    fun `onRetranscribe clears transcription and reschedules`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val rec = insertRecording(
            walkId = walk.id,
            startAt = 10_000L,
            transcription = "old transcription",
            wpm = 120.0,
        )

        val vm = newViewModel()
        vm.onRetranscribe(rec.id)

        val updated = repository.getVoiceRecording(rec.id)
        assertNull(updated?.transcription)
        assertNull(updated?.wordsPerMinute)
        assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
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
        // runTest by polling vm.state.value on a real dispatcher.
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                while ((vm.state.value as? RecordingsListUiState.Loaded)
                        ?.editingRecordingId != expected
                ) {
                    Thread.sleep(25L)
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
