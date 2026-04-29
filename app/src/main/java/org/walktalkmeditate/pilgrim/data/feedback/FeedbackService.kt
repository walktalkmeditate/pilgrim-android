// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource
import org.walktalkmeditate.pilgrim.di.FeedbackBaseUrl
import org.walktalkmeditate.pilgrim.di.FeedbackHttpClient

/**
 * iOS-parity "Trail Note" feedback POST. Reuses [DeviceTokenSource]
 * (and therefore the same persistent UUID the share path uses) for
 * the X-Device-Token header. The token is rate-limit metadata only —
 * the server hashes it with a salt before storage.
 */
@Singleton
class FeedbackService @Inject constructor(
    @FeedbackHttpClient private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenSource,
    private val json: Json,
    @FeedbackBaseUrl private val baseUrl: String,
) {

    /**
     * @throws FeedbackError.RateLimited when server returns 429.
     * @throws FeedbackError.ServerError for any other non-2xx.
     * @throws FeedbackError.NetworkError for transport failures.
     */
    suspend fun submit(category: String, message: String, deviceInfo: String?) {
        val body = json.encodeToString(
            FeedbackRequest.serializer(),
            FeedbackRequest(category, message, deviceInfo),
        )
        val token = deviceTokenStore.getToken()

        val request = Request.Builder()
            .url("$baseUrl/api/feedback")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Device-Token", token)
            .build()

        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).awaitResponse().use { response ->
                    when {
                        response.code == 429 -> throw FeedbackError.RateLimited
                        !response.isSuccessful -> throw FeedbackError.ServerError(response.code)
                    }
                }
            } catch (e: FeedbackError) {
                // Re-throw our own exceptions to preserve the caller-facing types.
                throw e
            } catch (e: CancellationException) {
                // OkHttp surfaces coroutine cancellation as InterruptedIOException
                // ("Canceled"), which IS an IOException — without this catch a
                // user navigating away mid-submit would see "Couldn't send."
                throw e
            } catch (e: IOException) {
                throw FeedbackError.NetworkError(e.message ?: "Network error")
            }
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    // resume(onCancellation = ...) closes the body if the
                    // cont was cancelled between the I/O thread firing
                    // onResponse and the suspension being resumed —
                    // otherwise the Response leaks an open connection.
                    cont.resume(response) { _, _, _ ->
                        runCatching { response.close() }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
