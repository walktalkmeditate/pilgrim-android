// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks

/**
 * Records 16 kHz mono 16-bit PCM to WAV files. One recording at a
 * time, serialized by an internal state flag. The PCM read loop runs
 * on a dedicated single-thread Executor; state transitions are
 * synchronized on [stateLock].
 *
 * This class does NOT persist to Room. [stop] returns a
 * [VoiceRecording] entity the caller can insert. This separation lets
 * Stage 2-C's UI discard cancelled recordings without a DB write.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioCapture: AudioCapture,
    private val audioFocus: AudioFocusCoordinator,
    private val clock: Clock,
) {
    private val stateLock = Any()
    private val session = AtomicReference<ActiveSession?>(null)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, THREAD_NAME).apply { isDaemon = true }
    }

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    fun start(walkId: Long, walkUuid: String): Result<Path> {
        // Defensive path-component check: the caller is always a trusted
        // internal surface passing Walk.uuid (random UUID) today, but
        // concatenating an unvalidated string into a file path invites
        // future bugs where a "../" sneaks in and escapes filesDir.
        require(walkUuid.matches(UUID_STRING_REGEX)) {
            "walkUuid must match UUID string format, got: $walkUuid"
        }
        if (!PermissionChecks.isMicrophoneGranted(context)) {
            return Result.failure(VoiceRecorderError.PermissionMissing)
        }
        synchronized(stateLock) {
            if (session.get() != null) {
                return Result.failure(VoiceRecorderError.ConcurrentRecording)
            }
            val recordingUuid = UUID.randomUUID().toString()
            val relativePath = "recordings/$walkUuid/$recordingUuid.wav"
            val absolute = try {
                val dir = context.filesDir.toPath().resolve("recordings/$walkUuid")
                Files.createDirectories(dir)
                dir.resolve("$recordingUuid.wav")
            } catch (e: IOException) {
                return Result.failure(VoiceRecorderError.FileSystemError(e))
            }
            val writer = WavWriter(absolute, audioCapture.sampleRateHz)
            try {
                writer.openForWriting()
            } catch (e: IOException) {
                return Result.failure(VoiceRecorderError.FileSystemError(e))
            }
            try {
                audioCapture.start()
            } catch (e: Throwable) {
                runCatching { writer.closeAndPatchHeader() }
                runCatching { Files.deleteIfExists(absolute) }
                return Result.failure(VoiceRecorderError.AudioCaptureInitFailed(e))
            }
            audioFocus.requestTransient()
            val startedAt = clock.now()
            val s = ActiveSession(
                walkId = walkId,
                relativePath = relativePath,
                writer = writer,
                startedAt = startedAt,
                stopRequested = AtomicBoolean(false),
            )
            session.set(s)
            executor.execute { runCaptureLoop(s) }
            return Result.success(absolute)
        }
    }

    fun stop(): Result<VoiceRecording> {
        val s = synchronized(stateLock) {
            session.getAndSet(null)
                ?: return Result.failure(VoiceRecorderError.NoActiveRecording)
        }
        s.stopRequested.set(true)
        // Block until the capture loop finishes — it reads one more
        // buffer at most (~100 ms), then closes the writer.
        s.doneLatch.await()
        audioFocus.abandon()
        _audioLevel.value = 0f
        val endedAt = clock.now()
        val duration = endedAt - s.startedAt
        return Result.success(
            VoiceRecording(
                walkId = s.walkId,
                startTimestamp = s.startedAt,
                endTimestamp = endedAt,
                durationMillis = duration,
                fileRelativePath = s.relativePath,
            ),
        )
    }

    private fun runCaptureLoop(s: ActiveSession) {
        try {
            val buffer = ShortArray(BUFFER_SAMPLES)
            while (!s.stopRequested.get()) {
                val n = audioCapture.read(buffer)
                // Negative = EOF or AudioRecord error code; bail. 0 is a
                // valid "no data yet, try again" per the AudioCapture
                // contract (rare in AudioRecord blocking mode but the
                // interface permits it for future stream-over-IPC impls).
                if (n < 0) break
                if (n == 0) continue
                s.writer.append(buffer, n)
                _audioLevel.value = rmsNormalized(buffer, n)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "capture loop failure", e)
        } finally {
            runCatching { audioCapture.stop() }
            runCatching { s.writer.closeAndPatchHeader() }
            _audioLevel.value = 0f
            s.doneLatch.countDown()
        }
    }

    private fun rmsNormalized(buffer: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / count)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private data class ActiveSession(
        val walkId: Long,
        val relativePath: String,
        val writer: WavWriter,
        val startedAt: Long,
        val stopRequested: AtomicBoolean,
        val doneLatch: CountDownLatch = CountDownLatch(1),
    )

    private companion object {
        const val TAG = "VoiceRecorder"
        const val THREAD_NAME = "voice-recorder"
        // 100 ms @ 16 kHz mono = 1600 samples. Balances latency against
        // syscall overhead; short enough to make stopRequested responsive.
        const val BUFFER_SAMPLES = 1_600
        // Matches the canonical 8-4-4-4-12 hex UUID form with lowercase or
        // uppercase hex. UUID.randomUUID().toString() always matches.
        val UUID_STRING_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }
}
