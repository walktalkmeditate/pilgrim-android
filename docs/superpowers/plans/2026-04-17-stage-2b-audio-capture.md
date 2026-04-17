# Stage 2-B Implementation Plan — Audio capture

**Spec:** [2026-04-17-stage-2b-audio-capture-design.md](../specs/2026-04-17-stage-2b-audio-capture-design.md)

**Test command (run after every task):**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

Task order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10. Green build/test between every task.

---

## Task 1 — Manifest permission + `PermissionChecks` extension

**File:** `app/src/main/AndroidManifest.xml` (modify)

Add alongside existing `uses-permission` entries:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

Place near `ACTIVITY_RECOGNITION` / `POST_NOTIFICATIONS` grouping — it's a
user-facing runtime permission, not infrastructure.

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionChecks.kt` (modify)

Add after `isActivityRecognitionGranted`:

```kotlin
fun isMicrophoneGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
```

**Verify:** `./gradlew assembleDebug` succeeds.

---

## Task 2 — `AudioCapture` interface

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/AudioCapture.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Hardware-mockable abstraction over the PCM source for voice
 * recording. Production implementation wraps [android.media.AudioRecord];
 * tests use [org.walktalkmeditate.pilgrim.audio.FakeAudioCapture] to
 * feed canned PCM buffers without a device.
 *
 * Contract:
 * - [start] must be called before [read]. Throws if the underlying
 *   recorder fails to initialize.
 * - [read] is blocking. Returns number of shorts read (0 or positive),
 *   or -1 on EOF / irrecoverable error. Same contract as AudioRecord.
 * - [stop] is idempotent; subsequent [read] returns -1.
 * - All methods MUST be called from the same thread (see VoiceRecorder's
 *   single-thread Executor). Implementations are not required to be
 *   thread-safe.
 */
interface AudioCapture {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    val sampleRateHz: Int
    val channels: Int
}
```

---

## Task 3 — `AudioRecordCapture` production implementation

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/AudioRecordCapture.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import javax.inject.Inject

/**
 * 16 kHz mono 16-bit PCM capture backed by [AudioRecord]. Not
 * thread-safe — VoiceRecorder pins the entire lifecycle to a single
 * dedicated thread.
 */
@SuppressLint("MissingPermission")
class AudioRecordCapture @Inject constructor() : AudioCapture {

    override val sampleRateHz: Int = SAMPLE_RATE_HZ
    override val channels: Int = 1

    private var recorder: AudioRecord? = null

    override fun start() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
        )
        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize returned $minBufferBytes"
        }
        val bufferBytes = maxOf(minBufferBytes, BUFFER_BYTES_MIN)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            bufferBytes,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord state=${r.state} (uninitialized)"
        }
        r.startRecording()
        recorder = r
    }

    override fun read(buffer: ShortArray): Int =
        recorder?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord.stop throws if never startRecording'd. OK here.
        }
        r.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 100 ms @ 16 kHz mono 16-bit = 3200 bytes. Floor at 4096 for
        // slack against OEM minimums.
        const val BUFFER_BYTES_MIN = 4_096
    }
}
```

---

## Task 4 — `WavWriter` pure-Kotlin

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/WavWriter.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Minimal RIFF/WAVE writer for 16-bit mono PCM. Writes a placeholder
 * 44-byte header on [openForWriting], appends little-endian PCM on
 * [append], and patches the two size fields on [closeAndPatchHeader].
 *
 * Not thread-safe. Caller is the single-threaded VoiceRecorder capture
 * Executor.
 */
class WavWriter(
    private val path: Path,
    private val sampleRateHz: Int,
) {
    private var file: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0

    fun openForWriting() {
        check(file == null) { "WavWriter already open for $path" }
        val f = RandomAccessFile(path.toFile(), "rw")
        f.setLength(0)
        f.write(buildHeader(dataSize = 0))
        file = f
        dataBytesWritten = 0
    }

    fun append(samples: ShortArray, count: Int) {
        val f = checkNotNull(file) { "WavWriter not open" }
        require(count in 0..samples.size) { "count=$count out of range" }
        if (count == 0) return
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        f.write(bytes.array())
        dataBytesWritten += count * 2
    }

    /** Patches data-size + chunk-size, closes the file. Returns bytes of PCM written. */
    fun closeAndPatchHeader(): Long {
        val f = checkNotNull(file) { "WavWriter not open" }
        file = null
        // Patch chunk size at offset 4 (file size - 8)
        f.seek(CHUNK_SIZE_OFFSET.toLong())
        f.write(leInt((HEADER_BYTES + dataBytesWritten - 8).toInt()))
        // Patch data size at offset 40
        f.seek(DATA_SIZE_OFFSET.toLong())
        f.write(leInt(dataBytesWritten.toInt()))
        f.close()
        return dataBytesWritten
    }

    private fun buildHeader(dataSize: Int): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = HEADER_BYTES + dataSize - 8
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(chunkSize)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                // subchunk size for PCM
        b.putShort(1)               // audio format = PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRateHz)
        b.putInt(byteRate)
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataSize)
        return b.array()
    }

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    companion object {
        const val HEADER_BYTES = 44
        const val CHUNK_SIZE_OFFSET = 4
        const val DATA_SIZE_OFFSET = 40
    }
}
```

