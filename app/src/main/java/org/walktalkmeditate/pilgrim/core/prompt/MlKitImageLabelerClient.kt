// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

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
 * ML Kit-backed [ImageLabelerClient] tuned for the prompt pipeline.
 *
 * Distinct instance from
 * [org.walktalkmeditate.pilgrim.data.photo.MlKitPhotoLabeler]: that
 * client uses 0.6 confidence for the goshuin-grid top-label use case
 * (one strong label per photo), whereas the prompt context wants the
 * top 8 weak-or-strong tags above 0.3 to feed scene context to the
 * LLM. Two different interpreter instances is fine — TFLite startup
 * cost is per-instance but the long-lived Singletons keep both warm.
 *
 * Concurrent `process` calls aren't safe per ML Kit docs, so the
 * [Mutex] serializes them within this instance.
 */
@Singleton
class MlKitImageLabelerClient @Inject constructor() : ImageLabelerClient {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
            .build(),
    )
    private val mutex = Mutex()

    override suspend fun label(bitmap: Bitmap): List<LabeledTag> = mutex.withLock {
        val image = InputImage.fromBitmap(bitmap, /* rotationDegrees = */ 0)
        labeler.process(image).await()
            .map { LabeledTag(text = it.text, confidence = it.confidence) }
    }

    private companion object {
        const val CONFIDENCE_THRESHOLD: Float = 0.3f
    }
}
