// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.graphics.Bitmap

/**
 * Test fake for [PhotoLabeler]. Default: returns one label "Object"
 * at confidence 0.9. Callers can swap [nextResult] per invocation, or
 * set [throwOnLabel] to simulate labeler failure.
 */
class FakePhotoLabeler(
    var nextResult: List<LabeledResult> = listOf(LabeledResult("Object", 0.9)),
    var throwOnLabel: Throwable? = null,
) : PhotoLabeler {
    val calls: MutableList<Bitmap> = mutableListOf()

    override suspend fun label(bitmap: Bitmap): List<LabeledResult> {
        calls += bitmap
        throwOnLabel?.let { throw it }
        return nextResult
    }
}