---

## Task 5 — `VoiceRecorderError` sealed class

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoiceRecorderError.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Typed failure modes for [VoiceRecorder.start] / [VoiceRecorder.stop].
 * Stage 2-C's UI switches on the concrete subclass to surface a
 * user-appropriate message.
 */
sealed class VoiceRecorderError : Exception() {
    data object PermissionMissing : VoiceRecorderError() {
        private fun readResolve(): Any = PermissionMissing
        override val message: String = "RECORD_AUDIO not granted"
    }

    data object ConcurrentRecording : VoiceRecorderError() {
        private fun readResolve(): Any = ConcurrentRecording
        override val message: String = "a recording is already in progress"
    }

    data object NoActiveRecording : VoiceRecorderError() {
        private fun readResolve(): Any = NoActiveRecording
        override val message: String = "stop() called with no active recording"
    }

    data class AudioCaptureInitFailed(override val cause: Throwable? = null) : VoiceRecorderError() {
        override val message: String = "AudioRecord failed to initialize" +
            (cause?.message?.let { ": $it" } ?: "")
    }

    data class FileSystemError(override val cause: Throwable) : VoiceRecorderError() {
        override val message: String = "failed to create recording file: ${cause.message}"
    }
}
```

`readResolve` keeps data-object equality stable across serialization /
deserialization boundaries (unlikely to matter here, but free safety).

---

## Task 6 — `AudioFocusCoordinator` helper

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/AudioFocusCoordinator.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AudioManager focus request/abandon so the [AudioFocusRequest]
 * instance is owned in one place — you must pass the SAME request
 * object to `abandonAudioFocusRequest` that you passed to `requestAudioFocus`,
 * so we hold the reference here rather than in VoiceRecorder's
 * state machine.
 */
@Singleton
class AudioFocusCoordinator @Inject constructor(
    private val audioManager: AudioManager,
) {
    private var activeRequest: AudioFocusRequest? = null

    /** Returns true if focus was granted. */
    fun requestTransient(): Boolean {
        abandonIfHeld()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        activeRequest = request
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() = abandonIfHeld()

    private fun abandonIfHeld() {
        val req = activeRequest ?: return
        activeRequest = null
        audioManager.abandonAudioFocusRequest(req)
    }
}
```

**Note:** we don't block recording on `requestTransient() == false`.
If focus denial happens (unusual — only when system has exclusive
lock), we still record. The return value is available for Stage 2-C
to surface a "something's grabbing audio" hint if desired.

---

