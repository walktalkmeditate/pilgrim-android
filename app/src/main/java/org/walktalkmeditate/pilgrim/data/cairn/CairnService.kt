// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

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
 * iOS parity `CairnService.swift@db4196e` — `POST /api/cairns` with
 * the same JSON body shape (`latitude`, `longitude`) and
 * `X-Device-Token` header. Server returns
 * `{"id": "<server-assigned>", "stone_count": <int>}`. `stone_count`
 * is the count AFTER the user's stone has been added — so 1 for a
 * fresh cairn, N+1 for stacking onto an existing one within the
 * server's merge radius.
 */
@Singleton
open class CairnService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val deviceTokenStore: DeviceTokenSource,
    private val json: Json,
) {

    /**
     * @throws CairnError.ServerError on non-2xx.
     * @throws CairnError.NetworkError on transport failures.
     * @throws CairnError.EncodingFailed if the request body fails to
     *   serialize (defensive — should not happen for our schema).
     */
    open suspend fun placeStone(
        latitude: Double,
        longitude: Double,
    ): PlaceStoneResult {
        val body = try {
            json.encodeToString(
                CairnRequest.serializer(),
                CairnRequest(latitude, longitude),
            )
        } catch (e: Exception) {
            throw CairnError.EncodingFailed(e.message ?: "encode failed")
        }
        val token = deviceTokenStore.getToken()

        val request = Request.Builder()
            .url("$BASE_URL/api/cairns")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Device-Token", token)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) {
                        val errMsg = decodeError(response)
                        throw CairnError.ServerError(response.code, errMsg)
                    }
                    val bodyStr = response.body.string()
                    val decoded = try {
                        json.decodeFromString(PlaceStoneResponse.serializer(), bodyStr)
                    } catch (e: Exception) {
                        throw CairnError.ServerError(response.code, "malformed response")
                    }
                    PlaceStoneResult(id = decoded.id, stoneCount = decoded.stoneCount)
                }
            } catch (e: CairnError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw CairnError.NetworkError(e.message ?: "Network error")
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

data class PlaceStoneResult(val id: String, val stoneCount: Int)

@Serializable
private data class CairnRequest(val latitude: Double, val longitude: Double)

@Serializable
private data class PlaceStoneResponse(
    val id: String,
    @SerialName("stone_count") val stoneCount: Int,
)

@Serializable
private data class ServerErrorBody(val error: String = "")
