// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

@Singleton
open class CollectiveCounterService @Inject constructor(
    @CounterHttpClient private val client: OkHttpClient,
    private val json: Json,
    private val deviceTokenStore: DeviceTokenStore,
    @CounterBaseUrl private val baseUrl: String,
) {
    open suspend fun fetch(): CollectiveStats = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + CollectiveConfig.ENDPOINT)
            .get()
            .build()
        // OkHttp's execute throws IOException on transport errors; we
        // let CancellationException propagate naturally and wrap any
        // other Throwable into IOException so callers (CollectiveRepo
        // .fetchIfStale) only need to handle one error type.
        val response = try {
            client.newCall(request).execute()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw IOException("counter GET network error", t)
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw IOException("counter GET failed: ${r.code}")
            }
            try {
                json.decodeFromString(CollectiveStats.serializer(), body)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw IOException("counter GET decode failed", t)
            }
        }
    }

    open suspend fun post(delta: CollectiveCounterDelta): PostResult = withContext(Dispatchers.IO) {
        val body = try {
            json.encodeToString(CollectiveCounterDelta.serializer(), delta)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext PostResult.Failed(t)
        }
        val token = try {
            deviceTokenStore.getToken()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext PostResult.Failed(t)
        }
        val request = Request.Builder()
            .url(baseUrl + CollectiveConfig.ENDPOINT)
            .header("Content-Type", "application/json")
            .header("X-Device-Token", token)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext PostResult.Failed(t)
        }
        response.use { r ->
            // Drain the body so the connection can be pooled.
            r.body?.string()
            when {
                r.code == 429 -> PostResult.RateLimited
                r.isSuccessful -> PostResult.Success
                else -> PostResult.Failed(IOException("counter POST failed: ${r.code}"))
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
