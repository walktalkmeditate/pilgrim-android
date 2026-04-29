// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import java.io.File

/**
 * Outcome of [PilgrimPackageBuilder.build]. Carries the ZIP file
 * + count of photos that couldn't be embedded (PHAsset / URI gone,
 * resize failed, encode bytes over 150 KB ceiling, no nearby route
 * sample for GPS derivation).
 */
data class PilgrimPackageBuildResult(
    val file: File,
    val skippedPhotoCount: Int,
)
