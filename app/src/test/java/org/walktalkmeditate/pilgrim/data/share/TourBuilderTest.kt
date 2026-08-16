// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Mirrors `UnitTests/TourBuilderTests.swift` scenario-for-scenario (pin
 * `3f9f9e8`), adapted for Android's inputs: [VoiceRecording] entities +
 * caller-supplied [RecordingArtifact] info instead of iOS's synchronous
 * `FileManager` disk reads (the real transcode/artifact store lands in
 * a later unit).
 */
class TourBuilderTest {

    private fun recording(
        walkId: Long = 1L,
        startTimestamp: Long = 1_000_000L,
        endTimestamp: Long = 1_060_000L,
        fileRelativePath: String = "recordings/test.wav",
        transcription: String? = "Test transcription",
        wordsPerMinute: Double? = null,
    ): VoiceRecording = VoiceRecording(
        walkId = walkId,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        durationMillis = endTimestamp - startTimestamp,
        fileRelativePath = fileRelativePath,
        transcription = transcription,
        wordsPerMinute = wordsPerMinute,
    )

    private fun candidate(
        id: Int,
        bytes: Long = 1_000_000L,
        seconds: Double = 60.0,
        included: Boolean = true,
        kind: TourRecordingKind = TourRecordingKind.SPOKEN,
    ): TourRecordingCandidate = TourRecordingCandidate(
        id = id,
        recordingUuid = "rec-$id",
        startTs = 1000L + id * 100L,
        endTs = 1050L + id * 100L,
        duration = seconds,
        sizeBytes = bytes,
        transcription = null,
        wpm = null,
        autoKind = kind,
        includeInShare = included,
        kindOverride = null,
        fileRelativePath = "$id.m4a",
        unavailableReason = null,
    )

    // MARK: - classify()

    @Test
    fun `classify with no transcription is spoken`() {
        assertEquals(TourRecordingKind.SPOKEN, TourBuilder.classify(transcription = null))
    }

    @Test
    fun `classify with few words is ambient`() {
        assertEquals(TourRecordingKind.AMBIENT, TourBuilder.classify(transcription = "wind and birds"))
    }

    @Test
    fun `classify with slow contemplative speech is spoken`() {
        // 25 wpm over a 5-minute talk is a real pattern on real walks —
        // sparse words must not demote a deliberate talk to ambience.
        val words = List(120) { "word" }.joinToString(" ")
        assertEquals(TourRecordingKind.SPOKEN, TourBuilder.classify(transcription = words))
    }

    @Test
    fun `classify with real speech is spoken`() {
        val words = List(20) { "word" }.joinToString(" ")
        assertEquals(TourRecordingKind.SPOKEN, TourBuilder.classify(transcription = words))
    }

    @Test
    fun `classify with empty transcription is ambient`() {
        assertEquals(TourRecordingKind.AMBIENT, TourBuilder.classify(transcription = ""))
    }

    @Test
    fun `classify with whitespace-only transcription is ambient`() {
        assertEquals(TourRecordingKind.AMBIENT, TourBuilder.classify(transcription = "  \n "))
    }

    // MARK: - tourItems()

    @Test
    fun `tourItems renumbers densely after excluding a candidate`() {
        val candidates = listOf(candidate(id = 0), candidate(id = 1, included = false), candidate(id = 2))
        val result = TourBuilder.tourItems(candidates, trimM = 150)
        assertEquals(listOf(1, 2), result.tour.recordings.map { it.n })
        assertEquals(1200L, result.tour.recordings[1].startTs)
        assertEquals(150, result.tour.trimM)
        assertEquals(listOf("0.m4a", "2.m4a"), result.files)
    }

    @Test
    fun `tourItems uses kindOverride over autoKind`() {
        val flipped = candidate(id = 0, kind = TourRecordingKind.SPOKEN).copy(kindOverride = TourRecordingKind.AMBIENT)
        val result = TourBuilder.tourItems(listOf(flipped), trimM = 0)
        assertEquals("ambient", result.tour.recordings[0].kind)
    }

    @Test
    fun `tourItems recordings and files always align in count and order`() {
        val urlless = candidate(id = 1).copy(fileRelativePath = null)
        val result = TourBuilder.tourItems(listOf(candidate(id = 0), urlless, candidate(id = 2)), trimM = 0)
        assertEquals(result.tour.recordings.size, result.files.size)
        assertEquals(listOf("0.m4a", "2.m4a"), result.files)
    }

