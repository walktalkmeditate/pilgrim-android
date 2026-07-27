// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.di.ModelDownloadHttpClient

/**
 * Identity of the object the worker downloads and verifies. Production
 * binding carries [WhisperModelConfig]'s pinned constants; injectable
 * so tests exercise the full resume/verify protocol with small
 * payloads instead of a 148 MB body.
 */
data class WhisperModelDownloadSpec(
    val url: String,
    val expectedBytes: Long,
    val expectedSha256: String,
)

/**
 * Process-local serialization of `.part` writers (U9 spec C3): REPLACE
 * cancels the incumbent worker *asynchronously*, so without this lock
 * the replacement could append to the partial while the cancelled
 * writer's final buffered flush is still in flight — corrupting exactly
 * the paths designed to be lossless (constraint flip, user retry).
 * Holders must re-probe the partial's length after acquiring.
 */
@Singleton
class ModelDownloadFiles @Inject constructor() {
    val writerMutex = Mutex()
}

/** Seam over [StatFs] so the storage precheck is drivable in tests. */
fun interface FreeSpaceProbe {
    fun availableBytes(dir: File): Long
}

class StatFsFreeSpaceProbe @Inject constructor() : FreeSpaceProbe {
    override fun availableBytes(dir: File): Long = StatFs(dir.absolutePath).availableBytes
}

/**
 * Tiny test seam over [WalkRepository.walkIdsWithPendingTranscriptions]
 * so the worker's re-kick tests don't need the full repository graph
 * (same shape as `RecordingsCountSource`).
 */
fun interface PendingTranscriptionWalkSource {
    suspend fun walkIdsWithPendingTranscriptions(): List<Long>
}

class WalkRepositoryPendingTranscriptionWalkSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : PendingTranscriptionWalkSource {
    override suspend fun walkIdsWithPendingTranscriptions(): List<Long> =
        walkRepository.walkIdsWithPendingTranscriptions()
}

/**
 * Resumable, checksum-verified delivery of the whisper base model
 * (U9 spec `docs/parity/2026-07-26-port-model-download-u9.md`).
 *
 * Protocol summary:
 *  - Resume: non-empty `.part` + stored etag → `Range` + `If-Range`;
 *    206 appends, 200 restarts from zero under the response's etag,
 *    416 discards the partial. The partial survives cancellation and
 *    transient failures by design.
 *  - Verify: SHA-256 streamed during the write (resume hashes the
 *    existing prefix first); the full-file digest must equal
 *    [WhisperModelDownloadSpec.expectedSha256]. The write is bounded by
 *    the expected size — Content-Length or cumulative bytes past it
 *    abort before filling storage.
 *  - Deliver: digest match → atomic rename → sha marker LAST (U8 L4)
 *    → success side-effects (U10 hook — self-invalidating, see
 *    [WhisperModelStore.onBaseVerified] — then the transcription
 *    re-kick gated on the auto-transcribe preference).
 *  - Failures: checksum mismatch retries until [CHECKSUM_ATTEMPT_CAP],
 *    then terminal `checksum`; the StatFs precheck fails terminal
 *    `storage` before any network I/O; IO/network errors always
 *    `retry` and never surface as a state.
 */
