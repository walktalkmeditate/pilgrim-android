// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the unified audio manifest from [AudioConfig.MANIFEST_URL]
 * (contains both bells and soundscapes tagged by [AudioAsset.type]),
 * parses the iOS-shape JSON, and exposes the resulting
 * [AudioAsset] list via [assets]. Cold-start behavior: load the
 * local cache file asynchronously in `init` so the Hilt-injection
 * thread (often Main) doesn't block on disk I/O — matches Stage
 * 5-D's deferred M2 fix for `VoiceGuideManifestService`. UIs
 * observing [assets] see `emptyList()` first, then the cached
 * catalog once the init-launched load completes.
 *
 * Thread safety: [_assets] and [_isSyncing] are `MutableStateFlow`;
 * concurrent `syncIfNeeded` calls are deduped via an atomic
 * `_isSyncing.compareAndSet` check that runs synchronously before
 * any coroutine is launched (Stage 5-C pattern).
 *
 * Cancellation: every `catch (Throwable)` in suspend paths is
 * preceded by a `catch (CancellationException) { throw ce }` so
 * scope cancellation propagates correctly (Stage 5-C pattern).
 *
 * Cache policy: writes the manifest to
 * `filesDir/audio_manifest.json` atomically (write-to-tmp +
 * rename). Only rewrites when the fetched `version` differs from
 * the cached one.
 *
 * Consumers that need a synchronous lookup right after app start
 * (notably [org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadWorker]
 * on a WorkManager-rescheduled boot) should
 * `.await()` [initialLoad] before calling [asset].
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5f-soundscape-playback-design.md`.
 */
@Singleton
class AudioManifestService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    @AudioManifestScope private val scope: CoroutineScope,
    @AudioManifestUrl private val manifestUrl: String,
) {
    private val _assets = MutableStateFlow<List<AudioAsset>>(emptyList())
    val assets: StateFlow<List<AudioAsset>> = _assets.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val localManifestFile: File by lazy {
        File(context.filesDir, LOCAL_MANIFEST_NAME)
    }

    /**
     * Completes when the init-time cache load finishes (regardless
     * of whether a cache existed). Consumers that need to
     * synchronously look up an asset by id right after app start
     * — notably a rescheduled `SoundscapeDownloadWorker` — should
     * `.await()` this before calling [asset].
     */
    private val _initialLoad = CompletableDeferred<Unit>()
    val initialLoad: Deferred<Unit> get() = _initialLoad

    init {
        // Async load — constructor is often called on the
        // Hilt-injection thread (Main for @HiltViewModel consumers).
        // `_assets` starts empty and flips to the cached value on
        // first emission. Subscribers to [assets] handle the
        // empty-first-emission case.
        scope.launch(Dispatchers.IO) {
            try {
                loadLocalManifestFromDisk()
            } finally {
                _initialLoad.complete(Unit)
            }
        }
    }

    /** Lookup an asset by id in the currently-cached catalog. */
    fun asset(id: String): AudioAsset? =
        _assets.value.firstOrNull { it.id == id }

    /**
     * Returns soundscape-typed assets in the current catalog.
     * Convenience helper — bells are consumed separately (Stage
     * 5-B bundles them in-APK and doesn't use this list today).
     */
    fun soundscapes(): List<AudioAsset> =
        _assets.value.filter { it.type == AudioAssetType.SOUNDSCAPE }

    /**
     * Fetch the remote manifest; if the version differs from the
     * local cache, persist it and publish the new asset list. On
     * network or parse failure, local cache and in-memory state
     * remain unchanged. Dedupes concurrent calls via CAS.
     */
    fun syncIfNeeded() {
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val remote = fetchRemoteManifest() ?: return@launch
                val localVersion = withContext(Dispatchers.IO) { readLocalVersion() }
                if (remote.version != localVersion) {
                    withContext(Dispatchers.IO) { saveLocalManifest(remote) }
                }
                _assets.value = remote.assets
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun fetchRemoteManifest(): AudioManifest? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(manifestUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "manifest fetch non-2xx: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.w(TAG, "manifest body empty")
                        return@use null
                    }
                    json.decodeFromString<AudioManifest>(body)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "manifest fetch failed", t)
                null
            }
        }

    private fun loadLocalManifestFromDisk() {
        if (!localManifestFile.exists()) return
        try {
            val text = localManifestFile.readText()
            val manifest = json.decodeFromString<AudioManifest>(text)
            _assets.value = manifest.assets
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Corrupt cache: treat as absent; next syncIfNeeded rewrites.
            Log.w(TAG, "failed to load local manifest; treating as absent", t)
        }
    }

    private fun readLocalVersion(): String? {
        if (!localManifestFile.exists()) return null
        return try {
            val text = localManifestFile.readText()
            json.decodeFromString<AudioManifest>(text).version
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveLocalManifest(manifest: AudioManifest) {
        val tmp = File(localManifestFile.parentFile, "$LOCAL_MANIFEST_NAME.tmp")
        try {
            tmp.writeText(json.encodeToString(manifest))
            if (!tmp.renameTo(localManifestFile)) {
                Files.move(
                    tmp.toPath(),
                    localManifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (ce: CancellationException) {
            tmp.delete()
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save local manifest; in-memory state retains it", t)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "AudioManifest"
        const val LOCAL_MANIFEST_NAME = "audio_manifest.json"
    }
}
