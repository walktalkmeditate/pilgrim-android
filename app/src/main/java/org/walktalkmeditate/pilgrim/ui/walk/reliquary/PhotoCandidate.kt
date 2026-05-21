// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

/**
 * UI model for a photo presented in the reliquary carousel.
 *
 * iOS parity `PhotoCandidate` (pilgrim-ios
 * `Pilgrim/Scenes/WalkSummary/Reliquary/...`). The reliquary lists
 * BOTH pinned and unpinned candidates — the user explicitly pins
 * the photos they want surfaced on the walk's map / share card,
 * matching iOS's "discover then opt-in" UX rather than Android's
 * earlier auto-pin behavior.
 *
 * Persistence: pinned candidates are mirrored to the
 * [org.walktalkmeditate.pilgrim.data.entity.WalkPhoto] Room entity
 * (with the row id surfaced as [pinnedPhotoId] for the unpin
 * path). Unpinned candidates are ephemeral — re-discovered on each
 * Walk Summary open and discarded when the user navigates away.
 *
 * @property uri MediaStore content URI string (stable across photo edits)
 * @property takenAtMs DATE_TAKEN epoch millis from MediaStore
 * @property capturedLat EXIF GPS latitude (Q+ unredacted via
 *   MediaStore.setRequireOriginal)
 * @property capturedLng EXIF GPS longitude
 * @property isPinned whether this candidate currently exists in
 *   the walk_photos table for this walk
 * @property pinnedPhotoId the WalkPhoto row id when pinned; null
 *   when unpinned. The togglePin path uses this for the unpin
 *   route (which needs the row id, not just the URI).
 */
data class PhotoCandidate(
    val uri: String,
    val takenAtMs: Long?,
    val capturedLat: Double?,
    val capturedLng: Double?,
    val isPinned: Boolean,
    val pinnedPhotoId: Long? = null,
)
