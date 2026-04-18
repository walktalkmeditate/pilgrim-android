// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.WalkRepository

/**
 * Best-effort batch orchestrator. Reads pending recordings for a walk,
 * transcribes each via [WhisperEngine], and updates the row using the
 * read-then-`.copy()`-then-`updateVoiceRecording` pattern (per Stage
 * 2-A's full-row @Update convention).
 *
 * Per-recording failures are logged but do NOT abort the batch.
 */
@Singleton
class TranscriptionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val engine: WhisperEngine,
) {
    suspend fun transcribePending(walkId: Long): Result<Int> {
        val pending = try {
            repository.voiceRecordingsFor(walkId).filter { it.transcription == null }
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        val filesRoot = context.filesDir.toPath().toAbsolutePath().normalize()
        var count = 0
        for (recording in pending) {
            if (recording.fileRelativePath.isBlank()) {
                // A blank path resolves to filesDir itself (a directory),
                // which std::ifstream can't read — JNI returns "" and the
                // runner would commit NO_SPEECH_PLACEHOLDER, masking what
                // is really a data-integrity bug. Skip and log instead.
                Log.w(TAG, "skipping recording ${recording.id}: blank fileRelativePath")
                continue
            }
            val absolute = filesRoot.resolve(recording.fileRelativePath).normalize()
            if (!absolute.startsWith(filesRoot)) {
                // Defensive: a malformed `file_relative_path` (absolute or
                // escaping via "..") would let JNI read arbitrary files.
                // Skip it instead of forwarding to whisper.cpp.
                Log.w(TAG, "skipping recording ${recording.id}: path escapes filesDir")
                continue
            }
            val outcome = engine.transcribe(absolute)
            outcome.fold(
                onSuccess = { result ->
                    val noSpeech = result.text.isBlank()
                    val text = if (noSpeech) NO_SPEECH_PLACEHOLDER else result.text
                    val wpm = if (noSpeech) null else computeWpm(text, recording.durationMillis)
                    try {
                        repository.updateVoiceRecording(
                            recording.copy(transcription = text, wordsPerMinute = wpm),
                        )
                        count++
                    } catch (t: Throwable) {
                        Log.w(TAG, "DB update failed for recording ${recording.id}", t)
                    }
                },
                onFailure = { error ->
                    if (error is WhisperError.ModelLoadFailed) {
                        // The engine couldn't load the model — every
                        // remaining recording will fail the same way.
                        // Escalate so WorkManager can back off and retry.
                        Log.w(TAG, "model load failed; aborting batch for retry", error)
                        return Result.failure(error)
                    }
                    Log.w(TAG, "transcribe failed for recording ${recording.id}", error)
                },
            )
        }
        return Result.success(count)
    }

    private fun computeWpm(text: String, durationMillis: Long): Double? {
        if (durationMillis <= 0) return null
        val words = text.trim().split(WORD_SPLIT).count { it.isNotBlank() }
        if (words == 0) return null
        val minutes = durationMillis / 60_000.0
        return (words / minutes).takeIf { it.isFinite() }
    }

    companion object {
        const val NO_SPEECH_PLACEHOLDER = "(no speech detected)"
        private const val TAG = "TranscriptionRunner"
        private val WORD_SPLIT = Regex("\\s+")
    }
}
