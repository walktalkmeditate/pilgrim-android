// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
) {
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

    @Serializable
    private data class SuccessResponse(val url: String, val id: String)

    @Serializable
    private data class ErrorResponse(val error: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

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
