// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.content.Context
import java.io.File
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.PrepState
import org.walktalkmeditate.pilgrim.data.share.RepairSlot
import org.walktalkmeditate.pilgrim.data.share.SlotIdentity
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourPhoto
import org.walktalkmeditate.pilgrim.data.share.TourRecordingCandidate
import org.walktalkmeditate.pilgrim.data.share.UploadSlot

/**
 * The pure, VM-free half of [WalkShareViewModel]'s interactive-share
 * orchestration.
 *
 * iOS splits the same slice into `WalkShareViewModel+ShareOrchestration.swift`
 * so the type body stays under its length ceiling, and marks the two
 * genuinely decision-making helpers `nonisolated static` precisely so
 * they can be tested without a ViewModel:
 * `resolveRetryItems` ("Pure identity resolution — no MainActor/instance
 * state — so it's directly unit-testable",
 * `WalkShareViewModel+ShareOrchestration.swift:307-317@3f9f9e8`) and
 * `expectedFailureRecords` ("Not `private`: unit-tested directly",
 * `:196-198@3f9f9e8`).
 *
 * Kotlin's `internal`/`private` is type-scoped, not file-scoped, so a
 * literal extension-file split would force nearly every one of the VM's
 * backing `MutableStateFlow`s to `internal` for no behavioral gain —
 * the state machine folds into [WalkShareViewModel] instead, and this
 * file takes exactly what iOS made `static`: the decisions, none of the
 * state.
 */

/**
 * One resolved repair pass. [photoSlots]/[audioSlots] are ready to hand
 * to [org.walktalkmeditate.pilgrim.data.share.ShareService.uploadMedia]
 * under their ORIGINAL cached `n`; [unresolved] carries every slot whose
 * identity no longer matches anything current — iOS's `remaining`,
 * "carried forward unchanged, not dropped"
 * (`WalkShareInteractiveTests.swift:422@3f9f9e8`).
 */
internal data class RepairResolution(
    val photoSlots: List<UploadSlot>,
    val audioSlots: List<UploadSlot>,
    val unresolved: List<RepairSlot>,
) {
    val hasUploadable: Boolean get() = photoSlots.isNotEmpty() || audioSlots.isNotEmpty()
}

/**
 * Port of iOS `resolveRetryItems`
 * (`WalkShareViewModel+ShareOrchestration.swift:318-360@3f9f9e8`):
 * matches each still-pending slot to CURRENT data by stable identity,
 * "never by `n` alone: an index that's still 'in bounds' after the
 * underlying candidate set shifted (an export drop, an unpin) can
 * silently point at a DIFFERENT file than the one that failed"
 * (`:311-314@3f9f9e8`). Every resolved slot uploads under its CACHED
 * [RepairSlot.n], never its position in whatever list it was
 * re-resolved from.
 *
 * Both current-data sets are resolved by the caller and passed in, so
 * this stays free of I/O and directly unit-testable: [audioArtifacts]
 * comes from [org.walktalkmeditate.pilgrim.data.share.SharePrepStore.ensureArtifact]
 * (which re-encodes an artifact the cache evicted, closing the port
 * plan's Decision-3 gap that iOS never has — its m4a IS the recording),
 * and [photos] from a re-export of exactly the photos this pass needs.
 *
 * Android needs no equivalent of iOS's "unrecognized kind" branch
 * (`:328-331@3f9f9e8`): `SlotKind` is an exhaustive enum decoded at the
 * store boundary, where an unknown value already fails the record's
 * decode rather than reaching here as a live item.
 */
internal fun resolveRepairSlots(
    pending: List<RepairSlot>,
    audioArtifacts: Map<String, File>,
    photos: List<TourPhoto>,
): RepairResolution {
    val photoSlots = mutableListOf<UploadSlot>()
    val audioSlots = mutableListOf<UploadSlot>()
    val unresolved = mutableListOf<RepairSlot>()

    for (slot in pending) {
        when (val identity = slot.identity) {
            is SlotIdentity.Audio -> {
                val file = audioArtifacts[identity.recordingUuid]
                if (file == null || !file.exists()) {
                    unresolved += slot
                } else {
                    audioSlots += UploadSlot(n = slot.n, file = file, identity = identity)
                }
            }

            is SlotIdentity.Photo -> {
                // BOTH halves of the compound key must match, exactly as
                // iOS ("sourceLocalIdentifier == item.photoLocalID &&
                // meta.ts == item.photoTs", `:348-350@3f9f9e8`).
                val photo = photos.firstOrNull { it.sourceUri == identity.sourceUri && it.meta.ts == identity.ts }
                if (photo == null || !photo.file.exists()) {
                    unresolved += slot
                } else {
                    photoSlots += UploadSlot(n = slot.n, file = photo.file, identity = identity)
                }
            }
        }
    }
    return RepairResolution(photoSlots, audioSlots, unresolved)
}

