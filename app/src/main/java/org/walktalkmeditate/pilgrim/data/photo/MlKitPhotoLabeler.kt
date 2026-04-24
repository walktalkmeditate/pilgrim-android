// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * ML Kit-backed [PhotoLabeler] using the bundled 447-class generic
 * labeler. `@Singleton` because:
 *  - `ImageLabeling.getClient(...)` initializes a JNI-backed TFLite
 *    interpreter; construct-per-call would burn ~10–50 ms per photo.
 *  - Concurrent `process(...)` calls on one interpreter aren't safe
 *    per ML Kit docs — the [Mutex] serializes them. A per-walk worker
 *    is unlikely to race another, but a rapid finish/rescheduling
 *    sequence + startup sweep could.
 *
 * Confidence threshold 0.6 balances the bundled model's noise floor
 * (~0.5) against losing legitimate weak-but-correct labels (~0.7).
 * Adjust only with a downstream-consumer reason.
 */
@Singleton
class MlKitPhotoLabeler @Inject constructor() : PhotoLabeler {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
            .build(),
    )
    private val mutex = Mutex()

    override suspend fun label(bitmap: Bitmap): List<LabeledResult> = mutex.withLock {
        val image = InputImage.fromBitmap(bitmap, /* rotationDegrees = */ 0)
        labeler.process(image).await()
            .map { LabeledResult(text = it.text, confidence = it.confidence.toDouble()) }
            .sortedByDescending { it.confidence }
    }

    companion object {
        const val CONFIDENCE_THRESHOLD: Float = 0.6f
    }
}
