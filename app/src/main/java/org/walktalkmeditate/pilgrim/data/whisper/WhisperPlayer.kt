// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

/**
 * iOS parity `WhisperPlayer.swift@db4196e`. Plays whisper audio files
 * fetched from `cdn.pilgrimapp.org/audio/whisper/<audioFileName>.aac`
 * and cached at `filesDir/whispers/<audioFileName>.aac`. Survives
 * across walks; cache cleared only on uninstall (matches iOS
 * `applicationSupportDirectory` semantics).
 *
 * Two playback channels (matches iOS — separate `AVAudioPlayer`
 * instances):
 *  - `play(definition)` — main channel (volume 0.8). Used by proximity
 *    auto-play + tap-on-pin + placement-success.
 *  - `preview(definition)` — preview channel (volume 0.6). Used by the
 *    WhisperPlacementSheet per-row play/stop button. Stops main
 *    channel via `stopPlay()` first.
 *
 * Concurrency: each channel serializes via its own Mutex. A second
 * `play()` while a play is in flight (e.g., rapid proximity entry)
 * stops the prior MediaPlayer + cancels its download Job. Network
 * failures fail silently (iOS parity — no user banner for whisper
 * audio failures).
 *
 * Cache eviction: NONE. Mirror of iOS — grows unbounded. Acceptable
 * because audio files are small (3-9s AAC, ~30-80KB each) and the
 * whisper catalog is bounded at ~35 entries.
 *
 * `isPlaying` StateFlow tracks the PREVIEW channel only — the
 * WhisperPlacementSheet UI gates its stop-button on this. Main-channel
 * playback (proximity / tap) is fire-and-forget from the UI's view.
 */