    @Test
    fun `tourItems always nulls out transcription`() {
        val withTranscript = TourRecordingCandidate(
            id = 0, recordingUuid = "rec-0", startTs = 1000L, endTs = 1060L, duration = 60.0, sizeBytes = 1_000_000L,
            transcription = "some real speech", wpm = 120.0, autoKind = TourRecordingKind.SPOKEN,
            includeInShare = true, kindOverride = null, fileRelativePath = "0.m4a", unavailableReason = null,
        )
        val result = TourBuilder.tourItems(listOf(withTranscript), trimM = 0)
        assertNull(
            "transcripts never leave the device — the page renders none of them",
            result.tour.recordings[0].transcription,
        )
    }

    @Test
    fun `tourItems carries a given soundscapeUrl and defaults to null`() {
        // Mirrors iOS testTourItems_carriesSoundscapeUrl (TourBuilderTests.swift:80-86@2ee1185).
        val withUrl = TourBuilder.tourItems(
            listOf(candidate(id = 0)),
            trimM = 0,
            soundscapeUrl = "https://cdn.pilgrimapp.org/audio/soundscape/stream.aac",
        )
        assertEquals("https://cdn.pilgrimapp.org/audio/soundscape/stream.aac", withUrl.tour.soundscapeUrl)

        val bare = TourBuilder.tourItems(listOf(candidate(id = 0)), trimM = 0)
        assertNull(bare.tour.soundscapeUrl)
    }

    @Test
    fun `unavailable candidates never enter the tour`() {
        val removed = TourRecordingCandidate(
            id = 1, recordingUuid = "rec-1", startTs = 1100L, endTs = 1150L, duration = 50.0, sizeBytes = 0L,
            transcription = "kept transcript", wpm = null, autoKind = TourRecordingKind.SPOKEN,
            includeInShare = false, kindOverride = null, fileRelativePath = null, unavailableReason = "audio removed",
        )
        val result = TourBuilder.tourItems(listOf(candidate(id = 0), removed), trimM = 0)
        assertEquals(1, result.tour.recordings.size)
        assertEquals(1, result.files.size)
    }

    // MARK: - soundscapeUrl() (fold-in, iOS PR #61/#62)

    private fun soundscapeAsset(
        id: String = "stream-1",
        type: String = AudioAssetType.SOUNDSCAPE,
    ): AudioAsset = AudioAsset(
        id = id,
        type = type,
        name = id,
        displayName = id,
        durationSec = 300.0,
        r2Key = "soundscape/$id.m4a",
        fileSizeBytes = 1_000_000L,
    )

    @Test
    fun `soundscapeUrl resolves through the manifest using the base-type-id formula, not r2Key`() {
        // Mirrors iOS testSoundscapeUrl_resolvesThroughManifest
        // (TourBuilderTests.swift:62-78@2ee1185) — the formula match is
        // asserted against the literal URL, exactly as iOS's own test
        // does, so a future accidental switch to r2Key (PR #62's bug)
        // fails here too.
        val assets = listOf(soundscapeAsset(id = "stream-1"))
        assertEquals(
            "https://cdn.pilgrimapp.org/audio/soundscape/stream-1.aac",
            TourBuilder.soundscapeUrl(selectedId = "stream-1", assets = assets),
        )
    }

    @Test
    fun `soundscapeUrl is null when the walker sits in silence`() {
        val assets = listOf(soundscapeAsset(id = "stream-1"))
        assertNull(TourBuilder.soundscapeUrl(selectedId = null, assets = assets))
    }

    @Test
    fun `soundscapeUrl is null for a retired id absent from the manifest`() {
        val assets = listOf(soundscapeAsset(id = "stream-1"))
        assertNull(
            "a retired id must not become a dead link",
            TourBuilder.soundscapeUrl(selectedId = "retired-id", assets = assets),
        )
    }

    @Test
    fun `soundscapeUrl is null when the manifest is unavailable`() {
        // Android's manifest surface is a flat (possibly empty) asset
        // list rather than iOS's nullable AudioManifest? — an empty
        // list is the "no manifest yet" case.
        assertNull(TourBuilder.soundscapeUrl(selectedId = "stream-1", assets = emptyList()))
    }

    @Test
    fun `soundscapeUrl ignores a same-id asset of a different type`() {
        // iOS filters through manifest.soundscapes before matching id —
        // an id collision with a bell must not resolve.
        val assets = listOf(soundscapeAsset(id = "shared-id", type = AudioAssetType.BELL))
        assertNull(TourBuilder.soundscapeUrl(selectedId = "shared-id", assets = assets))
    }

    // MARK: - totals() / validationError()

    @Test
    fun `totals sums only included available candidates`() {
        val candidates = listOf(
            candidate(id = 0, bytes = 1_000L, seconds = 10.0),
            candidate(id = 1, bytes = 2_000L, seconds = 20.0, included = false),
            candidate(id = 2, bytes = 3_000L, seconds = 30.0),
        )
        val totals = TourBuilder.totals(candidates)
        assertEquals(2, totals.count)
        assertEquals(4_000L, totals.bytes)
        assertEquals(40.0, totals.seconds, 0.0)
    }