@HiltWorker
class WhisperModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @ModelDownloadHttpClient private val httpClient: OkHttpClient,
    private val spec: WhisperModelDownloadSpec,
    private val files: ModelDownloadFiles,
    private val freeSpaceProbe: FreeSpaceProbe,
    private val store: WhisperModelStore,
    private val voicePreferences: VoicePreferencesRepository,
    private val pendingWalks: PendingTranscriptionWalkSource,
    private val transcriptionScheduler: TranscriptionScheduler,
) : CoroutineWorker(appContext, params) {

    private var lastReportedBytes = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        files.writerMutex.withLock {
            if (alreadyDelivered()) {
                Result.success()
            } else {
                runAttempt()
            }
        }
    }

    /**
     * The store's shared probe, keyed on the injected [spec] so tests
     * observe it: a stale-record re-enqueue (pruned WorkManager
     * history, `ensureEnqueued` after prune) becomes a cheap no-op
     * instead of a re-download.
     */
    private fun alreadyDelivered(): Boolean = WhisperModelConfig.verifiedModelPresent(
        filesDir = filesDir,
        expectedBytes = spec.expectedBytes,
        expectedSha256 = spec.expectedSha256,
    )

    private suspend fun runAttempt(): Result {
        val partial = WhisperModelConfig.basePartialPath(filesDir).toFile()
        val etagFile = WhisperModelConfig.baseEtagPath(filesDir).toFile()
        Files.createDirectories(WhisperModelConfig.baseModelPath(filesDir).parent)

        // Length re-probed under the writer mutex: a REPLACE-cancelled
        // writer has fully unwound by now, so this is the real length.
        var partialLength = if (partial.exists()) partial.length() else 0L
        if (partialLength > spec.expectedBytes) {
            discardPartial(partial, etagFile)
            partialLength = 0L
        }

        val available = freeSpaceProbe.availableBytes(applicationContext.filesDir)
        if (available < STORAGE_HEADROOM_BYTES - partialLength) {
            return Result.failure(workDataOf(KEY_FAILURE_REASON to REASON_STORAGE))
        }

        if (partialLength == spec.expectedBytes) {
            val digest = MessageDigest.getInstance(SHA_256)
            hashPrefix(digest, partial)
            return verifyAndDeliver(digest, partial, etagFile)
        }

        val storedEtag = if (partialLength > 0L && etagFile.exists()) {
            etagFile.readText().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (storedEtag == null && partialLength > 0L) {
            discardPartial(partial, etagFile)
            partialLength = 0L
        }

        reportProgress(partialLength)
        val request = Request.Builder().url(spec.url).apply {
            if (storedEtag != null) {
                header("Range", "bytes=$partialLength-")
                header("If-Range", storedEtag)
            }
        }.build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_PARTIAL && storedEtag != null ->
                        streamAndVerify(response, partial, etagFile, resumeFrom = partialLength)
                    response.code == HTTP_OK -> {
                        persistEtag(response, etagFile)
                        streamAndVerify(response, partial, etagFile, resumeFrom = 0L)
                    }
                    response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                        discardPartial(partial, etagFile)
                        Result.retry()
                    }
                    else -> Result.retry()
                }
            }
        } catch (ce: CancellationException) {
            // Partial + etag stay on disk — the rescheduled/replacing
            // attempt resumes from them (spec C3).
            throw ce
        } catch (io: IOException) {
            Log.w(TAG, "transfer failed; will retry from ${partial.length()} bytes", io)
            Result.retry()
        }
    }

    private suspend fun streamAndVerify(
        response: Response,
        partial: File,
        etagFile: File,
        resumeFrom: Long,
    ): Result {
        val body = response.body ?: return Result.retry()
        val contentLength = body.contentLength()
        if (contentLength >= 0L && resumeFrom + contentLength > spec.expectedBytes) {
            discardPartial(partial, etagFile)
            return Result.failure(workDataOf(KEY_FAILURE_REASON to REASON_CHECKSUM))
        }

        val digest = MessageDigest.getInstance(SHA_256)
        if (resumeFrom > 0L) hashPrefix(digest, partial)

        val total = copyBounded(
            source = body.byteStream(),
            target = partial,
            append = resumeFrom > 0L,
            digest = digest,
            alreadyHave = resumeFrom,
        )
        if (total == OVERSIZE) {
            discardPartial(partial, etagFile)
            return Result.failure(workDataOf(KEY_FAILURE_REASON to REASON_CHECKSUM))
        }
        if (total < spec.expectedBytes) {
            // Short stream: never verified, never terminal — the
            // partial resumes on the next attempt.
            return Result.retry()
        }
        return verifyAndDeliver(digest, partial, etagFile)
    }

    /** Returns cumulative bytes on stream end, or [OVERSIZE] when the body exceeds the bound. */
    private suspend fun copyBounded(
        source: InputStream,
        target: File,
        append: Boolean,
        digest: MessageDigest,
        alreadyHave: Long,
    ): Long {
        var total = alreadyHave
        FileOutputStream(target, append).use { out ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                if (total + read > spec.expectedBytes) return OVERSIZE
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
                if (total - lastReportedBytes >= PROGRESS_STEP_BYTES) reportProgress(total)
            }
        }
        return total
    }

    private suspend fun verifyAndDeliver(
        digest: MessageDigest,
        partial: File,
        etagFile: File,
    ): Result {
        reportProgress(spec.expectedBytes, verifying = true)
        val actual = digest.digest().toHex()
        if (actual != spec.expectedSha256) {
            discardPartial(partial, etagFile)
            return if (runAttemptCount >= CHECKSUM_ATTEMPT_CAP) {
                Result.failure(workDataOf(KEY_FAILURE_REASON to REASON_CHECKSUM))
            } else {
                Result.retry()
            }
        }

        val modelPath = WhisperModelConfig.baseModelPath(filesDir)
        try {
            Files.move(partial.toPath(), modelPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), modelPath, StandardCopyOption.REPLACE_EXISTING)
        }
        // Marker LAST (U8 L4): the only crash window allowed is "model
        // present, marker pending", which probes Absent and re-heals.
        WhisperModelConfig.baseShaMarkerPath(filesDir).toFile().writeText(spec.expectedSha256)
        etagFile.delete()

        store.onBaseVerified()
        rekickPendingTranscriptions()
        return Result.success()
    }

    /**
     * Walks whose batches failed (or backed off for hours) on the
     * missing/old model get their `transcribe-walk-<id>` re-enqueued
     * with REPLACE now that the model exists. Best-effort: a re-kick
     * failure must not fail an already-delivered download.
     */
    private suspend fun rekickPendingTranscriptions() {
        try {
            if (!voicePreferences.awaitAutoTranscribe()) return
            pendingWalks.walkIdsWithPendingTranscriptions().forEach { walkId ->
                transcriptionScheduler.rescheduleForWalk(walkId)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "transcription re-kick failed", t)
        }
    }

    private fun hashPrefix(digest: MessageDigest, partial: File) {
        FileInputStream(partial).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
    }

    private fun persistEtag(response: Response, etagFile: File) {
        val etag = response.header("ETag")?.trim()
        if (etag.isNullOrEmpty()) {
            etagFile.delete()
        } else {
            etagFile.writeText(etag)
        }
    }

    private fun discardPartial(partial: File, etagFile: File) {
        partial.delete()
        etagFile.delete()
    }

    private suspend fun reportProgress(bytes: Long, verifying: Boolean = false) {
        lastReportedBytes = bytes
        setProgress(
            workDataOf(
                KEY_BYTES_DOWNLOADED to bytes,
                KEY_TOTAL_BYTES to spec.expectedBytes,
                KEY_VERIFYING to verifying,
            ),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.US, it) }

    private val filesDir: Path
        get() = applicationContext.filesDir.toPath()

    companion object {
        const val UNIQUE_WORK_NAME = "whisper-model-download"

        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_VERIFYING = "verifying"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val REASON_CHECKSUM = "checksum"
        const val REASON_STORAGE = "storage"

        /**
         * `runAttemptCount` threshold for terminal FailedChecksum —
         * bounds bandwidth burned against a mispublished object while
         * still absorbing rare transit corruption.
         */
        const val CHECKSUM_ATTEMPT_CAP = 3

        /**
         * Free space required beyond what the partial already holds:
         * the remaining model bytes plus rename slack, rounded up.
         */
        const val STORAGE_HEADROOM_BYTES = 160L * 1024 * 1024

        internal const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val OVERSIZE = -1L
        private const val SHA_256 = "SHA-256"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val TAG = "WhisperModelDL"
    }
}