## Task 7 — `VoiceRecorder` orchestrator

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoiceRecorder.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
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
 * on a dedicated single-thread Executor; state mutations are
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
                walkUuid = walkUuid,
                recordingUuid = recordingUuid,
                relativePath = relativePath,
                absolutePath = absolute,
                writer = writer,
                startedAt = startedAt,
                stopRequested = java.util.concurrent.atomic.AtomicBoolean(false),
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
        // The capture-loop thread observes stopRequested, exits the
        // read loop, closes the writer, and finishes. Block until it
        // does — short (one buffer read at most, ~100 ms).
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
                if (n <= 0) break
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
        // Short.MAX_VALUE = 32767. Clamp; guard log scaling for Stage 2-C.
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private data class ActiveSession(
        val walkId: Long,
        val walkUuid: String,
        val recordingUuid: String,
        val relativePath: String,
        val absolutePath: Path,
        val writer: WavWriter,
        val startedAt: Long,
        val stopRequested: java.util.concurrent.atomic.AtomicBoolean,
        val doneLatch: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
    )

    private companion object {
        const val TAG = "VoiceRecorder"
        const val THREAD_NAME = "voice-recorder"
        // 100 ms @ 16 kHz mono = 1600 samples. Balance latency vs syscall
        // overhead. Short enough to make stopRequested responsive.
        const val BUFFER_SAMPLES = 1_600
    }
}
```

---

## Task 8 — Hilt DI providers

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/AudioModule.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.media.AudioManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.AudioCapture
import org.walktalkmeditate.pilgrim.audio.AudioRecordCapture

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    abstract fun bindAudioCapture(impl: AudioRecordCapture): AudioCapture

    companion object {
        @Provides
        @Singleton
        fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
            context.getSystemService(AudioManager::class.java)
    }
}
```

Note: `@Binds` requires an abstract method in an abstract class/module.
`@Provides` methods go in a companion object. Hilt handles both in one
module.

---

## Task 9 — Tests

