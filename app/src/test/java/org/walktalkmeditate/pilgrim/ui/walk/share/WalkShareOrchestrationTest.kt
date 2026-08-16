// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.share.PrepState
import org.walktalkmeditate.pilgrim.data.share.RepairSlot
import org.walktalkmeditate.pilgrim.data.share.SharePayload
import org.walktalkmeditate.pilgrim.data.share.SlotIdentity
import org.walktalkmeditate.pilgrim.data.share.SlotKind
import org.walktalkmeditate.pilgrim.data.share.SlotStatus
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourPhoto
import org.walktalkmeditate.pilgrim.data.share.TourRecordingCandidate
import org.walktalkmeditate.pilgrim.data.share.TourRecordingKind

/**
 * The decision helpers iOS keeps `nonisolated static` for exactly this
 * reason — "Pure identity resolution — no MainActor/instance state — so
 * it's directly unit-testable"
 * (`WalkShareViewModel+ShareOrchestration.swift:307-317@3f9f9e8`).
 * Mirrors `WalkShareInteractiveTests.swift`'s `resolveRetryItems` and
 * `expectedFailureRecords` sections scenario-for-scenario, adapted to
 * Android's uuid-keyed audio identity and file-backed photo artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkShareOrchestrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun existingFile(name: String): File = temp.newFile(name).apply { writeBytes(ByteArray(4)) }

    private fun photo(n: Int, uri: String, ts: Long, file: File) = TourPhoto(
        n = n,
        file = file,
        meta = SharePayload.Photo(lat = 1.0, lon = 2.0, ts = ts, data = null),
        sourceUri = uri,
    )

    private fun candidate(
        id: Int,
        uuid: String = "rec-$id",
        included: Boolean = true,
        unavailableReason: String? = null,
        path: String? = "$id.wav",
    ) = TourRecordingCandidate(
        id = id,
        recordingUuid = uuid,
        startTs = 1_000L + id,
        endTs = 1_060L + id,
        duration = 60.0,
        sizeBytes = 1_000L,
        transcription = null,
        wpm = null,
        autoKind = TourRecordingKind.SPOKEN,
        includeInShare = included,
        fileRelativePath = path,
        unavailableReason = unavailableReason,
    )

    // ---- resolveRepairSlots ---------------------------------------------

    @Test
    fun `an audio slot resolves by recording uuid and uploads under its cached n`() {
        // Port of `testResolveRetryItemsAudioFoundAtShiftedIndex`
        // (`WalkShareInteractiveTests.swift:449-463@3f9f9e8`): the cached
        // failure was slot 3; nothing about the CURRENT candidate order
        // may change that.
        val artifact = existingFile("rec-c.m4a")
        val cached = listOf(RepairSlot(SlotKind.AUDIO, 3, SlotIdentity.Audio("rec-c"), SlotStatus.PENDING))

        val resolution = resolveRepairSlots(cached, mapOf("rec-c" to artifact), emptyList())

        assertTrue(resolution.unresolved.isEmpty())
        assertEquals(3, resolution.audioSlots.single().n)
        assertEquals(artifact, resolution.audioSlots.single().file)
    }

    @Test
    fun `an audio slot whose recording is gone is carried forward, not dropped`() {
        // `testResolveRetryItemsAudioStartTsMismatchGoesToRemaining`
        // (`WalkShareInteractiveTests.swift:434-447@3f9f9e8`).
        val cached = listOf(RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio("rec-gone"), SlotStatus.PENDING))

        val resolution = resolveRepairSlots(cached, mapOf("rec-other" to existingFile("other.m4a")), emptyList())

        assertFalse(resolution.hasUploadable)
        assertEquals(cached, resolution.unresolved)
    }

    @Test
    fun `an audio slot whose artifact vanished between resolution and upload is carried forward`() {
        val missing = File(temp.root, "never-written.m4a")
        val cached = listOf(RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio("rec-a"), SlotStatus.PENDING))

        val resolution = resolveRepairSlots(cached, mapOf("rec-a" to missing), emptyList())

        assertEquals(cached, resolution.unresolved)
    }

    @Test
    fun `a photo slot resolves by identity, never by array position`() {
        // `testResolveRetryItemsMatchesPhotoByIdentityNotIndex`
        // (`WalkShareInteractiveTests.swift:398-412@3f9f9e8`): photo-B's
        // cached slot is n=1 but it now sits at position 1 — an
        // index-based lookup would upload photo-A's bytes under it.
        val fileA = existingFile("a.jpg")
        val fileB = existingFile("b.jpg")
        val cached = listOf(RepairSlot(SlotKind.PHOTO, 1, SlotIdentity.Photo("photo-B", 500L), SlotStatus.PENDING))
        val photos = listOf(photo(1, "photo-A", 100L, fileA), photo(2, "photo-B", 500L, fileB))

        val resolution = resolveRepairSlots(cached, emptyMap(), photos)

        assertTrue(resolution.unresolved.isEmpty())
        assertEquals(1, resolution.photoSlots.single().n)
        assertEquals("must upload photo-B's bytes, not photo-A's", fileB, resolution.photoSlots.single().file)
    }

    @Test
    fun `a photo slot needs BOTH halves of its compound identity to match`() {
        // `testResolveRetryItemsPhotoMissingIdentityGoesToRemaining`
        // (`:414-423@3f9f9e8`) plus the ts half: a same-uri photo whose
        // capture timestamp differs must NOT match.
        val file = existingFile("c.jpg")
        val cached = listOf(RepairSlot(SlotKind.PHOTO, 1, SlotIdentity.Photo("photo-x", 500L), SlotStatus.PENDING))

        assertEquals(
            cached,
            resolveRepairSlots(cached, emptyMap(), listOf(photo(1, "photo-other", 500L, file))).unresolved,
        )
        assertEquals(
            cached,
            resolveRepairSlots(cached, emptyMap(), listOf(photo(1, "photo-x", 999L, file))).unresolved,
        )
    }

    @Test
    fun `a mixed pass splits resolvable and unresolvable slots without losing any`() {
        val artifact = existingFile("mix.m4a")
        val cached = listOf(
            RepairSlot(SlotKind.PHOTO, 2, SlotIdentity.Photo("photo-gone", 1L), SlotStatus.PENDING),
            RepairSlot(SlotKind.AUDIO, 5, SlotIdentity.Audio("rec-live"), SlotStatus.PENDING),
        )

        val resolution = resolveRepairSlots(cached, mapOf("rec-live" to artifact), emptyList())

        assertEquals(1, resolution.audioSlots.size)
        assertEquals(1, resolution.unresolved.size)
        assertEquals(2, resolution.unresolved.single().n)
    }

    // ---- planInteractiveAudioUploads ------------------------------------

    @Test
    fun `audio slots are numbered by the same included-set order tourItems numbers recordings by`() {
        val candidates = listOf(
            candidate(0),
            candidate(1, included = false),
            candidate(2, unavailableReason = TourBuilder.REASON_AUDIO_REMOVED, path = null),
            candidate(3),
        )
        val files = candidates.associate { it.recordingUuid to existingFile("${it.recordingUuid}.m4a") }

        val plan = planInteractiveAudioUploads(candidates, emptySet()) { files[it.recordingUuid] }

        assertEquals(listOf(1, 2), plan.audioSlots.map { it.n })
        assertEquals(
            plan.audioSlots.map { it.n },
            TourBuilder.tourItems(candidates, trimM = 0).tour.recordings.map { it.n },
        )
        assertEquals(
            listOf(SlotIdentity.Audio("rec-0"), SlotIdentity.Audio("rec-3")),
            plan.audioSlots.map { it.identity },
        )
        assertTrue(plan.effectiveExcludedUuids.isEmpty())
    }

    @Test
    fun `a recording whose artifact cannot be produced is excluded from the payload, not silently declared`() {
        // The invariant iOS gets for free from `tourItems`' parallel
        // `files` array: a declared recording ALWAYS has bytes queued.
        val candidates = listOf(candidate(0), candidate(1))
        val onlyFirst = mapOf("rec-0" to existingFile("rec-0.m4a"))

        val plan = planInteractiveAudioUploads(candidates, walkerExcludedUuids = setOf("rec-9")) {
            onlyFirst[it.recordingUuid]
        }

        assertEquals(listOf(1), plan.audioSlots.map { it.n })
        assertEquals(setOf("rec-9", "rec-1"), plan.effectiveExcludedUuids)
    }

    // ---- row availability -------------------------------------------------

    @Test
    fun `a recording with no known size and prep in flight reads as preparing`() {
        val row = recordingAvailability(
            candidate = candidate(0, unavailableReason = TourBuilder.REASON_AUDIO_REMOVED, path = null),
            prepState = null,
            knownSizeBytes = null,
            prepBusy = true,
        )
        assertEquals(RecordingAvailability.Preparing, row)
    }

    @Test
    fun `a failed transcode reads as audio removed, not as a sixth row state`() {
        val row = recordingAvailability(
            candidate = candidate(0, unavailableReason = TourBuilder.REASON_AUDIO_REMOVED, path = null),
            prepState = PrepState.Failed,
            knownSizeBytes = null,
            prepBusy = true,
        )
        assertEquals(RecordingAvailability.AudioRemoved, row)
    }

    @Test
    fun `an over-cap recording keeps its own reason rather than collapsing to audio removed`() {
        val row = recordingAvailability(
            candidate = candidate(0, unavailableReason = TourBuilder.REASON_TOO_LARGE, path = null),
            prepState = PrepState.Ready(99),
            knownSizeBytes = null,
            prepBusy = false,
        )
        assertEquals(RecordingAvailability.TooLargeToCarry, row)
    }

    @Test
    fun `a known size wins over a re-encode in flight so an excluded row stays toggleable`() {
        val row = recordingAvailability(
            candidate = candidate(0),
            prepState = PrepState.Preparing,
            knownSizeBytes = 4_096L,
            prepBusy = true,
        )
        assertEquals(RecordingAvailability.Available(4_096L), row)
    }

    // ---- the two UI-gate booleans ----------------------------------------

    @Test
    fun `the form freezes for the whole in-flight span but dismissal locks only past the POST`() {
        // iOS `isShareInFlight` vs `isDismissLocked`
        // (`WalkShareView.swift:26-48@3f9f9e8`) — the second is a
        // deliberate 2-case subset of the first.
        val inFlight = listOf(
            ShareCardState.PreparingPhotos(1, 2),
            ShareCardState.PhotosDropped(1, 1),
            ShareCardState.Uploading,
            ShareCardState.UploadingMedia(1, 2),
        )
        val settled = listOf(
            ShareCardState.Idle,
            ShareCardState.Success("u"),
            ShareCardState.Partial("u", 1),
            ShareCardState.Error("boom"),
        )

        assertTrue(inFlight.all { isShareInFlight(it) })
        assertTrue(settled.none { isShareInFlight(it) })

        assertEquals(
            listOf(ShareCardState.Uploading, ShareCardState.UploadingMedia(1, 2)),
            inFlight.filter { isDismissLocked(it) },
        )
        assertTrue(settled.none { isDismissLocked(it) })
    }

    @Test
    fun `partial counts as shared — the page is already live`() {
        assertTrue(isSharedState(ShareCardState.Success("u")))
        assertTrue(isSharedState(ShareCardState.Partial("u", 3)))
        assertFalse(isSharedState(ShareCardState.Uploading))
        assertFalse(isSharedState(ShareCardState.Error("e")))
    }

    @Test
    fun `a repair record is current only when it names the cached share`() {
        assertTrue(isRepairRecordCurrent("abc", "abc"))
        assertFalse(isRepairRecordCurrent("abc", "def"))
        assertFalse(isRepairRecordCurrent(null, "abc"))
        assertFalse(isRepairRecordCurrent("abc", null))
    }

    // ---- totals label ------------------------------------------------------

    @Test
    fun `totals label pluralizes recordings and photos independently`() {
        // `testTourTotalsLabelWording` (`WalkShareInteractiveTests.swift:266-289@3f9f9e8`).
        assertEquals(
            "no recordings included",
            tourTotalsLabel(context, candidates = emptyList(), photoCount = 0),
        )

        val one = listOf(candidate(0).copy(sizeBytes = 1_048_576L, duration = 60.0))
        val singular = tourTotalsLabel(context, one, photoCount = 0)
        assertTrue("singular wording must not add a trailing s: $singular", singular.startsWith("1 recording ·"))
        assertFalse(singular.contains("1 recordings"))
        assertEquals("1 recording · 1.0 MB · 1 min", singular)

        assertEquals(
            "20 hi-res photos",
            tourTotalsLabel(context, candidates = emptyList(), photoCount = 20),
        )
        assertEquals(
            "1 hi-res photo",
            tourTotalsLabel(context, candidates = emptyList(), photoCount = 1),
        )
        assertEquals(
            "1 recording · 1.0 MB · 1 min · 2 hi-res photos",
            tourTotalsLabel(context, one, photoCount = 2),
        )
    }

    @Test
    fun `totals label counts only the included, available candidates`() {
        val candidates = listOf(
            candidate(0).copy(sizeBytes = 1_048_576L),
            candidate(1, included = false).copy(sizeBytes = 5_242_880L),
            candidate(2, unavailableReason = TourBuilder.REASON_TOO_LARGE, path = null).copy(sizeBytes = 99L),
        )
        assertEquals("1 recording · 1.0 MB · 1 min", tourTotalsLabel(context, candidates, photoCount = 0))
    }
}
