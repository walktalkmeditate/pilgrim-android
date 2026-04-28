// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Robolectric Compose tests for [RecordingRow]. Per Stage 10-D Task 12 plan,
 * we deliberately do NOT assert on the waveform itself — Task 8's
 * [WaveformBarTest] covers the bar; here we just verify the row composes
 * without crash and that the three visual states (file-available,
 * file-unavailable, edit-mode) surface the right text and callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingRowTest {

    @get:Rule val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var fileSystem: VoiceRecordingFileSystem
    private lateinit var waveformCache: WaveformCache
    private val createdFiles = mutableListOf<File>()

    @org.junit.Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileSystem = VoiceRecordingFileSystem(context)
        waveformCache = WaveformCache()
    }

    @After
    fun tearDown() {
        createdFiles.forEach { runCatching { it.delete() } }
        createdFiles.clear()
    }

    /** Build a [VoiceRecording] whose `fileRelativePath` either does or does not exist on disk. */
    private fun makeRecording(
        id: Long = 42L,
        durationMillis: Long = 42_000L,
        transcription: String? = null,
        isEnhanced: Boolean = false,
        withFileOnDisk: Boolean = true,
    ): VoiceRecording {
        val relative = "recordings/test/rec-$id.wav"
        if (withFileOnDisk) {
            val f = fileSystem.absolutePath(relative)
            f.parentFile?.mkdirs()
            // Minimum bytes so fileSizeBytes returns a non-zero value
            // (~300 KB to land at 0.3 MB in the meta string).
            f.writeBytes(ByteArray(300_000))
            createdFiles += f
        }
        return VoiceRecording(
            id = id,
            walkId = 1L,
            uuid = "uuid-$id",
            startTimestamp = 0L,
            endTimestamp = durationMillis,
            durationMillis = durationMillis,
            fileRelativePath = relative,
            transcription = transcription,
            isEnhanced = isEnhanced,
        )
    }

    @Test
    fun `file-available state shows recording index and meta`() {
        val rec = makeRecording(durationMillis = 42_000L)
        composeRule.setContent {
            PilgrimTheme {
                RecordingRow(
                    recording = rec,
                    indexInSection = 3,
                    fileSystem = fileSystem,
                    waveformCache = waveformCache,
                    fileAvailable = true,
                    isPlayingThisRow = false,
                    playbackPositionFraction = 0f,
                    playbackSpeed = 1.0f,
                    isEditing = false,
                    onPlay = {},
                    onPause = {},
                    onSeek = { _, _ -> },
                    onSpeedCycle = {},
                    onStartEditing = {},
                    onStopEditing = {},
                    onTranscriptionEdit = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("Recording 3").assertIsDisplayed()
        // Meta string format: "M:SS · 0.3 MB" — durationMillis = 42_000 → "0:42".
        composeRule.onNodeWithText("0:42 · 0.3 MB").assertIsDisplayed()
        // Speed pill at 1.0x renders "1x" (truncated form, not "1.0x").
        composeRule.onNodeWithText("1x").assertIsDisplayed()
        // Play button content description from string res.
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test
    fun `file-unavailable state shows unavailable label and no play button`() {
        val rec = makeRecording(withFileOnDisk = false)
        composeRule.setContent {
            PilgrimTheme {
                RecordingRow(
                    recording = rec,
                    indexInSection = 1,
                    fileSystem = fileSystem,
                    waveformCache = waveformCache,
                    fileAvailable = false,
                    isPlayingThisRow = false,
                    playbackPositionFraction = 0f,
                    playbackSpeed = 1.0f,
                    isEditing = false,
                    onPlay = {},
                    onPause = {},
                    onSeek = { _, _ -> },
                    onSpeedCycle = {},
                    onStartEditing = {},
                    onStopEditing = {},
                    onTranscriptionEdit = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("File unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Recording 1").assertIsDisplayed()
        // Play button absent.
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun `speed pill formats 1_5x correctly`() {
        val rec = makeRecording()
        composeRule.setContent {
            PilgrimTheme {
                RecordingRow(
                    recording = rec,
                    indexInSection = 1,
                    fileSystem = fileSystem,
                    waveformCache = waveformCache,
                    fileAvailable = true,
                    isPlayingThisRow = false,
                    playbackPositionFraction = 0f,
                    playbackSpeed = 1.5f,
                    isEditing = false,
                    onPlay = {},
                    onPause = {},
                    onSeek = { _, _ -> },
                    onSpeedCycle = {},
                    onStartEditing = {},
                    onStopEditing = {},
                    onTranscriptionEdit = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("1.5x").assertIsDisplayed()
    }

    @Test
    fun `tap on transcription block fires onStartEditing with recording id`() {
        val rec = makeRecording(transcription = "the path winds through cedar")
        var startedFor: Long? = null
        composeRule.setContent {
            PilgrimTheme {
                RecordingRow(
                    recording = rec,
                    indexInSection = 1,
                    fileSystem = fileSystem,
                    waveformCache = waveformCache,
                    fileAvailable = true,
                    isPlayingThisRow = false,
                    playbackPositionFraction = 0f,
                    playbackSpeed = 1.0f,
                    isEditing = false,
                    onPlay = {},
                    onPause = {},
                    onSeek = { _, _ -> },
                    onSpeedCycle = {},
                    onStartEditing = { startedFor = it },
                    onStopEditing = {},
                    onTranscriptionEdit = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithText("the path winds through cedar").performClick()
        composeRule.runOnIdle {
            assertEquals(rec.id, startedFor)
        }
    }

    @Test
    fun `edit mode renders TextField and Done commits with current text`() {
        val rec = makeRecording(transcription = "old text")
        var committedId: Long? = null
        var committedText: String? = null
        composeRule.setContent {
            PilgrimTheme {
                RecordingRow(
                    recording = rec,
                    indexInSection = 1,
                    fileSystem = fileSystem,
                    waveformCache = waveformCache,
                    fileAvailable = true,
                    isPlayingThisRow = false,
                    playbackPositionFraction = 0f,
                    playbackSpeed = 1.0f,
                    isEditing = true,
                    onPlay = {},
                    onPause = {},
                    onSeek = { _, _ -> },
                    onSpeedCycle = {},
                    onStartEditing = {},
                    onStopEditing = {},
                    onTranscriptionEdit = { id, text ->
                        committedId = id
                        committedText = text
                    },
                )
            }
        }
        // The TextField pre-populates with the existing transcription.
        composeRule.onNodeWithText("old text").assertIsDisplayed()
        // Replace text and tap Done.
        composeRule.onNodeWithText("old text").performTextClearance()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Transcription editor").performTextInput("new text")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle {
            assertEquals(rec.id, committedId)
            assertNotNull(committedText)
            assertEquals("new text", committedText)
        }
    }
}
