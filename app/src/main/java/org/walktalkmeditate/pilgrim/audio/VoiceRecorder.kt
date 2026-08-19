// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _isRecording = MutableStateFlow(false)

    /**
     * Recorder-level "a talk is being captured right now" signal. Set on
     * a successful [start], cleared in the shared [finalizeSession]
     * teardown that both the user [stop] and the focus-loss interruption
     * path route through. Consumed (via the `@TalkRecordingActive`
     * binding) by the seek sonar's suppression gate and the voice-guide
     * scheduler — iOS reads the same fact from the audio session's
     * mic-capable mode (`SeekSoundPlayer.swift:131-136@c1745e8`).
     * U9 port spec B14.
     */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * Wall-clock reading taken when the in-flight capture session opened,
     * or null when nothing is recording. Same [Clock] that stamps the
     * finished row's `startTimestamp`, so a caller adding
     * `now - recordingStartedAtMillis` to the completed-row sum lands on
     * the same number the row will carry (iOS reads the equivalent
     * `VoiceRecordingManagement.recordingStartDate@2ee1185:21`).
     */
    val recordingStartedAtMillis: Long?
        get() = session.get()?.startedAt

    // replay = 0 is safe: a recording can only START via WalkViewModel, which
    // subscribes to this flow in its init (eagerly, on Main.immediate) before
    // any recording is started — so no interruption can be emitted before the
    // sole consumer is collecting. extraBufferCapacity = 1 + DROP_OLDEST keeps
    // tryEmit non-suspending from the executor thread.
    private val _interruptions = MutableSharedFlow<Result<VoiceRecording>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits when an in-flight recording is finalized because the OS
     * reclaimed audio focus mid-recording (incoming call, another capture
     * app) — NOT on a normal user-initiated [stop]. On success the payload
     * carries the [VoiceRecording] for the audio captured up to the
     * interruption (the consumer persists it and tells the user recording
     * stopped); a failure payload means the interruption landed before any
     * PCM was written ([VoiceRecorderError.EmptyRecording]).
     */
    val interruptions: SharedFlow<Result<VoiceRecording>> = _interruptions.asSharedFlow()

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
            val startedAt = clock.now()
            val s = ActiveSession(
                walkId = walkId,
                relativePath = relativePath,
                absolutePath = absolute,
                writer = writer,
                startedAt = startedAt,
                stopRequested = AtomicBoolean(false),
            )
            session.set(s)
            // Enqueue the capture loop BEFORE registering the focus-loss
            // listener. The listener (delivered on the main Looper) calls
            // onAudioFocusLost, which submits finalizeSession to this same
            // single-thread executor; finalizeSession blocks on doneLatch,
            // which only the capture loop's `finally` counts down. If the
            // listener could fire before the capture loop were enqueued, the
            // finalize task would sit AHEAD of the capture loop in the FIFO
            // and deadlock the executor permanently. Enqueuing capture first
            // guarantees it always drains (and releases the latch) before any
            // finalize task runs. The listener captures THIS session so a
            // stale callback can't tear down a later recording.
            executor.execute { runCaptureLoop(s) }
            audioFocus.requestTransient(onLossListener = { onAudioFocusLost(s) })
            _isRecording.value = true
            return Result.success(absolute)
        }
    }

    /**
     * Stops the in-flight recording and returns its persisted entity.
     *
     * **Caller contract:** this method blocks on `doneLatch.await()` for
     * up to one `AudioCapture.read()` cycle (~100 ms on nominal
     * hardware; longer on MediaTek devices under battery saver). **Do
     * NOT call from a UI thread** — Stage 2-C must dispatch to
     * `Dispatchers.IO` or the foreground service's coroutine scope.
     * Calling on the main looper will ANR.
     */
    fun stop(): Result<VoiceRecording> {
        val s = synchronized(stateLock) {
            session.getAndSet(null)
                ?: return Result.failure(VoiceRecorderError.NoActiveRecording)
        }
        return finalizeSession(s)
    }

    /**
     * Shared teardown for both the user [stop] and the focus-loss
     * interruption path: signals the capture loop to stop, blocks until it
     * drains its last buffer (~100 ms), abandons focus, and either builds
     * the [VoiceRecording] entity or returns
     * [VoiceRecorderError.EmptyRecording] (deleting the header-only file).
     * Blocks on the done latch — must run off the main thread.
     */
    private fun finalizeSession(s: ActiveSession): Result<VoiceRecording> {
        // The session was already claimed (nulled) by the caller — flip
        // the flow immediately so suppression gates stop reading a live
        // recording during the drain wait below.
        _isRecording.value = false
        s.stopRequested.set(true)
        // Block until the capture loop finishes — it reads one more
        // buffer at most (~100 ms), then closes the writer.
        s.doneLatch.await()
        audioFocus.abandon()
        _audioLevel.value = 0f

        // Guard against silent 0-byte recordings: AudioRecord can fail
        // quietly (permission revoked mid-capture, Android 14+ FGS-type
        // mismatch on backgrounded recording, tap-record-then-stop-too-
        // fast), leaving us with a header-only WAV that would confuse a
        // user as a "0-second recording". Surface the empty case as a
        // typed error and delete the file so disk stays clean.
        val bytesCaptured = s.bytesWritten.get()
        if (bytesCaptured == 0L) {
            runCatching { Files.deleteIfExists(s.absolutePath) }
            return Result.failure(VoiceRecorderError.EmptyRecording)
        }

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

    /**
     * Invoked on the AudioManager focus-callback thread when the OS
     * reclaims audio focus during a recording (incoming call, another
     * capture app). Claims [expected] exactly once via `compareAndSet` —
     * a concurrent user [stop] races for the same session (whoever wins
     * finalizes; the loser no-ops), and a stale callback from an
     * already-finalized recording (or a re-sent OS event) can't tear down
     * a later one. Then finalizes the recording exactly like [stop] does,
     * via the shared [finalizeSession] (which abandons focus), and
     * publishes the result on [interruptions].
     */
    private fun onAudioFocusLost(expected: ActiveSession) {
        if (!session.compareAndSet(expected, null)) return
        // Signal the capture loop to stop BEFORE handing finalizeSession to
        // the executor: the loop must exit (and count down its latch) for the
        // queued-behind-it finalize task to unblock on the same single thread.
        expected.stopRequested.set(true)
        // finalizeSession blocks on the done latch — never run it on the focus
        // callback thread. The executor runs it right after the capture loop
        // exits; abandoning focus is left to finalizeSession so this path stays
        // symmetric with stop().
        executor.execute { _interruptions.tryEmit(finalizeSession(expected)) }
    }

    /**
     * Test hook: drive the exact focus-loss path the coordinator's
     * listener triggers for the active recording, without depending on the
     * OS to deliver a real focus-loss callback (Robolectric can't). No-op
     * when nothing is recording.
     */
    @VisibleForTesting
    internal fun simulateAudioFocusLoss() {
        session.get()?.let { onAudioFocusLost(it) }
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
                .getOrNull()?.let { s.bytesWritten.set(it) }
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
        val absolutePath: Path,
        val writer: WavWriter,
        val startedAt: Long,
        val stopRequested: AtomicBoolean,
        val doneLatch: CountDownLatch = CountDownLatch(1),
        val bytesWritten: AtomicLong = AtomicLong(0),
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
