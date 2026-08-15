// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/** Stage 8-A: successful share response from the Cloudflare Worker. */
data class ShareResult(val url: String, val id: String)

/**
 * Outcome of a [ShareService.uploadMedia] batch. iOS never models this as
 * its own value type — `WalkShareViewModel.ShareState.partial(url:failedCount:)`
 * (`WalkShareViewModel.swift@3f9f9e8`) carries the equivalent information
 * as one case of a UI-layer state enum, combining [ShareResult.url] (from
 * the earlier POST) with a failure count computed from `uploadAllMedia`'s
 * return value at the call site
 * (`WalkShareViewModel+ShareOrchestration.swift:153-155@3f9f9e8`). U6
 * collapses that into one self-contained value here so U8 receives a
 * complete outcome without re-threading the url through its own state
 * machine — [ShareService.uploadMedia] accepts the share's url purely to
 * carry it through to this result; it has no operational use for it.
 *
 * [failedPhotoCount]/[failedAudioCount] are broken out per kind (iOS's
 * `failedCount` is a single combined number) since [ShareRepairStore]'s
 * record is naturally per-kind and splitting costs nothing.
 *
 * The link is ALWAYS valid here — [ShareService.uploadMedia] is only ever
 * called after the share POST already succeeded, so even a 100%-failed
 * media batch still carries a usable [url] (mirrors iOS's `.partial`
 * revealing the link despite failures,
 * `WalkShareViewModel+ShareOrchestration.swift:153-155@3f9f9e8`).
 */
data class MediaUploadResult(
    val url: String,
    val totalCount: Int,
    val failedPhotoCount: Int,
    val failedAudioCount: Int,
) {
    val failedCount: Int get() = failedPhotoCount + failedAudioCount

    /**
     * True when this batch left slots a repair pass could target. Scope
     * note: this is the MECHANICAL signal ("is there an outstanding
     * [ShareRepairStore] record"), not iOS's STRONGER `repairUnavailable`
     * ("we already tried resolving every failure against current
     * candidates and none verified" —
     * `WalkShareViewModel+ShareOrchestration.swift:275-282@3f9f9e8`,
     * `resolveRetryItems`). That resolution needs domain lists (current
     * `VoiceRecording`/`TourPhoto` candidates) U6 doesn't own — U8 layers
     * it on top of [ShareRepairStore]'s persisted identities.
     */
    val repairable: Boolean get() = failedCount > 0
}
