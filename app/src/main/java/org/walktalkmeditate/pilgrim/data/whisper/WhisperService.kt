// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource

/**
 * iOS parity `WhisperService.swift@db4196e` — `POST /api/whispers` with
 * the same JSON body shape (`latitude`, `longitude`, `whisper_id`,
 * `category`, `expiry_option`) and `X-Device-Token` header. Server
 * returns `{"id": "<server-assigned>"}` on success.
 *
 * The OkHttpClient is the same project-wide singleton the share +
 * feedback paths use (10s connect / 30s read / 45s total). iOS uses
 * 15s; Android's 45s call timeout is more generous to absorb mobile
 * radio handoffs but the iOS-parity 429-vs-other-non-2xx behavior is
 * preserved.
 */
@Singleton
open class WhisperService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenSource,
    private val json: Json,
) {

    /**
     * @throws WhisperError.RateLimited on 429.
     * @throws WhisperError.ServerError on other non-2xx.
     * @throws WhisperError.NetworkError on transport failures.
     * @throws WhisperError.EncodingFailed if the request body fails
     *   to serialize (defensive — should not happen for our schema).
     */
    open suspend fun placeWhisper(
        latitude: Double,
        longitude: Double,
        whisperId: String,
        category: WhisperCategory,
        expiry: ExpiryDuration,
    ): PlaceWhisperResult {
        val body = try {
            json.encodeToString(
                WhisperRequest.serializer(),
                WhisperRequest(
                    latitude = latitude,
                    longitude = longitude,
                    whisperId = whisperId,
                    category = category.apiValue,
                    expiryOption = expiry.apiValue,
                ),
            )
        } catch (e: Exception) {
            throw WhisperError.EncodingFailed(e.message ?: "encode failed")
        }
        val token = deviceTokenStore.getToken()

        val request = Request.Builder()
            .url("$BASE_URL/api/whispers")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Device-Token", token)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).awaitResponse().use { response ->
                    when {
                        response.code == 429 -> throw WhisperError.RateLimited
                        !response.isSuccessful -> {
                            val errMsg = decodeError(response)
                            throw WhisperError.ServerError(response.code, errMsg)
                        }
                    }
                    val bodyStr = response.body.string()
                    val decoded = try {
                        json.decodeFromString(PlaceWhisperResponse.serializer(), bodyStr)
                    } catch (e: Exception) {
                        throw WhisperError.ServerError(response.code, "malformed response")
                    }
                    PlaceWhisperResult(id = decoded.id)
                }
            } catch (e: WhisperError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw WhisperError.NetworkError(e.message ?: "Network error")
            }
        }
    }

    private fun decodeError(response: Response): String =
        try {
            val bodyStr = response.body.string()
            json.decodeFromString(ServerErrorBody.serializer(), bodyStr).error
        } catch (_: Exception) {
            "Server error"
        }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
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
        const val BASE_URL = "https://walk.pilgrimapp.org"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class PlaceWhisperResult(val id: String)

@Serializable
private data class WhisperRequest(
    val latitude: Double,
    val longitude: Double,
    @SerialName("whisper_id") val whisperId: String,
    val category: String,
    @SerialName("expiry_option") val expiryOption: String,
)

@Serializable
private data class PlaceWhisperResponse(val id: String)

@Serializable
private data class ServerErrorBody(val error: String = "")
