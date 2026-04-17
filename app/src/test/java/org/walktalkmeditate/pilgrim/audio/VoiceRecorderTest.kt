// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.Clock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecorderTest {

    private lateinit var context: Context
    private lateinit var clock: FakeClock
    private lateinit var audioCapture: FakeAudioCapture
    private lateinit var focus: AudioFocusCoordinator
    private lateinit var recorder: VoiceRecorder

    // Stable-per-test-run UUIDs — VoiceRecorder.start validates that
    // walkUuid matches UUID string format (defensive against path
    // traversal via unvalidated concat).
    private val walkUuidA: String = UUID.randomUUID().toString()
    private val walkUuidB: String = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        clock = FakeClock(initial = 1_000L)
        audioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        focus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        recorder = VoiceRecorder(context, audioCapture, focus, clock)
    }

    @After
    fun tearDown() {
        // Defensive: ensure no session survives across tests.
        runCatching { recorder.stop() }
        val recDir = context.filesDir.toPath().resolve("recordings")
        if (Files.exists(recDir)) recDir.toFile().deleteRecursively()
    }

    @Test
    fun `start then stop produces a valid VoiceRecording with file on disk`() = runTest {
        val started = recorder.start(walkId = 42L, walkUuid = walkUuidA)
        assertTrue("start should succeed", started.isSuccess)
        val path = started.getOrThrow()

        // Allow the single-thread capture loop to consume the burst and
        // hit EOF on the FakeAudioCapture's second read.
        Thread.sleep(100)
        clock.advanceTo(3_500L)
        val stopped = recorder.stop()
        assertTrue("stop should succeed: ${stopped.exceptionOrNull()}", stopped.isSuccess)

        val recording = stopped.getOrThrow()
        assertEquals(42L, recording.walkId)
        assertEquals(1_000L, recording.startTimestamp)
        assertEquals(3_500L, recording.endTimestamp)
        assertEquals(2_500L, recording.durationMillis)
        assertTrue(
            "fileRelativePath shape: ${recording.fileRelativePath}",
            recording.fileRelativePath.startsWith("recordings/$walkUuidA/") &&
                recording.fileRelativePath.endsWith(".wav"),
        )
        assertTrue("file should exist at the returned path", Files.exists(path))
        assertTrue(
            "file should contain at least the WAV header + some PCM",
            Files.size(path) > WavWriter.HEADER_BYTES,
        )
    }

    @Test
    fun `second start returns ConcurrentRecording without touching first`() = runTest {
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()

        val second = recorder.start(walkId = 1L, walkUuid = walkUuidA)

        assertTrue(second.isFailure)
        assertEquals(VoiceRecorderError.ConcurrentRecording, second.exceptionOrNull())
        // First recording still active; stop for clean teardown.
        recorder.stop().getOrThrow()
    }

    @Test
    fun `stop without active recording returns NoActiveRecording`() {
        val result = recorder.stop()
        assertTrue(result.isFailure)
        assertEquals(VoiceRecorderError.NoActiveRecording, result.exceptionOrNull())
    }

    @Test
    fun `start without RECORD_AUDIO permission returns PermissionMissing`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val result = recorder.start(walkId = 1L, walkUuid = walkUuidA)

        assertTrue(result.isFailure)
        assertEquals(VoiceRecorderError.PermissionMissing, result.exceptionOrNull())
    }

    @Test
    fun `AudioCapture start failure maps to AudioCaptureInitFailed and cleans up file`() = runTest {
        audioCapture = FakeAudioCapture(startThrowable = IllegalStateException("mic busy"))
        recorder = VoiceRecorder(context, audioCapture, focus, clock)

        val result = recorder.start(walkId = 1L, walkUuid = walkUuidA)

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected AudioCaptureInitFailed, got $err",
            err is VoiceRecorderError.AudioCaptureInitFailed,
        )
        val leftoverDir = context.filesDir.toPath().resolve("recordings/$walkUuidA")
        val leftovers = if (Files.exists(leftoverDir)) {
            Files.list(leftoverDir).use { it.toList() }
        } else {
            emptyList()
        }
        assertTrue("expected no leftover .wav files, got $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `audioLevel emits non-zero during recording and returns to zero after stop`() = runTest {
        recorder.audioLevel.test {
            assertEquals(0f, awaitItem())

            recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
            // Wait for the capture loop to emit at least one non-zero level.
            var nonZero = 0f
            while (nonZero == 0f) {
                val v = awaitItem()
                if (v > 0f) nonZero = v
            }
            assertTrue("expected non-zero audio level, got $nonZero", nonZero > 0f)

            recorder.stop().getOrThrow()
            // Drain trailing emissions until back at zero.
            var last = nonZero
            while (last != 0f) last = awaitItem()
            assertEquals(0f, last)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `directory is created on first record and reused for second`() = runTest {
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(2_000L)
        recorder.stop().getOrThrow()

        // Reset capture state for the second recording.
        audioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        recorder = VoiceRecorder(context, audioCapture, focus, clock)
        clock.advanceTo(3_000L)
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(4_000L)
        recorder.stop().getOrThrow()

        val dir = context.filesDir.toPath().resolve("recordings/$walkUuidA")
        val files = Files.list(dir).use { it.toList() }
        assertEquals(2, files.size)
    }

    @Test
    fun `stop closes WAV and data_size field matches actual PCM bytes`() = runTest {
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
        Thread.sleep(100)
        clock.advanceTo(2_000L)
        val rec = recorder.stop().getOrThrow()

        val absolute = context.filesDir.toPath().resolve(rec.fileRelativePath)
        val totalBytes = Files.size(absolute)
        // 44-byte header + at least one buffer (1600 samples * 2 bytes).
        assertTrue(
            "expected ≥ header + 1 buffer (3244 bytes), got $totalBytes",
            totalBytes >= WavWriter.HEADER_BYTES + 3_200,
        )
        val dataSize = RandomAccessFile(absolute.toFile(), "r").use { raf ->
            raf.seek(WavWriter.DATA_SIZE_OFFSET.toLong())
            val buf = ByteArray(4)
            raf.readFully(buf)
            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
        }
        assertEquals(totalBytes - WavWriter.HEADER_BYTES, dataSize.toLong())
    }

    @Test
    fun `concurrent start after clean stop succeeds on a fresh session`() = runTest {
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(2_000L)
        recorder.stop().getOrThrow()

        // Fresh capture instance — FakeAudioCapture bursts are single-use.
        audioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        recorder = VoiceRecorder(context, audioCapture, focus, clock)
        clock.advanceTo(3_000L)
        val second = recorder.start(walkId = 2L, walkUuid = walkUuidB)
        assertTrue("second start after clean stop should succeed: ${second.exceptionOrNull()}",
            second.isSuccess)
        Thread.sleep(50)
        clock.advanceTo(4_000L)
        recorder.stop().getOrThrow()
    }

    @Test
    fun `uuid in fileRelativePath is parseable as a UUID`() = runTest {
        recorder.start(walkId = 1L, walkUuid = walkUuidA).getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(2_000L)
        val rec = recorder.stop().getOrThrow()

        val fileName = rec.fileRelativePath.substringAfterLast('/').removeSuffix(".wav")
        try {
            java.util.UUID.fromString(fileName)
        } catch (e: IllegalArgumentException) {
            fail("expected UUID in file name, got '$fileName': ${e.message}")
        }
    }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) { current = millis }
}
