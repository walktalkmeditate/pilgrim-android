// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * What one gather has done, as its worker reports it in progress and in
 * its output: iOS's per-Way `progress`, `failures` and `diskFull`, which
 * live only as long as the round (shared-walk spec S3 open question 5:
 * recomputed at each gather, never persisted past it).
 */
data class WayMediaReport(
    /** Files declared within the ceilings, those already on the phone included. */
    val accepted: Int,
    /** Of those, the ones neither landed nor failed yet. */
    val unfinished: Int,
    /** What won't arrive this round: refused, failed after its retry, over its cap, or on a full disk. */
    val failures: List<String>,
    val diskFull: Boolean,
) {
    /** iOS's `1 − left / total`: failures count as done. */
    val progress: Double get() = if (accepted > 0) 1.0 - unfinished.toDouble() / accepted else 1.0

    fun toData(): Data = workDataOf(
        KEY_ACCEPTED to accepted,
        KEY_UNFINISHED to unfinished,
        // Nothing shows the list (S1 §6.6); only whether it is empty matters.
        KEY_FAILURES to failures.take(MAX_REPORTED_FAILURES).toTypedArray(),
        KEY_DISK_FULL to diskFull,
    )

    companion object {
        private const val KEY_ACCEPTED = "accepted"
        private const val KEY_UNFINISHED = "unfinished"
        private const val KEY_FAILURES = "failures"
        private const val KEY_DISK_FULL = "disk_full"

        /** Keeps a hostile manifest's refusals inside WorkManager's 10 KB `Data` limit. */
        private const val MAX_REPORTED_FAILURES = 64

        /** Null for data no worker round wrote: a progress not yet reported, or a Way that was gone. */
        fun from(data: Data): WayMediaReport? {
            if (!data.keyValueMap.containsKey(KEY_ACCEPTED)) return null
            return WayMediaReport(
                accepted = data.getInt(KEY_ACCEPTED, 0),
                unfinished = data.getInt(KEY_UNFINISHED, 0),
                failures = data.getStringArray(KEY_FAILURES)?.toList().orEmpty(),
                diskFull = data.getBoolean(KEY_DISK_FULL, false),
            )
        }
    }
}

/**
 * Where the media worker fetches from and how it opens a partial file.
 * The client keeps OkHttp's own timeouts, as iOS's background session
 * keeps the platform's (S3 open question 4, recorded at the gate),
 * retries nothing on its own (the worker's one retry is the only one),
 * and refuses a redirect off the walk host before connecting to it (an
 * R6 addition, S3 §2).
 */
@Singleton
class WayMediaTransport internal constructor(
    val baseUrl: HttpUrl,
    val client: OkHttpClient,
    /** Opens or creates a partial for reading its length and writing, never truncating it. */
    val openPartial: (file: File) -> RandomAccessFile,
) {
    @Inject
    constructor() : this(
        baseUrl = WayImporter.BASE_URL.toHttpUrl(),
        client = mediaHttpClient(WayImporter.BASE_URL.toHttpUrl()),
        openPartial = { file -> RandomAccessFile(file, "rw") },
    )

    companion object {
        fun mediaHttpClient(base: HttpUrl): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .stayingOnTheHost(base)
            .build()
    }
}

/**
 * Gathers one shared Way's voices and photos into its `media/` folder
 * (iOS `WayMediaDownloader`, shared-walk spec S3 §2–§11), in the UI
 * process: WorkManager never runs in `:tracker`. One file at a time, in
 * moment order, under iOS's bounds:
 *
 * - every path re-checked against iOS's two shapes and rebuilt from its
 *   integer index, its file kept inside the Way's `media/` folder;
 * - 12 audio files and 20 photos at most, the excess failing at once;
 * - 15,728,640 bytes per audio file and 2,097,152 per photo, refused
 *   only when strictly larger, a resumed partial counted, enforced while
 *   the bytes arrive; a declared length over the cap is refused before
 *   reading (R6);
 * - any 2xx accepted; a Range request answered with the whole file
 *   restarts it;
 * - one immediate retry per file, not WorkManager's backoff;
 * - a full disk final for that file only: the files that landed stay;
 * - nothing lands for a Way gone before its rename, and its folder is
 *   never made again.
 *
 * A stopped run keeps its partial file and the next run resumes it. The
 * partial is opened once per fetch, before the request, and its length
 * and its bytes go through that one handle, so a partial unlinked during
 * the request is never resumed onto a fresh file: its rename fails and
 * the retry starts the file over. Nothing is logged: share ids and paths
 * are shared content.
 */
