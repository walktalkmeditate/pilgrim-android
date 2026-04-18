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
    suspend fun transcribePending(walkId: Long): Result<Int> = runCatching {
        val pending = repository.voiceRecordingsFor(walkId).filter { it.transcription == null }
        var count = 0
        for (recording in pending) {
            val absolute = context.filesDir.toPath().resolve(recording.fileRelativePath)
            engine.transcribe(absolute).fold(
                onSuccess = { result ->
                    val text = if (result.text.isBlank()) NO_SPEECH_PLACEHOLDER else result.text
                    val wpm = computeWpm(text, recording.durationMillis)
                    repository.updateVoiceRecording(
                        recording.copy(transcription = text, wordsPerMinute = wpm),
                    )
                    count++
                },
                onFailure = {
                    Log.w(TAG, "transcribe failed for recording ${recording.id}", it)
                },
            )
        }
        count
    }

    private fun computeWpm(text: String, durationMillis: Long): Double? {
        if (durationMillis <= 0) return null
        if (text == NO_SPEECH_PLACEHOLDER) return null
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
