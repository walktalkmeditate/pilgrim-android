// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.core.threads.CompressionRatio
import org.walktalkmeditate.pilgrim.core.threads.ThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

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
    private val modelStore: WhisperModelStore,
    private val modelDownloadScheduler: WhisperModelDownloadScheduler,
    private val threadsAnalyzer: TranscriptContextAnalyzer,
    private val threadsPreferences: ThreadsPreferencesRepository,
) {
    suspend fun transcribePending(walkId: Long): Result<Int> {
        val pending = try {
            repository.voiceRecordingsFor(walkId).filter { it.transcription == null }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        if (pending.isNotEmpty() && modelStore.readyModelPath() == null) {
            // No usable model on disk (fresh v1.3.0 install pre-delivery,
            // or a D2D/restore artifact). Self-heal instead of spinning:
            // (re-)enqueue the download — KEEP dedupes onto pending work
            // and the scheduler's FAILED/SUCCEEDED gate holds — and
            // return the same ModelLoadFailed the worker maps to retry,
            // so the backoff lands after the model can exist. Genuine
            // load failures of a PRESENT model keep the plain
            // escalation below (U10 spec L1).
            try {
                modelDownloadScheduler.ensureEnqueued()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "model download enqueue failed", t)
            }
            return Result.failure(WhisperError.ModelLoadFailed())
        }
        val filesRoot = context.filesDir.toPath().toAbsolutePath().normalize()
        var count = 0
        // Recordings that actually reached the engine. A row skipped for a
        // data-integrity reason (blank/escaping path) is NOT an attempt, so
        // it must not make an all-skipped batch look like an all-failed one.
        var attempted = 0
        try {
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
                attempted++
                val outcome = engine.transcribeWithSegments(absolute)
                outcome.fold(
                    onSuccess = { result ->
                        val noSpeech = result.text.isBlank()
                        val text = if (noSpeech) VoiceRecording.NO_SPEECH_PLACEHOLDER else result.text
                        val wpm = if (noSpeech) null else computeWpm(text, recording.durationMillis)
                        val persisted = persistWithRetry(recording, text, wpm)
                        if (persisted) count++
                        // Analysis triggers after a successful persist only, and never
                        // for the no-speech placeholder (there is no real transcript to
                        // analyze). Toggle-gated here to skip the segment-flag/language
                        // work entirely when the feature is off; analyzeAndStore itself
                        // re-checks the toggle too (defense in depth, not trust).
                        if (persisted && !noSpeech && threadsPreferences.threadsAfterWalks.value) {
                            analyzeThreadsSafely(recording.id, recording.uuid, result)
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
        } finally {
            // AF33: release the whisper model's native memory after the batch — covers
            // normal completion, the ModelLoadFailed early return, and
            // cancellation. unloadModel is a no-op when nothing was loaded.
            // (A concurrent batch that was blocked on the engine's nativeLock
            // simply reloads on its next transcribe — safe, just a reload cost.)
            // Explicit try/catch (not runCatching) so a CancellationException
            // is re-thrown rather than swallowed.
            try {
                engine.unloadModel()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "unloadModel after batch failed", t)
            }
        }
        // Honest feedback (AF32): work that was attempted but produced zero
        // successes is a failure, not a `success(0)` that reads as
        // "completed". A no-speech recording counts as a success (it commits
        // the placeholder), so this only fires when every *attempted*
        // recording failed to transcribe or persist. Gating on `attempted`
        // (not `pending`) means an all-skipped batch (every row had a
        // blank/escaping path) reports success(0) — those are data-integrity
        // skips already logged per-row, not transcription failures.
        return if (count == 0 && attempted > 0) {
            Log.w(TAG, "all $attempted attempted transcriptions failed")
            Result.failure(AllRecordingsFailedException(attempted))
        } else {
            Result.success(count)
        }
    }

    /**
     * Exactly one retry (two total attempts), no backoff — U2/BEH-58's
     * persistence-retry shape. iOS retries its transcription-text write
     * and its WPM write SEPARATELY, two attempts each; Android's
     * [WalkRepository.updateVoiceRecording] writes both fields in one
     * `@Update`, so one retried call covers both rather than needing two
     * independent retry loops.
     */
    private suspend fun persistWithRetry(recording: VoiceRecording, text: String, wordsPerMinute: Double?): Boolean {
        repeat(PERSIST_ATTEMPTS) { attempt ->
            try {
                repository.updateVoiceRecording(recording.copy(transcription = text, wordsPerMinute = wordsPerMinute))
                return true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "DB update failed for recording ${recording.id} (attempt ${attempt + 1}/$PERSIST_ATTEMPTS)", t)
            }
        }
        return false
    }

    /**
     * Never blocks transcription persist: any failure here (language
     * detection, theme/marker computation, the store write) is logged
     * and swallowed, `CancellationException` re-thrown. The recording's
     * transcription is already durably committed by the time this runs.
     */
    private suspend fun analyzeThreadsSafely(recordingId: Long, uuid: String, result: TranscriptionResult) {
        try {
            // Trimmed to match result.text's own trim (the engine joins
            // segments raw, then trims ONCE at the very ends) — an
            // untrimmed fragment from a leading-space FIRST segment would
            // never be found inside the trimmed transcript, leaving that
            // segment's flag unscrubbed. Empty-after-trim fragments are
            // dropped so a whitespace-only flagged segment never becomes a
            // no-op search that still costs a full transcript scan.
            val flaggedFragments = result.segments.filter { it.isFlagged() }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
            val flaggedRanges = TranscriptContextAnalyzer.flaggedRanges(result.text, flaggedFragments)
            val language = threadsAnalyzer.detectLanguage(result.text)
            // Decision only — never the language code or transcript text.
            val decision = if (language == ENGLISH) "proceeding" else "skipped (non-English)"
            Log.d(TAG, "recording $recordingId: threads analysis $decision")
            threadsAnalyzer.analyzeAndStore(uuid, result.text, flaggedRanges)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "threads analysis failed for recording $recordingId", t)
        }
    }

    /** ASR-quality flag (U2/BEH-56): whisper.cpp's own segment-level
     * `noSpeechProb` is a real signal on Android (unlike WhisperKit 0.16
     * on iOS, which hardcodes it to 0) — this branch is LIVE here, a
     * deliberate parity divergence iOS never exercises (Open question 1). */
    private fun WhisperSegment.isFlagged(): Boolean =
        CompressionRatio.of(text) > COMPRESSION_RATIO_THRESHOLD || noSpeechProb > NO_SPEECH_PROB_THRESHOLD

    private fun computeWpm(text: String, durationMillis: Long): Double? {
        if (durationMillis <= 0) return null
        val words = text.trim().split(WORD_SPLIT).count { it.isNotBlank() }
        if (words == 0) return null
        val minutes = durationMillis / 60_000.0
        return (words / minutes).takeIf { it.isFinite() }
    }

    companion object {
        private const val TAG = "TranscriptionRunner"
        private val WORD_SPLIT = Regex("\\s+")

        // U2/BEH-56 segment flag thresholds, verbatim from iOS.
        private const val COMPRESSION_RATIO_THRESHOLD = 2.4
        private const val NO_SPEECH_PROB_THRESHOLD = 0.6

        private const val ENGLISH = "en"

        // U2/BEH-58: exactly one retry, two total attempts, no backoff.
        private const val PERSIST_ATTEMPTS = 2
    }
}

/**
 * Raised by [TranscriptionRunner.transcribePending] when at least one
 * recording was attempted but EVERY attempt failed to transcribe (or
 * persist). Surfaced as a [Result.failure] so [TranscriptionWorker]
 * reports the work as failed rather than silently succeeded — iOS PR #45
 * AF32 honest-feedback parity. [attempted] is the number of recordings
 * that reached the engine (skipped data-integrity rows are excluded).
 */
internal class AllRecordingsFailedException(val attempted: Int) :
    Exception("all $attempted attempted transcriptions failed")
