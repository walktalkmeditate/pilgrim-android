// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * Fetches the voice-guide manifest from [VoiceGuideConfig.MANIFEST_URL],
 * parses the iOS-shaped JSON, and exposes the resulting
 * [VoiceGuidePack] list via [packs]. Cold-start behavior: load the
 * local cache file synchronously in `init` so UIs observing [packs]
 * see the last-known-good catalog immediately. Call [syncIfNeeded]
 * from a user surface (Stage 5-D picker) to refresh against the CDN.
 *
 * Thread safety: [_packs] and [_isSyncing] are `MutableStateFlow`;
 * concurrent `syncIfNeeded` calls are deduped via an atomic
 * `_isSyncing.compareAndSet` check that runs synchronously before
 * any coroutine is launched. Network I/O hops to `Dispatchers.IO`
 * inside `fetchRemoteManifest`; cache read/write in `syncIfNeeded`
 * also hops to `Dispatchers.IO` because the outer scope runs on
 * `Dispatchers.Default`, which is shared with whisper.cpp
 * transcription. The synchronous `loadLocalManifest()` in `init`
 * runs on the Hilt-injection thread — acceptable only because the
 * file is a few KB; any future growth would warrant moving that
 * read off the main thread.
 *
 * Cache policy: writes the manifest to
 * `filesDir/voice_guide_manifest.json` atomically (write-to-tmp +
 * rename). Only rewrites when the fetched `version` differs from the
 * cached one, so an unchanged remote doesn't churn the filesystem.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5c-voice-guide-manifest-design.md`.
 */
@Singleton
class VoiceGuideManifestService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    @VoiceGuideManifestScope private val scope: CoroutineScope,
    @VoiceGuideManifestUrl private val manifestUrl: String,
) {
    private val _packs = MutableStateFlow<List<VoiceGuidePack>>(emptyList())
    val packs: StateFlow<List<VoiceGuidePack>> = _packs.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val localManifestFile: File by lazy {
        File(context.filesDir, LOCAL_MANIFEST_NAME)
    }

    init {
        loadLocalManifest()
    }

    /** Lookup a pack by id in the currently-cached catalog. */
    fun pack(id: String): VoiceGuidePack? =
        _packs.value.firstOrNull { it.id == id }

    /**
     * Fetch the remote manifest; if the version differs from the
     * local cache (or no cache exists), persist it and publish the
     * new pack list. On network or parse failure, the local cache
     * and in-memory state remain unchanged.
     *
     * Dedupes concurrent calls — if a sync is in flight, subsequent
     * calls no-op until it completes. The pending sync picks up the
     * latest remote state; there's no value to queuing a second
     * identical fetch.
     */
    fun syncIfNeeded() {
        // Atomic CAS so rapid same-thread calls dedupe correctly. A
        // plain `if (value) return` check leaks: three calls in a row
        // all observe `false` before any launched coroutine runs and
        // flips it to `true`.
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val remote = fetchRemoteManifest() ?: return@launch
                // Disk I/O hops explicitly to Dispatchers.IO — the
                // outer launch is on Dispatchers.Default, which is the
                // fixed CPU-bound pool shared with whisper.cpp
                // transcription (Stage 2-D). Blocking a Default thread
                // on a file write would contend with that pool AND
                // trip StrictMode in debug builds.
                val localVersion = withContext(Dispatchers.IO) { readLocalVersion() }
                if (remote.version != localVersion) {
                    withContext(Dispatchers.IO) { saveLocalManifest(remote) }
                }
                _packs.value = remote.packs
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun fetchRemoteManifest(): VoiceGuideManifest? =
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
                    json.decodeFromString<VoiceGuideManifest>(body)
                }
            } catch (ce: CancellationException) {
                // Must not be swallowed — propagating cancellation is
                // load-bearing for structured concurrency. Without
                // this, scope cancellation mid-fetch would be logged
                // as a network error and the caller would silently
                // continue with `null`, leaving the finally block to
                // reset `_isSyncing` on an already-cancelled scope.
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "manifest fetch failed", t)
                null
            }
        }

    private fun loadLocalManifest() {
        if (!localManifestFile.exists()) return
        try {
            val text = localManifestFile.readText()
            val manifest = json.decodeFromString<VoiceGuideManifest>(text)
            _packs.value = manifest.packs
        } catch (t: Throwable) {
            // Corrupt cache (partial write interrupted by crash, manual
            // file edit, schema mismatch): treat as absent. The next
            // syncIfNeeded rewrites the file atomically.
            Log.w(TAG, "failed to load local manifest; treating as absent", t)
        }
    }

    private fun readLocalVersion(): String? {
        if (!localManifestFile.exists()) return null
        return try {
            val text = localManifestFile.readText()
            json.decodeFromString<VoiceGuideManifest>(text).version
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveLocalManifest(manifest: VoiceGuideManifest) {
        // Atomic write: write-to-tmp + rename. Protects against a
        // partial write (app kill / power loss mid-write) leaving a
        // corrupt manifest on disk.
        val tmp = File(localManifestFile.parentFile, "$LOCAL_MANIFEST_NAME.tmp")
        try {
            tmp.writeText(json.encodeToString(manifest))
            if (!tmp.renameTo(localManifestFile)) {
                // Rare — same-filesystem rename on Android's internal
                // storage is typically atomic. Fall back to REPLACE.
                Files.move(
                    tmp.toPath(),
                    localManifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save local manifest; in-memory state retains it", t)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "VoiceGuideManifest"
        const val LOCAL_MANIFEST_NAME = "voice_guide_manifest.json"
    }
}
