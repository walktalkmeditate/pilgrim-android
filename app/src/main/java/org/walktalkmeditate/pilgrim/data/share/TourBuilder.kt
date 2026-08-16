// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioConfig
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

/**
 * Port of iOS `TourRecordingCandidate` (`TourBuilder.swift:5-20`), plus
 * one Android-original field.
 *
 * [recordingUuid] has no iOS counterpart: iOS's candidate carries the
 * playable `fileURL` directly and matches a repair slot back to its
 * source by truncated `startTs`
 * (`WalkShareViewModel+ShareOrchestration.swift:340@3f9f9e8`). Android
 * needs a stable key for four jobs the iOS type never has to do — the
 * walker's exclusion set, [SharePrepStore]'s per-recording
 * cancel/artifact path, the [SlotIdentity.Audio] repair identity, and
 * kind overrides on a *derived* (rather than stored-and-mutated)
 * candidate list — and
 * [org.walktalkmeditate.pilgrim.data.entity.VoiceRecording.uuid] is the
 * Room-unique key all four already agree on (see [SlotIdentity]'s KDoc
 * for why that is a strict upgrade over a timestamp).
 */
data class TourRecordingCandidate(
    val id: Int,
    val recordingUuid: String,
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

    /**
     * The two `unavailableReason` values (`TourBuilder.swift:56-63@3f9f9e8`),
     * named so the UI layer can map them to its closed
     * `RecordingAvailability` type by identity rather than by
     * re-typing the literals a second time.
     */
    const val REASON_AUDIO_REMOVED = "audio removed"
    const val REASON_TOO_LARGE = "too large to carry"

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
        val text = transcription ?: return TourRecordingKind.SPOKEN
        return if (whitespaceSeparatedWordCount(text) < 8) TourRecordingKind.AMBIENT else TourRecordingKind.SPOKEN
    }

    /**
     * Counts runs of non-whitespace characters, mirroring Swift's
     * `text.split(whereSeparator: \.isWhitespace).count` exactly —
     * including its Unicode-aware notion of whitespace (`Char
     * .isWhitespace()`, not the ASCII-only `\s` regex class).
     */
    private fun whitespaceSeparatedWordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
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
     *
     * [kindOverrides] is the walker's spoken/ambient flip, keyed by
     * [VoiceRecording.uuid]. iOS mutates `kindOverride` in place on its
     * stored candidate array (`flipKind`,
     * `WalkShareViewModel.swift:236-241@3f9f9e8`); Android's candidate
     * list is derived on every emission, so the choice rides in here
     * instead and is normalized back to null when it matches the
     * candidate's own `autoKind` — the same "never store a redundant
     * explicit override" rule iOS applies
     * (`WalkShareViewModel.swift:240@3f9f9e8`). Applying it inside this
     * one function is what keeps the UI's rows and
     * [SharePayloadBuilder]'s own derivation from drifting apart.
     */
    fun candidates(
        recordings: List<VoiceRecording>,
        artifacts: Map<String, RecordingArtifact> = emptyMap(),
        excludedUuids: Set<String> = emptySet(),
        kindOverrides: Map<String, TourRecordingKind> = emptyMap(),
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
                !fileExists || sizeBytes == null || sizeBytes <= 0L -> REASON_AUDIO_REMOVED
                sizeBytes > MAX_FILE_BYTES -> REASON_TOO_LARGE
                else -> null
            }

            val autoKind = classify(rec.transcription)
            TourRecordingCandidate(
                id = index,
                recordingUuid = rec.uuid,
                startTs = startTs,
                endTs = endTs,
                duration = rec.durationMillis / MILLIS_PER_SECOND.toDouble(),
                sizeBytes = sizeBytes ?: 0L,
                transcription = rec.transcription,
                wpm = rec.wordsPerMinute,
                autoKind = autoKind,
                includeInShare = unavailableReason == null && rec.uuid !in excludedUuids,
                kindOverride = kindOverrides[rec.uuid]?.takeIf { it != autoKind },
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
     * The included-and-carryable subset, in the order that assigns
     * every downstream 1-based `n`. iOS gets this for free — its
     * `tourItems` returns a `files` array parallel to
     * `tour.recordings`, so a slot's declared `n` and its uploaded
     * bytes are the same list position by construction
     * (`TourBuilder.swift@3f9f9e8`). Android's uploaded bytes live in a
     * different place entirely ([SharePrepStore.artifactFile], keyed by
     * recording uuid rather than list position), so the numbering rule
     * has to be a shared function instead of a shared array — this one
     * — called by both [tourItems] and the upload-slot assembly in
     * `WalkShareOrchestration`.
     */
    fun includedCandidates(candidates: List<TourRecordingCandidate>): List<TourRecordingCandidate> =
        candidates.filter { it.includeInShare && it.unavailableReason == null && it.fileRelativePath != null }

    /**
     * Fold-in (iOS PR #61/#62): resolves the walker's selected
     * soundscape to the public CDN URL the app's own
     * [org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadWorker]
     * downloads it with — `<base>/<type>/<id>.aac` — NOT
     * [AudioAsset.r2Key], whose bucket-relative path already contains
     * the `audio/` prefix and would double it (iOS PR #62 fixed a live
     * 404 from that exact mistake). A null [selectedId] means silence
     * and resolves to null; an id absent from [assets] — a retired
     * asset, or [assets] simply empty because the manifest hasn't
     * loaded yet (Android's flat asset list stands in for iOS's
     * nullable `AudioManifest?` "no manifest" case) — also resolves to
     * null, never a dead link.
     *
     * iOS `TourBuilder.soundscapeUrl(selectedId:manifest:)`
     * (`TourBuilder.swift:96-110@2ee1185`).
     */
    fun soundscapeUrl(selectedId: String?, assets: List<AudioAsset>): String? {
        if (selectedId == null) return null
        val asset = assets.firstOrNull { it.id == selectedId && it.type == AudioAssetType.SOUNDSCAPE } ?: return null
        return AudioConfig.BASE_URL.trimEnd('/') + "/${asset.type}/${asset.id}.aac"
    }

    /**
     * Collapses the included, available candidates into the wire
     * [SharePayload.Tour] plus a parallel file list for the (later
     * unit's) upload step. `n` is a fresh 1-based renumbering over only
     * the included set — structurally decoupled from `candidate.id`.
     * `transcription` is deliberately never wired through: transcripts
     * never leave the device. [soundscapeUrl] defaults to null (a
     * classic-shaped call site never sends one); the interactive
     * builder passes the already-resolved [soundscapeUrl] result
     * through — iOS `tourItems(candidates:trimM:soundscapeUrl:)`
     * (`TourBuilder.swift:112@2ee1185`).
     */
    fun tourItems(candidates: List<TourRecordingCandidate>, trimM: Int, soundscapeUrl: String? = null): TourItemsResult {
        val included = includedCandidates(candidates)
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
        return TourItemsResult(
            SharePayload.Tour(recordings = recordings, trimM = trimM, soundscapeUrl = soundscapeUrl),
            files,
        )
    }
}
