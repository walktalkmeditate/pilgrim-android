// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.net.Uri
import app.cash.turbine.test
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageBuildResult

class DataSettingsViewModelTest {

    private lateinit var workspace: File
    private lateinit var sourceDir: File
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        workspace = File(System.getProperty("java.io.tmpdir"), "ds-vm-${UUID.randomUUID()}")
        sourceDir = File(workspace, "rec").also { it.mkdirs() }
        targetDir = File(workspace, "out")
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `recordingCount mirrors source size`() = runTest {
        val source = FakeRecordingsCountSource(flowOf(listOf(stubRecording(1), stubRecording(2))))
        val vm = newViewModel(recordingsSource = source)
        vm.recordingCount.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != 2) current = awaitItem()
            assertEquals(2, current)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportRecordings emits Success with file`() = runTest {
        File(sourceDir, "a.wav").writeBytes(ByteArray(64))
        val vm = newViewModel(
            recordingsSource = FakeRecordingsCountSource(flowOf(listOf(stubRecording(1)))),
        )

        vm.exportEvents.test(timeout = 5.seconds) {
            vm.exportRecordings()
            val event = awaitItem()
            assertTrue(event is RecordingsExportResult.Success)
            val file = (event as RecordingsExportResult.Success).file
            assertTrue(file.exists())
            assertTrue(file.name.startsWith("pilgrim-recordings-"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exportRecordings emits Empty when source dir empty`() = runTest {
        val vm = newViewModel(
            recordingsSource = FakeRecordingsCountSource(flowOf(emptyList())),
        )

        vm.exportEvents.test(timeout = 5.seconds) {
            vm.exportRecordings()
            assertEquals(RecordingsExportResult.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `concurrent exportRecordings calls produce only one event`() = runTest {
        File(sourceDir, "a.wav").writeBytes(ByteArray(64))
        val vm = newViewModel(
            recordingsSource = FakeRecordingsCountSource(flowOf(listOf(stubRecording(1)))),
        )

        vm.exportEvents.test(timeout = 5.seconds) {
            vm.exportRecordings()
            vm.exportRecordings() // double-tap; second call must short-circuit on the in-flight guard
            val first = awaitItem()
            assertTrue(first is RecordingsExportResult.Success)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestPilgrimExport with zero walks fails fast`() = runTest {
        val vm = newViewModel(
            walksSource = FakeWalksSource(flowOf(emptyList())),
        )
        vm.walkCount.test(timeout = 5.seconds) {
            // Drain initial 0 then ensure the flow has settled at 0.
            var current = awaitItem()
            while (current != 0) current = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.requestPilgrimExport()
        val state = vm.pilgrimExportState.value
        assertTrue("expected Failed but was $state", state is PilgrimExportState.Failed)
    }

    @Test
    fun `requestPilgrimExport with finished walks surfaces Confirming snapshot`() = runTest {
        val walks = listOf(
            stubWalk(id = 1, startTimestamp = 1_000L, endTimestamp = 2_000L),
            stubWalk(id = 2, startTimestamp = 5_000L, endTimestamp = 6_000L),
            stubWalk(id = 3, startTimestamp = 9_000L, endTimestamp = null), // active, excluded
        )
        val vm = newViewModel(
            walksSource = FakeWalksSource(flowOf(walks)),
            walkPhotoDao = FakeWalkPhotoDao(observeAllCountFlow = flowOf(7)),
        )
        vm.walkCount.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != 2) current = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.pinnedPhotoCount.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != 7) current = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.requestPilgrimExport()
        val confirming = vm.pilgrimExportState.value as? PilgrimExportState.Confirming
            ?: error("expected Confirming")
        assertEquals(2, confirming.walkCount)
        assertEquals(7, confirming.pinnedPhotoCount)
        // 7 photos × 80,000 bytes
        assertEquals(560_000L, confirming.estimatedPhotoBytes)
    }

    @Test
    fun `cancelPilgrimExport returns to Idle`() = runTest {
        val vm = newViewModel(
            walksSource = FakeWalksSource(flowOf(listOf(stubWalk(1, 1_000L, 2_000L)))),
        )
        vm.walkCount.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current != 1) current = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.requestPilgrimExport()
        assertTrue(vm.pilgrimExportState.value is PilgrimExportState.Confirming)
        vm.cancelPilgrimExport()
        assertEquals(PilgrimExportState.Idle, vm.pilgrimExportState.value)
    }

    private fun newViewModel(
        recordingsSource: RecordingsCountSource = FakeRecordingsCountSource(flowOf(emptyList())),
        walksSource: WalksSource = FakeWalksSource(flowOf(emptyList())),
        walkPhotoDao: WalkPhotoDao = FakeWalkPhotoDao(observeAllCountFlow = flowOf(0)),
        gateway: PilgrimPackageGateway = FakePilgrimPackageGateway(),
    ): DataSettingsViewModel = DataSettingsViewModel(
        recordingsSource = recordingsSource,
        walksSource = walksSource,
        walkPhotoDao = walkPhotoDao,
        pilgrimGateway = gateway,
        env = DataExportEnv(sourceDir = { sourceDir }, targetDir = { targetDir }),
    )

    private fun stubRecording(id: Long) = VoiceRecording(
        id = id,
        walkId = 1L,
        startTimestamp = 0L,
        endTimestamp = 1_000L,
        durationMillis = 1_000L,
        fileRelativePath = "recordings/walk-1/$id.wav",
    )

    private fun stubWalk(id: Long, startTimestamp: Long, endTimestamp: Long?) = Walk(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
    )
}

private class FakeRecordingsCountSource(
    private val flow: Flow<List<VoiceRecording>>,
) : RecordingsCountSource {
    override fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> = flow
}

private class FakeWalksSource(
    private val flow: Flow<List<Walk>>,
) : WalksSource {
    override fun observeAllWalks(): Flow<List<Walk>> = flow
}

private class FakePilgrimPackageGateway(
    private val buildResult: () -> PilgrimPackageBuildResult = {
        error("build not stubbed for this test")
    },
    private val importResult: () -> Int = { error("import not stubbed for this test") },
) : PilgrimPackageGateway {
    override suspend fun build(includePhotos: Boolean): PilgrimPackageBuildResult = buildResult()
    override suspend fun import(uri: Uri): Int = importResult()
}

/**
 * Minimal [WalkPhotoDao] stub for the VM tests. Only the
 * [observeAllCount] flow is exercised; every other method throws
 * `NotImplementedError` to fail fast if a future test path
 * accidentally relies on it.
 */
private class FakeWalkPhotoDao(
    private val observeAllCountFlow: Flow<Int>,
) : WalkPhotoDao {
    override suspend fun insert(photo: WalkPhoto): Long = TODO("not used")
    override suspend fun insertAll(photos: List<WalkPhoto>): List<Long> = TODO("not used")
    override suspend fun delete(photo: WalkPhoto) = TODO("not used")
    override suspend fun deleteById(id: Long): Int = TODO("not used")
    override suspend fun getById(id: Long): WalkPhoto? = TODO("not used")
    override suspend fun getForWalk(walkId: Long): List<WalkPhoto> = TODO("not used")
    override fun observeForWalk(walkId: Long): Flow<List<WalkPhoto>> = TODO("not used")
    override suspend fun countForWalk(walkId: Long): Int = TODO("not used")
    override fun observeAllCount(): Flow<Int> = observeAllCountFlow
    override suspend fun countByPhotoUri(photoUri: String): Int = TODO("not used")
    override suspend fun updateAnalysis(
        id: Long,
        label: String?,
        confidence: Double?,
        analyzedAt: Long,
    ) = TODO("not used")
    override suspend fun getPendingAnalysisForWalk(walkId: Long): List<WalkPhoto> = TODO("not used")
}
