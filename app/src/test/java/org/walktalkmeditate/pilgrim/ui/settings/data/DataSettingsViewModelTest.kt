// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

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
        val vm = DataSettingsViewModel(
            recordingsSource = source,
            env = DataExportEnv(sourceDir = { sourceDir }, targetDir = { targetDir }),
        )
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
        val vm = DataSettingsViewModel(
            recordingsSource = FakeRecordingsCountSource(flowOf(listOf(stubRecording(1)))),
            env = DataExportEnv(sourceDir = { sourceDir }, targetDir = { targetDir }),
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
        val vm = DataSettingsViewModel(
            recordingsSource = FakeRecordingsCountSource(flowOf(emptyList())),
            env = DataExportEnv(sourceDir = { sourceDir }, targetDir = { targetDir }),
        )

        vm.exportEvents.test(timeout = 5.seconds) {
            vm.exportRecordings()
            assertEquals(RecordingsExportResult.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun stubRecording(id: Long) = VoiceRecording(
        id = id,
        walkId = 1L,
        startTimestamp = 0L,
        endTimestamp = 1_000L,
        durationMillis = 1_000L,
        fileRelativePath = "recordings/walk-1/$id.wav",
    )
}

private class FakeRecordingsCountSource(
    private val flow: Flow<List<VoiceRecording>>,
) : RecordingsCountSource {
    override fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> = flow
}
