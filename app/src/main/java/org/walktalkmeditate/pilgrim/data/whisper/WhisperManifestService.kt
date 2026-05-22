// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import android.util.Log
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * iOS parity `WhisperManifestService.swift@db4196e` — MVP shape.
 * Fetches the whisper manifest from CDN and exposes the active
 * (non-retired) entries grouped by [WhisperCategory].
 *
 * Deferred (separate PR):
 *  - Local cache (write-through DataStore / file)
 *  - Bootstrap from a bundled JSON in `assets/`
 *  - Periodic re-fetch / version migration
 *
 * For MVP we just lazy-fetch on first need; if the fetch fails the
 * VM's place-whisper code path bails to a "couldn't load whisper
 * catalog" banner. A failed manifest does NOT block placement of
 * already-cached categories — but we have no cache yet, so today a
 * failed manifest = no placement.
 */
@Singleton
open class WhisperManifestService @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    protected val _manifest = MutableStateFlow<WhisperManifest?>(null)
    open val manifest: StateFlow<WhisperManifest?> = _manifest.asStateFlow()

    /**
     * One-shot fetch. Returns `true` on success. Caller may invoke
     * this once at app start (or lazily on the placement path) — the
     * result is held in [manifest]. No automatic retry.
     */
    open suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(MANIFEST_URL).get().build()
        try {
            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "manifest fetch HTTP ${response.code}")
                    return@withContext false
                }
                val bodyStr = response.body.string()
                // iOS parity: lossy per-entry decode. A future iOS
                // release that adds a 9th [WhisperCategory] would
                // otherwise crash the entire manifest parse on every
                // pre-update Android user (CDN is updated
                // independently of the binary). Two-pass: decode the
                // outer container with `whispers: List<JsonElement>`,
                // then map each element through a per-entry
                // `runCatching`. Unknown-enum / missing-field entries
                // are silently dropped; the rest are kept.
                val raw = json.decodeFromString(WhisperManifestRaw.serializer(), bodyStr)
                val parsed = raw.whispers.mapNotNull { elem ->
                    runCatching {
                        json.decodeFromJsonElement(WhisperDefinition.serializer(), elem)
                    }.onFailure { e ->
                        Log.w(TAG, "skipping unparseable whisper entry: ${e.message}")
                    }.getOrNull()
                }
                _manifest.value = WhisperManifest(version = raw.version, whispers = parsed)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "manifest fetch network error: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "manifest fetch decode error: ${e.message}")
            false
        }
    }

    @Serializable
    private data class WhisperManifestRaw(
        val version: Int,
        @SerialName("whispers") val whispers: List<JsonElement>,
    )

    /**
     * The set of [WhisperCategory] values that have at least one
     * placeable (non-retired) [WhisperDefinition]. Empty when the
     * manifest hasn't been fetched yet — caller is expected to gate
     * the placement UI on this being non-empty.
     */
    open fun placeableCategories(): Set<WhisperCategory> {
        val m = _manifest.value ?: return emptySet()
        return m.whispers
            .asSequence()
            .filter { it.isActive }
            .map { it.category }
            .toSet()
    }

    /**
     * Pick a random active [WhisperDefinition] for [category]. Returns
     * `null` if no placeable whisper exists (manifest not loaded, or
     * every entry retired).
     */
    open fun randomWhisper(category: WhisperCategory): WhisperDefinition? {
        val m = _manifest.value ?: return null
        return m.whispers.filter { it.isActive && it.category == category }.randomOrNull()
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
        // iOS parity (Config.swift Whisper.manifestURL): the manifest lives
        // under /audio/whisper/, alongside the .aac files — NOT /whispers/.
        // The old /whispers/manifest.json path 404s, leaving the catalog
        // empty so preview/placement/tap all silently no-op.
        const val MANIFEST_URL = "https://cdn.pilgrimapp.org/audio/whisper/manifest.json"
        const val TAG = "WhisperManifest"
    }
}