### Task 9a — `WavWriterTest`

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/WavWriterTest.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WavWriterTest {

    private lateinit var tempDir: java.nio.file.Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("wavwriter-test")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `empty file has byte-exact RIFF WAVE 16kHz mono 16-bit header`() {
        val path = tempDir.resolve("empty.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        val bytes = writer.closeAndPatchHeader()

        assertEquals(0L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(44, file.size)
        // chunk size = 44 - 8 = 36
        assertEquals(36, readLeInt(file, 4))
        // data size = 0
        assertEquals(0, readLeInt(file, 40))
        // "RIFF" "WAVE" "fmt " "data"
        assertEquals("RIFF", String(file, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(file, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(file, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(file, 36, 4, Charsets.US_ASCII))
        assertEquals(16, readLeInt(file, 16))    // fmt subchunk size
        assertEquals(1, readLeShort(file, 20))   // PCM format
        assertEquals(1, readLeShort(file, 22))   // mono
        assertEquals(16_000, readLeInt(file, 24))
        assertEquals(32_000, readLeInt(file, 28)) // byte rate = 16000 * 1 * 2
        assertEquals(2, readLeShort(file, 32))   // block align
        assertEquals(16, readLeShort(file, 34))  // bits per sample
    }

    @Test
    fun `one sample patches data_size to 2 and chunk_size to 38`() {
        val path = tempDir.resolve("one.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(0x1234), count = 1)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(2L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(46, file.size)
        assertEquals(38, readLeInt(file, 4))
        assertEquals(2, readLeInt(file, 40))
        // Little-endian 0x1234 → 0x34 0x12 at offsets 44, 45
        assertEquals(0x34.toByte(), file[44])
        assertEquals(0x12.toByte(), file[45])
    }

    @Test
    fun `three samples encoded little-endian in order`() {
        val path = tempDir.resolve("three.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(0x0001, 0x7FFF, 0x8001.toShort()), count = 3)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(6L, bytes)
        val file = Files.readAllBytes(path)
        val expectedPcm = byteArrayOf(
            0x01, 0x00,   // 0x0001 LE
            0xFF.toByte(), 0x7F,   // 0x7FFF LE
            0x01, 0x80.toByte(),   // 0x8001 LE
        )
        assertArrayEquals(expectedPcm, file.sliceArray(44 until 50))
    }

    @Test
    fun `append with partial count writes only that many samples`() {
        val path = tempDir.resolve("partial.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(1, 2, 3, 4, 5), count = 2)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(4L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(48, file.size)
    }

    @Test
    fun `sample rate and byte rate track the constructor param`() {
        val path = tempDir.resolve("rate.wav")
        val writer = WavWriter(path, sampleRateHz = 44_100)

        writer.openForWriting()
        writer.closeAndPatchHeader()

        val file = Files.readAllBytes(path)
        assertEquals(44_100, readLeInt(file, 24))
        assertEquals(44_100 * 2, readLeInt(file, 28)) // byte rate
    }

    @Test
    fun `append without open throws IllegalStateException`() {
        val path = tempDir.resolve("never-opened.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)
        try {
            writer.append(shortArrayOf(1), count = 1)
            assert(false) { "expected IllegalStateException" }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertTrue("no file was created", !Files.exists(path))
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
}
```

### Task 9b — `FakeAudioCapture`

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/FakeAudioCapture.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [AudioCapture]. Feeds a pre-configured stream of PCM
 * buffers on [read], optionally throwing on [start] or [stop] for
 * negative-path coverage.
 */
class FakeAudioCapture(
    override val sampleRateHz: Int = 16_000,
    override val channels: Int = 1,
    /** PCM samples to feed in response to reads. A short array means one read call returns its entire length. */
    private val bursts: List<ShortArray> = listOf(ShortArray(1_600) { 100 }),
    var startThrowable: Throwable? = null,
) : AudioCapture {

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val cursor = AtomicInteger(0)
    val stopCallCount = AtomicInteger(0)

    override fun start() {
        startThrowable?.let { throw it }
        started.set(true)
    }

    override fun read(buffer: ShortArray): Int {
        if (!started.get()) return -1
        val idx = cursor.getAndIncrement()
        if (idx >= bursts.size) return -1
        val src = bursts[idx]
        val n = minOf(src.size, buffer.size)
        System.arraycopy(src, 0, buffer, 0, n)
        return n
    }

    override fun stop() {
        started.set(false)
        stopCallCount.incrementAndGet()
    }
}
```

### Task 9c — `VoiceRecorderTest`

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/VoiceRecorderTest.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        recorder.stop()
        val recDir = context.filesDir.toPath().resolve("recordings")
        if (Files.exists(recDir)) recDir.toFile().deleteRecursively()
    }

    @Test
    fun `start then stop produces a valid VoiceRecording with file on disk`() = runTest {
        val started = recorder.start(walkId = 42L, walkUuid = "walk-uuid-1")
        assertTrue("start should succeed", started.isSuccess)
        val path = started.getOrThrow()
        // Wait for capture loop to consume all bursts + hit EOF.
        Thread.sleep(100)
        clock.advanceTo(3_500L)
        val stopped = recorder.stop()
        assertTrue("stop should succeed: ${stopped.exceptionOrNull()}", stopped.isSuccess)

        val recording = stopped.getOrThrow()
        assertEquals(42L, recording.walkId)
        assertEquals(1_000L, recording.startTimestamp)
        assertEquals(3_500L, recording.endTimestamp)
        assertEquals(2_500L, recording.durationMillis)
        assertEquals("recordings/walk-uuid-1/${recording.uuid.let { "" }}".let {
            // fileRelativePath uses its own UUID, not the recording entity's uuid.
            // Just check the pattern.
            recording.fileRelativePath
        }.let { it.startsWith("recordings/walk-uuid-1/") && it.endsWith(".wav") }, true)
        assertTrue("file should exist at the returned path", Files.exists(path))
        assertTrue("file should contain a non-empty WAV", Files.size(path) > WavWriter.HEADER_BYTES)
    }

    @Test
    fun `second start returns ConcurrentRecording without side effects`() = runTest {
        recorder.start(walkId = 1L, walkUuid = "walk-1").getOrThrow()
        val second = recorder.start(walkId = 1L, walkUuid = "walk-1")

        assertTrue(second.isFailure)
        assertEquals(VoiceRecorderError.ConcurrentRecording, second.exceptionOrNull())
        // First recording still active; stop it for cleanup.
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
        val result = recorder.start(walkId = 1L, walkUuid = "walk-1")
        assertTrue(result.isFailure)
        assertEquals(VoiceRecorderError.PermissionMissing, result.exceptionOrNull())
    }

    @Test
    fun `AudioCapture start failure maps to AudioCaptureInitFailed and cleans up file`() = runTest {
        audioCapture = FakeAudioCapture(startThrowable = IllegalStateException("mic busy"))
        recorder = VoiceRecorder(context, audioCapture, focus, clock)

        val result = recorder.start(walkId = 1L, walkUuid = "walk-err")

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected AudioCaptureInitFailed, got $err", err is VoiceRecorderError.AudioCaptureInitFailed)
        val leftoverDir = context.filesDir.toPath().resolve("recordings/walk-err")
        val leftovers = if (Files.exists(leftoverDir)) Files.list(leftoverDir).use { it.toList() } else emptyList()
        assertTrue("expected no leftover .wav files, got $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `audioLevel emits non-zero during recording and returns to zero after stop`() = runTest {
        recorder.audioLevel.test {
            assertEquals(0f, awaitItem())

            recorder.start(walkId = 1L, walkUuid = "walk-level").getOrThrow()
            // Wait for the capture loop to emit at least one level reading.
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
    fun `directory is created on first record and reused for subsequent recordings`() = runTest {
        recorder.start(walkId = 1L, walkUuid = "walk-reuse").getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(2_000L)
        recorder.stop().getOrThrow()

        clock.advanceTo(3_000L)
        recorder.start(walkId = 1L, walkUuid = "walk-reuse").getOrThrow()
        Thread.sleep(50)
        clock.advanceTo(4_000L)
        recorder.stop().getOrThrow()

        val dir = context.filesDir.toPath().resolve("recordings/walk-reuse")
        val files = Files.list(dir).use { it.toList() }
        assertEquals(2, files.size)
    }

    @Test
    fun `stop closes WAV file and header reports positive data bytes`() = runTest {
        recorder.start(walkId = 1L, walkUuid = "walk-header").getOrThrow()
        Thread.sleep(100)
        clock.advanceTo(2_000L)
        val rec = recorder.stop().getOrThrow()

        val absolute = context.filesDir.toPath().resolve(rec.fileRelativePath)
        val totalBytes = Files.size(absolute)
        // 44-byte header + at least one buffer (1600 samples * 2 bytes).
        assertTrue("expected at least header + 1 buffer, got $totalBytes", totalBytes >= 44 + 3200)
        // Verify data_size field matches payload bytes.
        val raf = java.io.RandomAccessFile(absolute.toFile(), "r")
        raf.seek(40)
        val buf = ByteArray(4)
        raf.readFully(buf)
        raf.close()
        val dataSize = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(totalBytes - 44, dataSize.toLong())
    }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) { current = millis }
}
```

---

## Task 10 — Full CI gate + commit

Run:
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

**Expected:** 100 → ~113 tests passing, BUILD SUCCESSFUL, no new lint.

Commit as one atomic step:
```
feat(audio): Stage 2-B — AudioRecord capture + WAV writer + mic permission

VoiceRecorder @Singleton with start/stop returning Result<T> of typed
errors (PermissionMissing / ConcurrentRecording / NoActiveRecording /
AudioCaptureInitFailed / FileSystemError). Captures 16 kHz mono 16-bit
PCM directly into a RIFF/WAVE file (header patched on close). Dedicated
single-thread Executor runs the read loop; audioLevel StateFlow publishes
RMS per buffer for Stage 2-C's level meter. AudioFocusCoordinator owns
the AudioFocusRequest pair so the abandon uses the same instance as the
request.

AudioCapture interface + AudioRecordCapture production impl + test-side
FakeAudioCapture mirror the LocationSource / FusedLocationSource split
so VoiceRecorderTest runs without device hardware.

No UI, no transcription, no playback — Stages 2-C / 2-D / 2-E bind to
this surface. Voice recording is screen-on-only for MVP; FGS type=
microphone is a forward-carry.
```