    @Test
    fun `validationError fires over 12 included recordings`() {
        val candidates = (0 until 13).map { candidate(id = it) }
        assertNotNull(TourBuilder.validationError(candidates))
        assertNull(TourBuilder.validationError(candidates.take(12)))
    }

    @Test
    fun `validationError fires on total bytes and total seconds caps`() {
        val heavy = (0 until 5).map { candidate(id = it, bytes = 14_000_000L) } // 70MB
        assertNotNull(TourBuilder.validationError(heavy))
        val long = (0 until 7).map { candidate(id = it, seconds = 1000.0) } // 7000s > 6480
        assertNotNull(TourBuilder.validationError(long))
        val contemplative = (0 until 6).map { candidate(id = it, seconds = 1000.0) } // 6000s fits in 108 min
        assertNull(TourBuilder.validationError(contemplative))
    }

    @Test
    fun `validationError ignores excluded recordings in the totals`() {
        val candidates = (0 until 13).map { candidate(id = it, included = it < 12) }
        assertNull(TourBuilder.validationError(candidates))
    }

    // MARK: - candidates()

    @Test
    fun `candidates marks an available recording included with real artifact size`() {
        val rec = recording(fileRelativePath = "recordings/a.wav")
        val artifacts = mapOf(rec.uuid to RecordingArtifact(sizeBytes = 42_000L, fileExists = true))

        val candidates = TourBuilder.candidates(listOf(rec), artifacts)

        assertEquals(1, candidates.size)
        val found = candidates.first()
        assertNull(found.unavailableReason)
        assertTrue(found.includeInShare)
        assertEquals(42_000L, found.sizeBytes)
        assertEquals("recordings/a.wav", found.fileRelativePath)
    }

    @Test
    fun `candidates marks a recording with no artifact info as audio removed`() {
        val rec = recording(fileRelativePath = "recordings/missing.wav")

        // No entry supplied in the artifacts map for this uuid.
        val candidates = TourBuilder.candidates(listOf(rec))

        assertEquals(1, candidates.size)
        assertEquals("audio removed", candidates.first().unavailableReason)
        assertFalse(candidates.first().includeInShare)
        assertNull(candidates.first().fileRelativePath)
    }

    @Test
    fun `candidates marks an oversized artifact too large to carry`() {
        val rec = recording(fileRelativePath = "recordings/big.wav")
        val artifacts = mapOf(rec.uuid to RecordingArtifact(sizeBytes = 16 * 1024 * 1024L, fileExists = true))

        val candidates = TourBuilder.candidates(listOf(rec), artifacts)

        assertEquals("too large to carry", candidates.first().unavailableReason)
        assertFalse(candidates.first().includeInShare)
        // Still just one unavailable row — not enough on its own to trip the aggregate error.
        assertNull(TourBuilder.validationError(candidates))
    }

    @Test
    fun `exactly the max file size is fine, one byte over is not`() {
        val atLimit = recording(fileRelativePath = "recordings/at-limit.wav")
        val overLimit = recording(fileRelativePath = "recordings/over-limit.wav")
        val artifacts = mapOf(
            atLimit.uuid to RecordingArtifact(sizeBytes = TourBuilder.MAX_FILE_BYTES, fileExists = true),
            overLimit.uuid to RecordingArtifact(sizeBytes = TourBuilder.MAX_FILE_BYTES + 1, fileExists = true),
        )

        val candidates = TourBuilder.candidates(listOf(atLimit, overLimit), artifacts)

        assertNull(candidates.first { it.fileRelativePath?.contains("at-limit") == true }.unavailableReason)
        assertEquals("too large to carry", candidates.first { it.unavailableReason != null }.unavailableReason)
    }

    @Test
    fun `candidates excludes a sub-second blip recording entirely`() {
        val start = 1_700_000_000_000L
        val rec = recording(startTimestamp = start, endTimestamp = start + 400L) // 0.4s

        val candidates = TourBuilder.candidates(listOf(rec))

        assertTrue(
            "a recording whose start/end truncate to the same second must not appear at all — not even unavailable",
            candidates.isEmpty(),
        )
    }

    @Test
    fun `candidates come back sorted by start date regardless of storage order`() {
        val early = recording(startTimestamp = 1_700_000_000_000L, endTimestamp = 1_700_000_030_000L)
        val late = recording(startTimestamp = 1_700_000_600_000L, endTimestamp = 1_700_000_630_000L)
        // Supplied out of chronological order.
        val candidates = TourBuilder.candidates(listOf(late, early))

        assertEquals(candidates.map { it.startTs }.sorted(), candidates.map { it.startTs })
        assertEquals(early.startTimestamp / 1000L, candidates.first().startTs)
        assertEquals(late.startTimestamp / 1000L, candidates.last().startTs)
    }

