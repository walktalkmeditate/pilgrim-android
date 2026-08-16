// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Stage 8-A: POSTs a walk to the Cloudflare Worker Share endpoint and
 * returns an ephemeral URL for the generated HTML page.
 *
 * HTTP calls run on [Dispatchers.IO]; OkHttp's blocking API is
 * network/disk-bound. CE is explicitly re-thrown inside the catch
 * (Stage 5-C audit rule) so coroutine cancellation unwinds cleanly
 * instead of being folded into a NetworkError.
 *
 * Uses the `@ShareHttpClient`-qualified OkHttp client (90s call
 * timeout) rather than the default 45s, since a share POST triggers
 * server-side Mapbox image generation + R2 writes that can
 * legitimately take 30-60s on slow connections.
 */
@Singleton
class ShareService @Inject constructor(
    @ShareHttpClient private val client: OkHttpClient,
    private val json: Json,
    private val deviceTokenStore: DeviceTokenStore,
    @ShareBaseUrl private val baseUrl: String,
    private val repairStore: ShareRepairStore,
) {
    /**
     * Media PUTs reuse [client]'s connect/read timeouts (10s / 30s —
     * the 30s read timeout is OkHttp's idle-per-read-call semantics,
     * matching the pin's documented "resets on bytes moving" contract,
     * `ShareService.swift:157-160@3f9f9e8`) but drop its 90s CALL
     * timeout (a hard ceiling unrelated to idle detection) entirely.
     * [client]'s 90s ceiling is sized for a JSON POST; a 15MB audio PUT
     * on a slow-but-alive connection can legitimately run past 90s and
     * must not be aborted while bytes are still moving — same reasoning
     * as `NetworkModule.provideModelDownloadHttpClient`'s unbounded
     * `callTimeout` for the whisper-model download, applied locally here
     * (not as a new DI-qualified client) since U6 does not own
     * `ShareModule`.
     */
    private val mediaClient: OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun share(payload: SharePayload): ShareResult = withContext(Dispatchers.IO) {
        val body = try {
            json.encodeToString(SharePayload.serializer(), payload)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw ShareError.EncodingFailed(t)
        }
        val token = deviceTokenStore.getToken()
        val request = Request.Builder()
            .url(baseUrl + ShareConfig.SHARE_ENDPOINT)
            .header("Content-Type", "application/json")
            .header("X-Device-Token", token)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            throw ShareError.NetworkError(io)
        }
        response.use { r ->
            val responseBody = r.body?.string().orEmpty()
            if (r.code == 429) throw ShareError.RateLimited
            if (!r.isSuccessful) {
                // Use explicit try/catch instead of runCatching —
                // kotlin stdlib's runCatching catches CE, which we
                // must re-throw (Stage 5-C lesson). The JSON decode
                // is synchronous here, so CE is unlikely but
                // defensive correctness still applies.
                val message = try {
                    json.decodeFromString(ErrorResponse.serializer(), responseBody).error
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    "Unknown error"
                }
                throw ShareError.ServerError(r.code, message)
            }
            try {
                val success = json.decodeFromString(SuccessResponse.serializer(), responseBody)
                ShareResult(url = success.url, id = success.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw ShareError.EncodingFailed(t)
            }
        }
    }

    /**
     * Phase 19 U6: sequential media upload with kill-safe repair
     * bookkeeping. Port of iOS `uploadAllMedia`
     * (`Pilgrim/Models/Share/ShareService.swift:167-226@3f9f9e8`) plus
     * `uploadSpecific` (`ShareService.swift:233-258@3f9f9e8`) — Android
     * collapses both into one function, since [UploadSlot.n] is always
     * caller-supplied rather than derived from list position (see class
     * doc on [UploadSlot]), the same call shape serves a fresh share
     * (dense `n = 1..N`) and a repair pass (cached original `n`) alike;
     * iOS needed two functions only because `uploadAllMedia` computes
     * `n` from `enumerated()` while `uploadSpecific` accepts it
     * pre-computed.
     *
     * **Order is load-bearing**: [photos] upload strictly before
     * [audio] — the constructor-parameter order below matches this
     * (unlike iOS, whose `uploadAllMedia(shareID:audioFiles:photos:)`
     * parameter order is audio-first while its LOOP order is
     * photos-first — the parity spec's §7 "Lens disagreements" flags
     * this as a trap for a port that mirrors the Swift parameter order
     * without noticing the loop disagrees with it,
     * `ShareService.swift:160-166@3f9f9e8`). Within each kind, slots
     * upload in the order given — callers are expected to pass them in
     * `n` order, though [uploadMedia] does not itself re-derive or
     * re-sort `n` (see [UploadSlot] class doc: filename/list position
     * must never drive slot numbering, only the caller-supplied `n`
     * does).
     *
     * **Kill-safety / cancellation**: every slot [photos] + [audio]
     * describe is durably recorded as [SlotStatus.PENDING] in
     * [ShareRepairStore] BEFORE the first PUT is attempted
     * ([ShareRepairStore.prePopulate], called first thing below) and
     * flipped to [SlotStatus.UPLOADED] the instant its PUT is confirmed
     * ([ShareRepairStore.markUploaded], called synchronously inline —
     * no unstructured-Task hop, unlike iOS's `onItemSuccess` callback).
     * Because nothing is ever marked done before it truly is, a process
     * death or a coroutine cancellation at ANY point leaves
     * [ShareRepairStore.load] accurate without any extra "mark the
     * untried tail" step: the pre-fail primitive iOS implements as
     * `backgroundTimeExhausted()` (`ShareService.swift:269-283@3f9f9e8`,
     * checked before every item AND mid-retry) has no Android
     * foreground-time-budget equivalent (Decision 4 of the port plan:
     * "no WorkManager in v1.4.0... the pin's pre-fail-doomed-PUTs gate
     * translates to marking the untried tail as pending-in-record before
     * cancellation completes") — here it translates to
     * [coroutineContext.ensureActive] calls at the SAME two checkpoints
     * (top of each item, and mid-retry before the backoff delay — see
     * [putWithRetry]), which THROW [CancellationException] rather than
     * returning a sentinel, per this project's CE re-throw discipline.
     * The exception propagates to the caller (never swallowed into a
     * fabricated [MediaUploadResult]); the repair record is the durable
     * side-channel of what happened. This is the explicit, testable
     * cancellation contract callers (U8) build on: cancel the coroutine
     * running this function to stop starting new PUTs — an
     * already-in-flight PUT is allowed to finish or fail on its own
     * (mirrors iOS: `Task.isCancelled` never aborts an in-flight
     * `URLSession` task either).
     *
     * **Retry**: each slot gets one automatic retry (2 attempts total,
     * 800ms backoff — [MEDIA_MAX_ATTEMPTS] / [MEDIA_RETRY_BACKOFF_MS],
     * `ShareService.swift:377-411@3f9f9e8`). Failures never throw past
     * the batch — they accumulate into [MediaUploadResult]'s per-kind
     * counts and the batch continues.
     *
     * **Identity**: a slot whose [UploadSlot.identity] doesn't match
     * what [ShareRepairStore.prePopulate] resolved for its `(kind, n)`
     * is refused — no PUT is attempted, no record write happens, and it
     * counts as failed. See [ShareRepairStore.prePopulate]'s KDoc for
     * the full merge/refuse contract this enforces.
     */
    suspend fun uploadMedia(
        walkUuid: String,
        shareId: String,
        shareUrl: String,
        photos: List<UploadSlot>,
        audio: List<UploadSlot>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): MediaUploadResult = uploadMediaBounded(
        walkUuid = walkUuid,
        shareId = shareId,
        shareUrl = shareUrl,
        photos = photos,
        audio = audio,
        retryBackoffMs = MEDIA_RETRY_BACKOFF_MS,
        onProgress = onProgress,
    )

    /**
     * [uploadMedia]'s implementation with the retry backoff exposed as
     * [retryBackoffMs] rather than hardcoded — same "expose the real
     * parameter, default it at the public entry point" shape as
     * [TourPhotoExporter.exportBounded]'s `perCandidateBudgetMs`, so
     * tests exercise the real retry loop without paying 800ms per
     * retried item.
     */
    internal suspend fun uploadMediaBounded(
        walkUuid: String,
        shareId: String,
        shareUrl: String,
        photos: List<UploadSlot>,
        audio: List<UploadSlot>,
        retryBackoffMs: Long,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): MediaUploadResult = withContext(Dispatchers.IO) {
        for (slot in photos) {
            require(slot.identity is SlotIdentity.Photo) {
                "photo slot n=${slot.n} must carry SlotIdentity.Photo, got ${slot.identity}"
            }
        }
        for (slot in audio) {
            require(slot.identity is SlotIdentity.Audio) {
                "audio slot n=${slot.n} must carry SlotIdentity.Audio, got ${slot.identity}"
            }
        }

        // Written before the first PUT, unconditionally — see class doc.
        val proposed = photos.map { RepairSlot(SlotKind.PHOTO, it.n, it.identity, SlotStatus.PENDING) } +
            audio.map { RepairSlot(SlotKind.AUDIO, it.n, it.identity, SlotStatus.PENDING) }
        val record = repairStore.prePopulate(walkUuid, shareId, proposed)

        val total = photos.size + audio.size
        var completed = 0
        onProgress(completed, total)

        val token = deviceTokenStore.getToken()
        var failedPhotos = 0
        var failedAudio = 0

        for (slot in photos) {
            coroutineContext.ensureActive() // Checkpoint A — see class doc
            if (!uploadOne(walkUuid, shareId, SlotKind.PHOTO, slot, record, token, retryBackoffMs)) failedPhotos++
            completed++
            onProgress(completed, total)
        }
        for (slot in audio) {
            coroutineContext.ensureActive() // Checkpoint A — see class doc
            if (!uploadOne(walkUuid, shareId, SlotKind.AUDIO, slot, record, token, retryBackoffMs)) failedAudio++
            completed++
            onProgress(completed, total)
        }

        if (failedPhotos == 0 && failedAudio == 0) {
            repairStore.clear(walkUuid)
        }

        MediaUploadResult(
            url = shareUrl,
            totalCount = total,
            failedPhotoCount = failedPhotos,
            failedAudioCount = failedAudio,
        )
    }

    /**
     * Resolves [slot] against the [record] [uploadMediaBounded] just
     * pre-populated, refusing an identity mismatch, short-circuiting an
     * already-[SlotStatus.UPLOADED] slot without a redundant network
     * call, and otherwise driving [putWithRetry] then recording success.
     */
    private suspend fun uploadOne(
        walkUuid: String,
        shareId: String,
        kind: SlotKind,
        slot: UploadSlot,
        record: RepairRecord,
        token: String,
        retryBackoffMs: Long,
    ): Boolean {
        val entry = checkNotNull(record.slots.firstOrNull { it.kind == kind && it.n == slot.n }) {
            "prePopulate must have produced an entry for $kind/${slot.n}"
        }
        return when {
            entry.identity != slot.identity -> false
            entry.status == SlotStatus.UPLOADED -> true
            else -> {
                val url = mediaUploadUrl(shareId, kind, slot.n)
                val mediaType = if (kind == SlotKind.PHOTO) PHOTO_MEDIA_TYPE else AUDIO_MEDIA_TYPE
                val success = putWithRetry(url, mediaType, slot.file, token, retryBackoffMs, "$kind/${slot.n}")
                if (success) repairStore.markUploaded(walkUuid, kind, slot.n)
                success
            }
        }
    }

    /**
     * At most [MEDIA_MAX_ATTEMPTS] attempts, [retryBackoffMs] backoff
     * before the single retry, re-checking cancellation between attempts
     * (Checkpoint B — not just once before the call, mirroring
     * `putWithRetry`'s mid-retry `backgroundTimeExhausted()` re-check,
     * `ShareService.swift:356-365@3f9f9e8`). Never throws past this
     * function except [CancellationException]: a non-2xx response or an
     * [IOException] both fold into `false`.
     */
    private suspend fun putWithRetry(
        url: String,
        mediaType: MediaType,
        file: File,
        token: String,
        retryBackoffMs: Long,
        slot: String,
    ): Boolean {
        var succeeded = false
        for (attempt in 0 until MEDIA_MAX_ATTEMPTS) {
            succeeded = attemptPut(url, mediaType, file, token, slot)
            if (succeeded) break
            if (attempt < MEDIA_MAX_ATTEMPTS - 1) {
                coroutineContext.ensureActive() // Checkpoint B — see class doc on uploadMedia
                delay(retryBackoffMs)
            }
        }
        return succeeded
    }

    /**
     * One PUT attempt. Builds a fresh [Request]/file-backed
     * [okhttp3.RequestBody] every call (never reused across attempts) —
     * mirrors iOS re-invoking its `body()` thunk fresh per attempt
     * (`ShareService.swift:347@3f9f9e8`) and, for audio, reading file
     * bytes lazily rather than pre-loaded
     * (`ShareService.swift:218-220@3f9f9e8` — Android's file-streaming
     * [okhttp3.RequestBody] goes further and never loads the whole file
     * into memory at all, unlike iOS's `Data(contentsOf:)`). Explicit
     * `Content-Type` header mirrors [share]'s existing pattern even
     * though OkHttp would also derive it from the body's own media type;
     * `Content-Length` is NOT set explicitly — OkHttp computes it from
     * the file-backed body's `contentLength()` automatically, which is
     * exact by construction (no risk of drifting from the real byte
     * count the way a manually-computed value could).
     *
     * Both failure modes fold into `false` for the caller, but neither
     * folds silently: a partial share is otherwise indistinguishable in a
     * bug report from a share that never tried, so the reason is logged
     * against [slot] (`KIND/n`, the slot descriptor the repair record and
     * the PUT path both key on).
     */
    private suspend fun attemptPut(
        url: String,
        mediaType: MediaType,
        file: File,
        token: String,
        slot: String,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", mediaType.toString())
            .header("X-Device-Token", token)
            .put(file.asRequestBody(mediaType))
            .build()
        return try {
            mediaClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "media PUT $slot rejected with HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (io: IOException) {
            Log.w(TAG, "media PUT $slot failed", io)
            false
        }
    }

    /**
     * `{baseUrl}/api/share/{shareId}/{photos|audio}/{n}`, 1-based `n` —
     * pinned literally by the parity spec's `testRequestShapeMatchesWorkerContract`
     * (`UnitTests/ShareMediaUploadTests.swift:6-14@3f9f9e8`:
     * `"https://walk.pilgrimapp.org/api/share/abc123defg/audio/3"`).
     * Path segments are asymmetric on purpose — `"photos"` (plural) vs
     * `"audio"` (singular) — matching iOS `MediaKind.rawValue`
     * (`ShareService.swift:144@3f9f9e8`) exactly; this is not a typo.
     */
    private fun mediaUploadUrl(shareId: String, kind: SlotKind, n: Int): String {
        val segment = when (kind) {
            SlotKind.PHOTO -> "photos"
            SlotKind.AUDIO -> "audio"
        }
        return "$baseUrl${ShareConfig.SHARE_ENDPOINT}/$shareId/$segment/$n"
    }

    @Serializable
    private data class SuccessResponse(val url: String, val id: String)

    @Serializable
    private data class ErrorResponse(val error: String)

    companion object {
        private const val TAG = "ShareService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** `ShareService.swift:150@3f9f9e8`. */
        internal val PHOTO_MEDIA_TYPE = "image/jpeg".toMediaType()

        /** `ShareService.swift:150@3f9f9e8` — see [SharePrepStore]'s WAV->AAC-LC transcode (Decision 1, U4) for why Android's mp4/m4a audio matches this despite recording as 16kHz mono WAV. */
        internal val AUDIO_MEDIA_TYPE = "audio/mp4".toMediaType()

        /** `ShareService.swift:384@3f9f9e8` (`for attempt in 0..<2`) — one automatic retry per item. */
        internal const val MEDIA_MAX_ATTEMPTS = 2

        /** `ShareService.swift:404@3f9f9e8` (`800_000_000` ns). */
        internal const val MEDIA_RETRY_BACKOFF_MS = 800L
    }
}