@HiltWorker
class WayMediaDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: WayStore,
    private val transport: WayMediaTransport,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val way = inputData.getString(KEY_WAY_ID)?.let(store::load)
        val share = way?.source as? WaySource.Share
        if (way == null || share == null || !WayImporter.isShareId(share.id) || way.id != "share:${share.id}") {
            return@withContext Result.success()
        }
        val split = WayMediaRules.withinCeilings(WayMediaRules.mediaFiles(way))
        val failures = split.refused.toMutableList()
        val missing = split.accepted.filterNot { store.mediaFile(way.id, it)?.exists() == true }
        var unfinished = missing.size
        var diskFull = false
        fun report() = WayMediaReport(split.accepted.size, unfinished, failures.toList(), diskFull)
        setProgress(report().toData())
        for (relative in missing) {
            when (fetchWithRetry(way.id, share.id, relative)) {
                Outcome.LANDED -> Unit
                Outcome.FAILED -> failures += relative
                Outcome.DISK_FULL -> {
                    failures += relative
                    diskFull = true
                }
                // Nothing is recorded against a Way that no longer exists (iOS's guard).
                Outcome.WAY_GONE -> return@withContext Result.success()
            }
            unfinished--
            setProgress(report().toData())
        }
        Result.success(report().toData())
    }

    private suspend fun fetchWithRetry(wayId: String, shareId: String, relative: String): Outcome {
        val first = fetch(wayId, shareId, relative)
        val settled = if (first == Attempt.RETRYABLE) fetch(wayId, shareId, relative) else first
        return when (settled) {
            Attempt.LANDED -> Outcome.LANDED
            Attempt.WAY_GONE -> Outcome.WAY_GONE
            Attempt.DISK_FULL -> Outcome.DISK_FULL
            Attempt.REFUSED, Attempt.RETRYABLE -> Outcome.FAILED
        }
    }

    private suspend fun fetch(wayId: String, shareId: String, relative: String): Attempt {
        val canonical = WayMediaRules.canonicalPath(relative) ?: return Attempt.REFUSED
        val partial = store.mediaPartialFile(wayId, canonical) ?: return Attempt.REFUSED
        return try {
            val file = store.holdMediaPartial(wayId, partial, transport.openPartial) ?: return Attempt.WAY_GONE
            try {
                file.use { transfer(it, wayId, shareId, canonical, partial) }
            } finally {
                store.releaseMediaPartial(partial)
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            when {
                isDiskFull(e) -> {
                    partial.delete()
                    Attempt.DISK_FULL
                }
                store.load(wayId) == null -> Attempt.WAY_GONE
                else -> Attempt.RETRYABLE
            }
        }
    }

    /** One request for [relative], resumed from what [file] already holds, then landed from [partial]. */
    private suspend fun transfer(
        file: RandomAccessFile,
        wayId: String,
        shareId: String,
        relative: String,
        partial: File,
    ): Attempt {
        val cap = WayMediaRules.byteCap(relative)
        val have = file.length().takeIf { it <= cap } ?: 0L
        val url = transport.baseUrl.newBuilder().addPathSegment(shareId).addPathSegments(relative).build()
        val request = Request.Builder().url(url)
            .apply { if (have > 0) header(HEADER_RANGE, "bytes=$have-") }
            .build()
        val call = transport.client.newCall(request)
        return cancelledWithTheWorker(call) {
            call.execute().use { response -> receive(response, file, wayId, relative, partial, have, cap) }
        }
    }

    private suspend fun receive(
        response: Response,
        file: RandomAccessFile,
        wayId: String,
        relative: String,
        partial: File,
        have: Long,
        cap: Long,
    ): Attempt {
        if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
            partial.delete()
            return Attempt.RETRYABLE
        }
        if (response.code !in HTTP_SUCCESS) return Attempt.RETRYABLE
        val partialReply = response.code == HTTP_PARTIAL && have > 0
        if (partialReply && !startsAt(response, have)) {
            partial.delete()
            return Attempt.RETRYABLE
        }
        val start = if (partialReply) have else 0L
        val declared = response.body.contentLength()
        if (declared >= 0 && start + declared > cap) {
            partial.delete()
            return Attempt.REFUSED
        }
        if (start == 0L) file.setLength(0)
        file.seek(start)
        val written = copyCapped(response.body.byteStream(), file, start, cap)
        if (written > cap) {
            partial.delete()
            return Attempt.REFUSED
        }
        file.fd.sync()
        // Closed before it lands, so nothing writes to the file once it has its own name.
        file.close()
        return if (store.landMedia(wayId, relative, partial)) Attempt.LANDED else Attempt.WAY_GONE
    }

    /** The bytes so far, or one past [cap] the moment they would cross it. */
    private suspend fun copyCapped(source: InputStream, out: RandomAccessFile, start: Long, cap: Long): Long {
        var total = start
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read == -1) return total
            if (total + read > cap) return cap + 1
            out.write(buffer, 0, read)
            total += read
        }
    }

    /**
     * A blocking call can't see the worker stop, so a watcher cancels it:
     * the read in flight then fails, and the caller rethrows the stop.
     */
    private suspend fun <T> cancelledWithTheWorker(call: Call, block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private fun startsAt(response: Response, offset: Long): Boolean {
        val range = response.header(HEADER_CONTENT_RANGE) ?: return false
        return CONTENT_RANGE.find(range)?.groupValues?.get(1)?.toLongOrNull() == offset
    }

    private enum class Attempt { LANDED, WAY_GONE, DISK_FULL, REFUSED, RETRYABLE }

    private enum class Outcome { LANDED, WAY_GONE, DISK_FULL, FAILED }

    companion object {
        const val KEY_WAY_ID = "way_id"

        private const val HEADER_RANGE = "Range"
        private const val HEADER_CONTENT_RANGE = "Content-Range"
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val HTTP_SUCCESS = 200..299
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-")
        private const val COPY_BUFFER_BYTES = 64 * 1024

        fun uniqueWorkName(wayId: String): String = "way-media-$wayId"

        /**
         * iOS `isDiskFull`'s three detections, Android's way: `ENOSPC` from
         * the write, the folder, or the rename, directly or as a cause; and
         * SQLite's full database, which is how WorkManager's own store
         * refuses a gather on a full phone.
         */
        internal fun isDiskFull(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(MAX_CAUSES).any { cause ->
                val message = cause.message.orEmpty()
                cause is SQLiteFullException ||
                    message.contains(ENOSPC_NAME) || message.contains(ENOSPC_TEXT) || isErrnoNoSpace(cause)
            }

        private fun isErrnoNoSpace(cause: Throwable): Boolean = try {
            cause is ErrnoException && cause.errno == OsConstants.ENOSPC
        } catch (e: LinkageError) {
            false
        }

        private const val MAX_CAUSES = 8
        private const val ENOSPC_NAME = "ENOSPC"
        private const val ENOSPC_TEXT = "No space left on device"
    }
}
