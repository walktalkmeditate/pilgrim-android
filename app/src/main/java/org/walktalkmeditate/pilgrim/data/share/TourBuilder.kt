// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/** Port of iOS `TourRecordingKind` (`TourBuilder.swift:3`). Wire value is the lowercase name. */
enum class TourRecordingKind(internal val wireValue: String) {
    SPOKEN("spoken"),
    AMBIENT("ambient"),
}

/**
 * A caller-supplied artifact lookup for one recording. iOS resolves
 * this synchronously off disk inside `TourBuilder.candidates(for:)`
 * (`FileManager.attributesOfItem`); Android's transcode/artifact store
 * lands in a later unit, so this unit takes size + existence as plain
 * inputs. A missing map entry (no artifact known yet) reads the same
 * as `fileExists = false` — "audio removed".
 */
data class RecordingArtifact(
    val sizeBytes: Long?,
    val fileExists: Boolean,
)

/** Port of iOS `TourRecordingCandidate` (`TourBuilder.swift:5-20`). */
data class TourRecordingCandidate(
    val id: Int,
    val startTs: Long,
    val endTs: Long,
    val duration: Double,
    val sizeBytes: Long,
    val transcription: String?,
    val wpm: Double?,
    val autoKind: TourRecordingKind,
    val includeInShare: Boolean,
    val kindOverride: TourRecordingKind? = null,
    val fileRelativePath: String? = null,
    val unavailableReason: String? = null,
) {
    val effectiveKind: TourRecordingKind get() = kindOverride ?: autoKind
}

data class TourTotals(val count: Int, val bytes: Long, val seconds: Double)

data class TourItemsResult(val tour: SharePayload.Tour, val files: List<String>)

/**
 * Port of iOS `TourBuilder` (`Pilgrim/Models/Share/TourBuilder.swift`,
 * pin `3f9f9e8`): builds interactive-share tour recording candidates
 * from a walk's voice recordings, classifies each as spoken/ambient,
 * enforces the aggregate caps, and collapses the included set into the
 * wire [SharePayload.Tour].
 */
internal object TourBuilder {

    const val MAX_RECORDINGS = 12
    const val MAX_FILE_BYTES = 15L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 60L * 1024 * 1024

    // 108 minutes — iOS PR #60 raised this from 45 min; the pin (3f9f9e8) carries 6480.
    const val MAX_TOTAL_SECONDS = 6480.0

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * A deliberate recording is presumed to be a voice: only a
     * transcription that reads as non-speech (too few words) files the
     * recording as ambience. The walker can override either way. No
     * words-per-minute gate: contemplative talks — a thought, then a
     * long silence — measure ~25 wpm on real walks, and slow speech is
     * still speech.
     */
    fun classify(transcription: String?): TourRecordingKind {
        val text = transcription?.trim() ?: return TourRecordingKind.SPOKEN
        val wordCount = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return if (wordCount < 8) TourRecordingKind.AMBIENT else TourRecordingKind.SPOKEN
    }

    /**
     * Sorts [recordings] by start time and resolves each into a
     * candidate. A recording with no stored file path, or whose
     * truncated end/start timestamps collide (a sub-second blip — the
     * worker rejects the whole POST on `end_ts <= start_ts`), is
     * excluded entirely rather than marked unavailable.
     *
     * [excludedUuids] mirrors a walker's per-recording exclusion choice
     * — distinct from unavailability, it still sets `includeInShare =
     * false` but leaves `unavailableReason` null.
     */
    fun candidates(
        recordings: List<VoiceRecording>,
        artifacts: Map<String, RecordingArtifact> = emptyMap(),
        excludedUuids: Set<String> = emptySet(),
    ): List<TourRecordingCandidate> {
        val sorted = recordings.sortedBy { it.startTimestamp }
        return sorted.mapIndexedNotNull { index, rec ->
            if (rec.fileRelativePath.isEmpty()) return@mapIndexedNotNull null
            val startTs = rec.startTimestamp / MILLIS_PER_SECOND
            val endTs = rec.endTimestamp / MILLIS_PER_SECOND
            if (endTs <= startTs) return@mapIndexedNotNull null

            val artifact = artifacts[rec.uuid]
            val sizeBytes = artifact?.sizeBytes
            val fileExists = artifact?.fileExists == true
            val unavailableReason = when {
                !fileExists || sizeBytes == null || sizeBytes <= 0L -> "audio removed"
                sizeBytes > MAX_FILE_BYTES -> "too large to carry"
                else -> null
            }

            TourRecordingCandidate(
                id = index,
                startTs = startTs,
                endTs = endTs,
                duration = rec.durationMillis / MILLIS_PER_SECOND.toDouble(),
                sizeBytes = sizeBytes ?: 0L,
                transcription = rec.transcription,
                wpm = rec.wordsPerMinute,
                autoKind = classify(rec.transcription),
                includeInShare = unavailableReason == null && rec.uuid !in excludedUuids,
                kindOverride = null,
                fileRelativePath = if (unavailableReason == null) rec.fileRelativePath else null,
                unavailableReason = unavailableReason,
            )
        }
    }

    fun totals(candidates: List<TourRecordingCandidate>): TourTotals {
        val included = candidates.filter { it.includeInShare && it.unavailableReason == null }
        return TourTotals(
            count = included.size,
            bytes = included.sumOf { it.sizeBytes },
            seconds = included.sumOf { it.duration },
        )
    }

    fun validationError(candidates: List<TourRecordingCandidate>): String? {
        val (count, bytes, seconds) = totals(candidates)
        if (count > MAX_RECORDINGS) {
            return "A walk page carries at most $MAX_RECORDINGS recordings — leave some out."
        }
        if (bytes > MAX_TOTAL_BYTES) {
            return "Recordings total ${bytes / 1_048_576} MB — the page carries at most 60 MB."
        }
        if (seconds > MAX_TOTAL_SECONDS) {
            return "Recordings total ${(seconds / 60).toInt()} minutes — the page carries at most " +
                "${(MAX_TOTAL_SECONDS / 60).toInt()}."
        }
        return null
    }

    /**
     * Collapses the included, available candidates into the wire
     * [SharePayload.Tour] plus a parallel file list for the (later
     * unit's) upload step. `n` is a fresh 1-based renumbering over only
     * the included set — structurally decoupled from `candidate.id`.
     * `transcription` is deliberately never wired through: transcripts
     * never leave the device.
     */
    fun tourItems(candidates: List<TourRecordingCandidate>, trimM: Int): TourItemsResult {
        val included = candidates.filter {
            it.includeInShare && it.unavailableReason == null && it.fileRelativePath != null
        }
        val recordings = included.mapIndexed { index, c ->
            SharePayload.TourRecording(
                n = index + 1,
                startTs = c.startTs,
                endTs = c.endTs,
                duration = c.duration,
                kind = c.effectiveKind.wireValue,
                transcription = null,
                wpm = c.wpm,
                sizeBytes = c.sizeBytes,
            )
        }
        val files = included.mapNotNull { it.fileRelativePath }
        return TourItemsResult(SharePayload.Tour(recordings = recordings, trimM = trimM), files)
    }
}
