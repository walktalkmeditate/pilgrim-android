// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * ML Kit-backed [FaceDetectorClient]. Uses `PERFORMANCE_MODE_FAST` —
 * the prompt pipeline only needs a count, not landmarks / contours /
 * classifications, so the accurate mode's extra cost would be wasted.
 *
 * `Mutex` serializes process calls — ML Kit detection clients aren't
 * safe for concurrent use.
 */
@Singleton
class MlKitFaceDetectorClient @Inject constructor() : FaceDetectorClient {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build(),
    )
    private val mutex = Mutex()

    override suspend fun detect(bitmap: Bitmap): Int = mutex.withLock {
        val image = InputImage.fromBitmap(bitmap, /* rotationDegrees = */ 0)
        detector.process(image).await().size
    }
}
