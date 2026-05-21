// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.AndroidPilgrimPhotoEmbedder

/**
 * Encodes a pinned reliquary photo's `content://` URI into a base64
 * JPEG string for the Walk Share payload. Interface so the share VM
 * can be unit-tested without real MediaStore bytes. iOS analogue:
 * `WalkShareViewModel.loadSharePhoto`.
 */
interface SharePhotoEncoder {
    /** Background-thread only. Null when the URI is unreadable / decode fails. */
    fun encodeBase64(uriString: String): String?
}

/**
 * Delegates to [AndroidPilgrimPhotoEmbedder], which already owns the
 * proven content-URI → ≤600px aspect-fit JPEG pipeline used by the
 * `.pilgrim` exporter. Sharing that pipeline keeps a single resize /
 * compress code path.
 */
@Singleton
class AndroidSharePhotoEncoder @Inject constructor(
    private val embedder: AndroidPilgrimPhotoEmbedder,
) : SharePhotoEncoder {
    override fun encodeBase64(uriString: String): String? = embedder.encodeBase64(uriString)
}
