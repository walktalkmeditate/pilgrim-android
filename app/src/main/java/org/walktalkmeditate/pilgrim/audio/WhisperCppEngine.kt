// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.util.Log
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore

/**
 * Seam over the whisper.cpp JNI surface so the engine's load-state
 * machine is unit-testable — the native library needs a device.
 */
internal interface WhisperNative {
    fun init(modelPath: String): Long
    fun transcribe(handle: Long, wavPath: String): String?

    /**
     * Additive (U5): a second, parallel decode entry point returning
     * per-segment text/timing/no-speech-probability instead of one
     * joined string. Null means the same "inference failed" outcome as
     * [transcribe] returning null; an empty (zero-length) array means
     * "no speech" (readable WAV, nothing decodable) — the same
     * distinction [transcribe] makes between a null return and an empty
     * string.
     */
    fun transcribeSegments(handle: Long, wavPath: String): Array<WhisperSegment>?
    fun free(handle: Long)
}

/**
 * Production [WhisperNative]. The JNI symbols in `whisper-jni.cpp` are
 * bound to THIS object's name; the library loads on first reference,
 * which no unit test ever makes.
 */
internal object JniWhisperNative : WhisperNative {

    init {
        System.loadLibrary("pilgrim-whisper")
    }

    override fun init(modelPath: String): Long = nativeInit(modelPath)
    override fun transcribe(handle: Long, wavPath: String): String? =
        nativeTranscribe(handle, wavPath)
    override fun transcribeSegments(handle: Long, wavPath: String): Array<WhisperSegment>? =
        nativeTranscribeSegments(handle, wavPath)
    override fun free(handle: Long) = nativeFree(handle)

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, wavPath: String): String?
    private external fun nativeTranscribeSegments(ctx: Long, wavPath: String): Array<WhisperSegment>?
    private external fun nativeFree(ctx: Long)
}

/**
 * Production [WhisperEngine] backed by whisper.cpp via JNI. The model is
 * lazy-loaded on first transcribe so a user who never records voice
 * notes never pays the model's RAM cost, and resolved per batch through
 * [WhisperModelStore.readyModelPath] — verified base preferred, the
 * legacy tiny transitionally during the upgrade window (U10 spec
 * `docs/parity/2026-07-26-port-engine-switch-u10.md`).
 *
 * The loaded state is keyed on the resolved path: when the resolution
 * changes between batches (tiny → base after the verified switch), the
 * stale native context is freed and the new model loaded. Singleton-
 * scoped so the loaded model survives across multiple transcriptions
 * within one batch; [unloadModel] frees the native context after the
 * batch (AF33) so the weights don't stay resident while the user keeps
 * using the app. The next [transcribe] reloads.
 */
@Singleton
class WhisperCppEngine internal constructor(
    private val store: WhisperModelStore,
    private val native: WhisperNative,
) : WhisperEngine {

    @Inject
    constructor(store: WhisperModelStore) : this(store, JniWhisperNative)

    private val nativeLock = Any()

    @Volatile
    private var nativeHandle: Long = 0L

    /** Guarded by [nativeLock]. */
    private var loadedModelPath: Path? = null

    private fun ensureLoaded(modelPath: Path): Long {
        synchronized(nativeLock) {
            val current = nativeHandle
            if (current != 0L && loadedModelPath == modelPath) return current
            if (current != 0L) {
                nativeHandle = 0L
                loadedModelPath = null
                native.free(current)
            }
            val handle = native.init(modelPath.absolutePathString())
            if (handle == 0L) throw WhisperError.ModelLoadFailed()
            nativeHandle = handle
            loadedModelPath = modelPath
            return handle
        }
    }

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {
            // Resolved BEFORE the monitor — suspending under nativeLock
            // is forbidden. A resolve that races the post-switch tiny
            // delete fails native init below → ModelLoadFailed → the
            // worker's backoff retry re-resolves to base (U10 spec L3).
            val modelPath = try {
                store.readyModelPath()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "model resolution failed", t)
                return@withContext Result.failure(WhisperError.ModelLoadFailed(t))
            } ?: return@withContext Result.failure(WhisperError.ModelLoadFailed())
            try {
                // whisper.h is explicit: a single whisper_context must
                // not be used by multiple threads concurrently. Hold the
                // monitor across both ensureLoaded (reentrant) and
                // native.transcribe so two simultaneous workers can't
                // race on the same native ctx.
                val text = synchronized(nativeLock) {
                    val handle = ensureLoaded(modelPath)
                    native.transcribe(handle, wavPath.absolutePathString())
                }
                // The JNI returns nullptr on whisper_full failure; the
                // real `whisper_full` rc is logged in whisper-jni.cpp at
                // WARN level (`PilgrimWhisper rc=...`) but is not threaded
                // back to Kotlin. -1 here is a placeholder for "see
                // logcat". Threading the rc would require a richer JNI
                // signature; revisit if a future stage adds analytics
                // that need to discriminate failure modes.
                    ?: return@withContext Result.failure(WhisperError.InferenceFailed(-1))
                Result.success(TranscriptionResult(text = text.trim(), wordsPerMinute = null))
            } catch (e: WhisperError) {
                Result.failure(e)
            } catch (e: Throwable) {
                Log.w(TAG, "transcribe failed", e)
                Result.failure(WhisperError.InferenceFailed(-1).also { it.initCause(e) })
            }
        }

    /**
     * Additive (U5): parallels [transcribe]'s model-resolution +
     * native-lock dance exactly, but calls [WhisperNative.transcribeSegments]
     * instead. [transcribe] itself is left untouched (existing entry
     * point unchanged) rather than refactored to share this logic, so
     * the two paths duplicate the model-resolution block rather than
     * risking any behavior change to the pinned original.
     */
    override suspend fun transcribeWithSegments(wavPath: Path): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {
            val modelPath = try {
                store.readyModelPath()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "model resolution failed", t)
                return@withContext Result.failure(WhisperError.ModelLoadFailed(t))
            } ?: return@withContext Result.failure(WhisperError.ModelLoadFailed())
            try {
                val segments = synchronized(nativeLock) {
                    val handle = ensureLoaded(modelPath)
                    native.transcribeSegments(handle, wavPath.absolutePathString())
                }
                    ?: return@withContext Result.failure(WhisperError.InferenceFailed(-1))
                val text = segments.joinToString("") { it.text }
                Result.success(
                    TranscriptionResult(
                        text = text.trim(),
                        wordsPerMinute = null,
                        segments = segments.toList(),
                    ),
                )
            } catch (e: WhisperError) {
                Result.failure(e)
            } catch (e: Throwable) {
                Log.w(TAG, "transcribeWithSegments failed", e)
                Result.failure(WhisperError.InferenceFailed(-1).also { it.initCause(e) })
            }
        }

    override fun unloadModel() {
        synchronized(nativeLock) {
            val handle = nativeHandle
            if (handle == 0L) return
            nativeHandle = 0L
            loadedModelPath = null
            native.free(handle)
        }
    }

    private companion object {
        const val TAG = "WhisperCppEngine"
    }
}