/**
 * The upload slots and (possibly narrowed) exclusion set for a fresh
 * interactive share.
 *
 * [effectiveExcludedUuids] is the walker's own exclusion set PLUS any
 * included recording whose artifact could not be produced — the payload
 * is built with this set, so `tour.recordings` can only ever declare
 * recordings whose bytes are actually queued. iOS gets that invariant
 * for free (`tourItems` returns `tour.recordings` and `files` as one
 * parallel pair, `TourBuilder.swift@3f9f9e8`); Android's bytes come
 * from a separately-produced transcode artifact, so the two have to be
 * reconciled explicitly before the payload is built rather than after.
 */
internal data class InteractiveUploadPlan(
    val audioSlots: List<UploadSlot>,
    val effectiveExcludedUuids: Set<String>,
)

/**
 * Assembles [InteractiveUploadPlan] over [candidates]' included subset,
 * numbering audio slots 1..N in [TourBuilder.includedCandidates] order —
 * the SAME function [TourBuilder.tourItems] numbers `tour.recordings[].n`
 * from, so a declared recording and its PUT slot can never disagree.
 */
internal fun planInteractiveAudioUploads(
    candidates: List<TourRecordingCandidate>,
    walkerExcludedUuids: Set<String>,
    artifactFor: (candidate: TourRecordingCandidate) -> File?,
): InteractiveUploadPlan {
    val included = TourBuilder.includedCandidates(candidates)
    val resolved = included.mapNotNull { candidate -> artifactFor(candidate)?.let { candidate to it } }
    val dropped = included.map { it.recordingUuid }.toSet() - resolved.map { it.first.recordingUuid }.toSet()
    return InteractiveUploadPlan(
        audioSlots = resolved.mapIndexed { index, (candidate, file) ->
            UploadSlot(n = index + 1, file = file, identity = SlotIdentity.Audio(candidate.recordingUuid))
        },
        effectiveExcludedUuids = walkerExcludedUuids + dropped,
    )
}

/** Photo upload slots, under the dense 1..N `n` [TourPhoto] already assigned itself (U5). */
internal fun planPhotoUploads(photos: List<TourPhoto>): List<UploadSlot> =
    photos.map { UploadSlot(n = it.n, file = it.file, identity = SlotIdentity.Photo(it.sourceUri, it.meta.ts)) }

/**
 * Whether a repair record belongs to [cachedShareId]. A record naming a
 * DIFFERENT share is stale — the walk was re-shared since, so its slots
 * point at a page that is no longer the one this screen shows. iOS
 * expresses the same rule as an unconditional overwrite at both write
 * sites (`cacheFailedMedia(...)` on every interactive attempt,
 * `cacheFailedMedia([])` on every classic one,
 * `WalkShareViewModel+ShareOrchestration.swift:130-131,157-163@3f9f9e8`);
 * Android's record carries its own `shareId`, so a stale one is also
 * detectable on READ — which is what closes the gap iOS leaves when a
 * share attempt dies between its POST and its pre-populate.
 */
internal fun isRepairRecordCurrent(recordShareId: String?, cachedShareId: String?): Boolean =
    recordShareId != null && cachedShareId != null && recordShareId == cachedShareId

/**
 * Port of iOS `tourTotalsLabel`
 * (`WalkShareViewModel.swift:47-59@3f9f9e8`) — the disclosure's
 * one-line summary. Same assembly (recordings clause, then photos
 * clause, joined by " · ", falling back to the empty-state sentence),
 * with the pluralization and number formatting moved into resources.
 */
