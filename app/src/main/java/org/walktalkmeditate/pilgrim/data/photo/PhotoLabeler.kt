// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.graphics.Bitmap

/**
 * Single labeled result from [PhotoLabeler.label]. `confidence` is in
 * the closed interval `[0.0, 1.0]` — ML Kit returns `Float`, widened
 * to `Double` at the boundary so Room's REAL column maps cleanly.
 */
data class LabeledResult(
    val text: String,
    val confidence: Double,
)

/**
 * Thin abstraction over the image-labeling model. Lets the runner + VM
 * unit-test against a fake without pulling in ML Kit's JNI + TFLite
 * stack. Implementations must be safe to call from any dispatcher —
 * the ML Kit impl serializes concurrent calls internally via a Mutex
 * (one JNI interpreter shared across workers).
 */
interface PhotoLabeler {
    /**
     * Run the model against [bitmap]. Returns labels sorted by
     * confidence descending (empty when nothing meets the threshold).
     * Callers should take [List.firstOrNull] for the "top label"
     * semantic. Must not recycle [bitmap] — the caller owns it.
     */
    suspend fun label(bitmap: Bitmap): List<LabeledResult>
}
