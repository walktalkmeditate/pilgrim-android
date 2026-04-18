// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [WhisperEngine] backed by whisper.cpp via JNI. The model is
 * lazy-loaded on first transcribe so a user who never records voice
 * notes never pays the ~75 MB RAM cost.
 *
 * Singleton-scoped so the loaded model survives across multiple
 * transcriptions; the native heap is reclaimed by the OS when the
 * process exits — there is intentionally no explicit teardown path
 * (see whisper-jni.cpp note).
 */
@Singleton
class WhisperCppEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: WhisperModelInstaller,
) : WhisperEngine {

    private val nativeLock = Any()

    @Volatile
    private var nativeHandle: Long = 0L

    private fun ensureLoaded(): Long {
        synchronized(nativeLock) {
            if (nativeHandle != 0L) return nativeHandle
            val modelPath = try {
                modelInstaller.installIfNeeded()
            } catch (e: Throwable) {
                Log.w(TAG, "model install failed", e)
                throw WhisperError.ModelLoadFailed(e)
            }
            val handle = nativeInit(modelPath.absolutePathString())
            if (handle == 0L) throw WhisperError.ModelLoadFailed()
            nativeHandle = handle
            return handle
        }
    }

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {
            try {
                // whisper.h is explicit: a single whisper_context must
                // not be used by multiple threads concurrently. Hold the
                // monitor across both ensureLoaded (reentrant) and
                // nativeTranscribe so two simultaneous workers can't
                // race on the same native ctx.
                val text = synchronized(nativeLock) {
                    val handle = ensureLoaded()
                    nativeTranscribe(handle, wavPath.absolutePathString())
                } ?: return@withContext Result.failure(WhisperError.InferenceFailed(-1))
                Result.success(TranscriptionResult(text = text.trim(), wordsPerMinute = null))
            } catch (e: WhisperError) {
                Result.failure(e)
            } catch (e: Throwable) {
                Log.w(TAG, "transcribe failed", e)
                Result.failure(WhisperError.InferenceFailed(-1).also { it.initCause(e) })
            }
        }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, wavPath: String): String?

    private companion object {
        const val TAG = "WhisperCppEngine"
        init { System.loadLibrary("pilgrim-whisper") }
    }
}
