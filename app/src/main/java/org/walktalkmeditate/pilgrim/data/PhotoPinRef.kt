// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

/**
 * Batch input for [WalkRepository.pinPhotos] — a picker URI plus the
 * optional capture timestamp the VM read from `DATE_TAKEN` at pick
 * time. Kept lean (no walk id / pin time) so callers can pre-assemble
 * a batch before knowing the transaction timestamp.
 */
data class PhotoPinRef(
    val uri: String,
    val takenAt: Long?,
)
