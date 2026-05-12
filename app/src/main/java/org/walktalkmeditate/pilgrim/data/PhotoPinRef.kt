// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

/**
 * Batch input for [WalkRepository.pinPhotos] — a picker URI plus
 * pre-extracted metadata (capture timestamp + EXIF GPS coords). Kept
 * lean (no walk id / pin time) so callers can pre-assemble a batch
 * before knowing the transaction timestamp.
 */
data class PhotoPinRef(
    val uri: String,
    val takenAt: Long?,
    val capturedLat: Double? = null,
    val capturedLng: Double? = null,
)