@Singleton
open class WhisperPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val soundsPreferences: SoundsPreferencesRepository,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playMutex = Mutex()
    private val previewMutex = Mutex()
    private val downloadMutex = Mutex()

    @Volatile private var playPlayer: MediaPlayer? = null
    @Volatile private var previewPlayer: MediaPlayer? = null
    @Volatile private var playJob: Job? = null
    @Volatile private var previewJob: Job? = null
    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var focusHolders: Int = 0

    private val _isPlaying = MutableStateFlow(false)
    open val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Play [definition] at [PLAY_VOLUME] (0.8). Fetches from CDN +
     * caches to disk on first hit; replays from cache thereafter.
     * No-op when `soundsEnabled` is false.
     */
    open fun play(definition: WhisperDefinition) {
        if (!soundsPreferences.soundsEnabled.value) return
        playJob?.cancel()
        playJob = scope.launch {
            playMutex.withLock {
                val file = ensureCached(definition.audioFileName) ?: return@withLock
                stopPlay()
                startMediaPlayer(file, PLAY_VOLUME) { player ->
                    playPlayer = player
                }
            }
        }
    }

    /**
     * Preview [definition] at [PREVIEW_VOLUME] (0.6). Used by the
     * placement sheet's per-row play/stop. Stops both channels first
     * — only one preview active at a time.
     */
    open fun preview(definition: WhisperDefinition) {
        if (!soundsPreferences.soundsEnabled.value) return
        previewJob?.cancel()
        previewJob = scope.launch {
            previewMutex.withLock {
                val file = ensureCached(definition.audioFileName) ?: return@withLock
                stopPreview()
                _isPlaying.value = true
                startMediaPlayer(file, PREVIEW_VOLUME) { player ->
                    previewPlayer = player
                }
            }
        }
    }

    /**
     * Stop both channels. Called by [WhisperPlacementSheet.onDismiss]
     * + before a fresh placement-event auto-play.
     */
    open fun stop() {
        playJob?.cancel()
        previewJob?.cancel()
        stopPlay()
        stopPreview()
    }

    private fun stopPlay() {
        synchronized(this) {
            val wasPlaying = playPlayer != null
            playPlayer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            playPlayer = null
            if (wasPlaying) releaseFocusHolder()
        }
    }

    private fun stopPreview() {
        synchronized(this) {
            val wasPlaying = previewPlayer != null
            previewPlayer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            previewPlayer = null
            _isPlaying.value = false
            if (wasPlaying) releaseFocusHolder()
        }
    }

    /**
     * Refcounted transient duck-focus. Each active MediaPlayer holds
     * one ref; the OS-level focus request fires on 0→1 and abandons
     * on N→0. iOS uses `.mixWithOthers` instead — Android doesn't
     * have an equivalent that auto-ducks background music, so we use
     * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` to get the same UX.
     */
    private fun acquireFocusHolder() {
        synchronized(this) {
            if (focusHolders == 0) requestDuckFocus()
            focusHolders += 1
        }
    }
    private fun releaseFocusHolder() {
        synchronized(this) {
            focusHolders -= 1
            if (focusHolders <= 0) {
                focusHolders = 0
                abandonDuckFocus()
            }
        }
    }
    private fun requestDuckFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ).setAudioAttributes(audioAttrs).build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }
    private fun abandonDuckFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private suspend fun ensureCached(audioFileName: String): File? {
        val dir = File(context.filesDir, CACHE_DIR_NAME).also { it.mkdirs() }
        val target = File(dir, "$audioFileName.aac")
        if (target.exists() && target.length() > 0L) return target
        // Serialize concurrent downloads of the SAME file via the mutex.
        // Different files can download in parallel (each call holds its
        // own Job + serializes only against same-file races).
        return downloadMutex.withLock {
            if (target.exists() && target.length() > 0L) return@withLock target
            val url = "$CDN_BASE_URL/$audioFileName.aac"
            val request = Request.Builder().url(url).get().build()
            try {
                withContext(Dispatchers.IO) {
                    httpClient.newCall(request).awaitResponse().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "whisper fetch HTTP ${response.code} for $audioFileName")
                            return@withContext null
                        }
                        val tmp = File(dir, "$audioFileName.tmp")
                        response.body.byteStream().use { input ->
                            tmp.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        // Truncation guard — iOS checks Content-Length.
                        val expectedLen = response.body.contentLength()
                        if (expectedLen > 0L && tmp.length() != expectedLen) {
                            Log.w(TAG, "truncated download: ${tmp.length()} != $expectedLen")
                            tmp.delete()
                            return@withContext null
                        }
                        if (!tmp.renameTo(target)) {
                            // Fallback if renameTo fails (e.g., target
                            // appeared between exists-check and rename).
                            tmp.delete()
                            return@withContext if (target.exists()) target else null
                        }
                        target
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "whisper fetch IO: ${e.message}")
                null
            }
        }
    }

    private fun startMediaPlayer(
        file: File,
        volume: Float,
        bindPlayer: (MediaPlayer) -> Unit,
    ) {
        val player = MediaPlayer().apply {
            setAudioAttributes(audioAttrs)
            setVolume(volume, volume)
            setOnCompletionListener { mp ->
                runCatching { mp.release() }
                if (mp === previewPlayer) {
                    previewPlayer = null
                    _isPlaying.value = false
                }
                if (mp === playPlayer) {
                    playPlayer = null
                }
                releaseFocusHolder()
            }
            setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                runCatching { mp.release() }
                if (mp === previewPlayer) {
                    previewPlayer = null
                    _isPlaying.value = false
                }
                if (mp === playPlayer) playPlayer = null
                releaseFocusHolder()
                true
            }
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            acquireFocusHolder()
            player.start()
            bindPlayer(player)
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer start failed: ${e.message}")
            runCatching { player.release() }
            _isPlaying.value = false
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, _, _ -> runCatching { response.close() } }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

    private companion object {
        const val TAG = "WhisperPlayer"
        const val CDN_BASE_URL = "https://cdn.pilgrimapp.org/audio/whisper"
        const val CACHE_DIR_NAME = "whispers"
        const val PLAY_VOLUME = 0.8f
        const val PREVIEW_VOLUME = 0.6f
    }
}