    @Test
    fun `candidates excludes a recording listed in excludedUuids`() {
        val kept = recording(startTimestamp = 1_000_000L, endTimestamp = 1_060_000L, fileRelativePath = "recordings/kept.wav")
        val excluded = recording(
            startTimestamp = 1_100_000L, endTimestamp = 1_160_000L, fileRelativePath = "recordings/excluded.wav",
        )
        val artifacts = mapOf(
            kept.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true),
            excluded.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true),
        )

        val candidates = TourBuilder.candidates(listOf(kept, excluded), artifacts, excludedUuids = setOf(excluded.uuid))

        val excludedCandidate = candidates.first { it.startTs == excluded.startTimestamp / 1000L }
        assertFalse(excludedCandidate.includeInShare)
        assertNull("exclusion is a user choice, not an unavailability reason", excludedCandidate.unavailableReason)

        val result = TourBuilder.tourItems(candidates, trimM = 0)
        assertEquals(1, result.tour.recordings.size)
    }

    // MARK: - U8: identity + kind overrides

    @Test
    fun `candidates carry the source recording uuid`() {
        // U8 needs a row-to-recording mapping for exclusion, prep
        // cancellation, artifact paths, and the repair record's
        // `SlotIdentity.Audio(recordingUuid)`. iOS never needs this
        // (its candidate carries the m4a `fileURL` and matches repairs
        // by `startTs`, `WalkShareViewModel+ShareOrchestration.swift:340@3f9f9e8`);
        // Android's Room uuid is the stronger identity — see
        // `SlotIdentity`'s KDoc.
        val rec = recording()
        val result = TourBuilder.candidates(
            recordings = listOf(rec),
            artifacts = mapOf(rec.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true)),
        )
        assertEquals(rec.uuid, result.single().recordingUuid)
    }

    @Test
    fun `a kind override by recording uuid wins over the auto classification`() {
        // iOS stores the override on the mutable candidate struct
        // (`flipKind`, `WalkShareViewModel.swift:236-241@3f9f9e8`);
        // Android's candidate list is derived, so the override rides in
        // keyed by uuid and is applied here — one function, so the UI
        // rows and `SharePayloadBuilder`'s own derivation cannot drift.
        val rec = recording(transcription = null)
        val artifacts = mapOf(rec.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true))

        val auto = TourBuilder.candidates(listOf(rec), artifacts).single()
        assertEquals(TourRecordingKind.SPOKEN, auto.effectiveKind)
        assertNull(auto.kindOverride)

        val flipped = TourBuilder.candidates(
            listOf(rec),
            artifacts,
            kindOverrides = mapOf(rec.uuid to TourRecordingKind.AMBIENT),
        ).single()
        assertEquals(TourRecordingKind.AMBIENT, flipped.effectiveKind)
        assertEquals(TourRecordingKind.AMBIENT, flipped.kindOverride)
    }

    @Test
    fun `an override matching the auto kind normalizes back to null`() {
        // iOS `flipKind`: "kindOverride = flipped == autoKind ? nil : flipped"
        // (`WalkShareViewModel.swift:240@3f9f9e8`).
        val rec = recording(transcription = null)
        val candidate = TourBuilder.candidates(
            listOf(rec),
            mapOf(rec.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true)),
            kindOverrides = mapOf(rec.uuid to TourRecordingKind.SPOKEN),
        ).single()
        assertNull("a redundant override must not be stored", candidate.kindOverride)
        assertEquals(TourRecordingKind.SPOKEN, candidate.effectiveKind)
    }

    @Test
    fun `includedCandidates is the same filter tourItems uses`() {
        // U8 numbers audio upload slots from this list; `tourItems`
        // numbers `tour.recordings[].n` from it too. One function, so
        // slot `n` can never drift from the declared recording `n`
        // (iOS gets this for free — `tourItems` returns the parallel
        // `files` array, `TourBuilder.swift@3f9f9e8`).
        val candidates = listOf(
            candidate(id = 0),
            candidate(id = 1, included = false),
            candidate(id = 2).copy(unavailableReason = "audio removed", fileRelativePath = null),
            candidate(id = 3),
        )
        val included = TourBuilder.includedCandidates(candidates)
        assertEquals(listOf(0, 3), included.map { it.id })
        assertEquals(
            included.map { it.startTs },
            TourBuilder.tourItems(candidates, trimM = 0).tour.recordings.map { it.startTs },
        )
    }
}