internal fun tourTotalsLabel(
    context: Context,
    candidates: List<TourRecordingCandidate>,
    photoCount: Int,
): String {
    val totals = TourBuilder.totals(candidates)
    val parts = buildList {
        if (totals.count > 0) {
            add(
                context.resources.getQuantityString(
                    R.plurals.share_interactive_totals_recordings,
                    totals.count,
                    totals.count,
                    // iOS: String(format: "%.1f", bytes / 1_048_576). Locale.US
                    // keeps the decimal separator ASCII and the test suite
                    // deterministic across CI locales (Stage 5-A lesson).
                    String.format(Locale.US, "%.1f", totals.bytes / 1_048_576.0),
                    (totals.seconds / 60).toInt(),
                ),
            )
        }
        if (photoCount > 0) {
            add(context.resources.getQuantityString(R.plurals.share_interactive_totals_photos, photoCount, photoCount))
        }
    }
    return if (parts.isEmpty()) {
        context.getString(R.string.share_interactive_totals_empty)
    } else {
        parts.joinToString(TOTALS_SEPARATOR)
    }
}

/** iOS `parts.joined(separator: " · ")` (`WalkShareViewModel.swift:58@3f9f9e8`). */
private const val TOTALS_SEPARATOR = " · "

/**
 * A recording row's availability.
 *
 * [RecordingAvailability.Preparing] is shown only while the transcoded
 * size is genuinely still unknown. Once a size has landed once, the row
 * stays [RecordingAvailability.Available] even across a walker exclusion
 * (which deletes the artifact per the port plan's Decision 3) and its
 * later re-encode — otherwise excluding a recording would hide its own
 * include control (`rowShowsControls`, UI-24) and strand it excluded
 * forever, a trap iOS cannot have because its candidate's availability
 * is resolved synchronously off disk before any row renders (spec UI-22).
 */
internal fun recordingAvailability(
    candidate: TourRecordingCandidate,
    prepState: PrepState?,
    knownSizeBytes: Long?,
    prepBusy: Boolean,
): RecordingAvailability = when {
    knownSizeBytes != null && candidate.unavailableReason == null ->
        RecordingAvailability.Available(knownSizeBytes)
    prepState is PrepState.Preparing -> RecordingAvailability.Preparing
    prepState == null && prepBusy -> RecordingAvailability.Preparing
    candidate.unavailableReason == TourBuilder.REASON_TOO_LARGE -> RecordingAvailability.TooLargeToCarry
    candidate.unavailableReason != null -> RecordingAvailability.AudioRemoved
    else -> RecordingAvailability.Available(candidate.sizeBytes)
}

/**
 * The four states that freeze the whole form — iOS `isShareInFlight`
 * (`WalkShareView.swift:26-36@3f9f9e8`): "Mid-edit desyncs (toggles,
 * journal, expiry) must stay impossible for the whole in-flight span —
 * including the pre-POST photo-export phase and the dropped-photo
 * consent pause, neither of which has anything server-side yet but both
 * of which are mid-attempt."
 */
internal fun isShareInFlight(state: ShareCardState): Boolean = when (state) {
    is ShareCardState.PreparingPhotos,
    is ShareCardState.PhotosDropped,
    ShareCardState.Uploading,
    is ShareCardState.UploadingMedia,
    -> true
    else -> false
}

/**
 * The two states that lock dismissal — iOS `isDismissLocked`
 * (`WalkShareView.swift:38-48@3f9f9e8`), a deliberate SUBSET of
 * [isShareInFlight]: "`.preparingPhotos` is a local, cancellable export
 * and `.photosDropped` is a pre-POST consent pause, so neither locks the
 * toolbar Cancel or interactive dismiss the way `isShareInFlight` locks
 * the form."
 */
internal fun isDismissLocked(state: ShareCardState): Boolean =
    state == ShareCardState.Uploading || state is ShareCardState.UploadingMedia

/** iOS `isShared` — `.partial` counts the same as `.success` (`WalkShareViewModel.swift:121-129@3f9f9e8`). */
internal fun isSharedState(state: ShareCardState): Boolean =
    state is ShareCardState.Success || state is ShareCardState.Partial

/** The repair slots a pass should still attempt. */
internal fun pendingSlots(slots: List<RepairSlot>): List<RepairSlot> =
    slots.filter { it.status == org.walktalkmeditate.pilgrim.data.share.SlotStatus.PENDING }