/**
 * One media file [uploadMedia][ShareService.uploadMedia] should PUT.
 * [n] is the 1-based worker slot the bytes belong under
 * (`/api/share/{shareId}/{photos|audio}/{n}`) — supplied by the caller,
 * NEVER re-derived from this list's position by [ShareService.uploadMedia]
 * itself. This is what makes a repair pass safe to share the exact same
 * function as a fresh upload: for a fresh share, the caller assigns dense
 * `n = 1..N` (e.g. U5's [TourPhoto.n], the successfully-exported set's
 * own position); for a repair pass, the caller assigns the ORIGINAL
 * failed slot's `n` recovered from [ShareRepairStore] — which may not
 * equal this item's position in whatever CURRENT candidate list it was
 * re-resolved from (U5's carry-note: photo files are named by
 * dense success-order `n`, and a retry pass must not re-number). Because
 * [ShareService.uploadMedia] treats [n] as opaque caller data and cross-
 * checks [identity] against [ShareRepairStore]'s recorded identity for
 * that exact `n` before ever PUTting (see [ShareRepairStore.prePopulate]),
 * a caller that resolves the wrong file to a given `n` gets a refused
 * slot, never a wrong-slot upload.
 */
data class UploadSlot(val n: Int, val file: File, val identity: SlotIdentity)

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ShareHttpClient

/**
 * Qualifier for the Share Worker base URL. Stage 5-C's
 * `@VoiceGuideManifestUrl` / `@VoiceGuidePromptBaseUrl` pattern —
 * avoids the `@JvmInline value class` Hilt-factory-visibility trap.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ShareBaseUrl
