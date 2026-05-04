// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * ML Kit-backed [TextRecognizerClient]. Uses the bundled Latin-script
 * recognizer — sufficient for the contemplative-walk prompt pipeline
 * (English-language signage / receipts). Other-script recognizers can
 * be added behind a separate implementation later.
 *
 * Returns already-flattened individual lines so the analyzer's filter
 * stage doesn't need to walk the block / line / element tree.
 *
 * `Mutex` serializes process calls — ML Kit text-recognition clients
 * aren't safe for concurrent use.
 */
@Singleton
class MlKitTextRecognizerClient @Inject constructor() : TextRecognizerClient {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mutex = Mutex()

    override suspend fun recognize(bitmap: Bitmap): List<String> = mutex.withLock {
        val image = InputImage.fromBitmap(bitmap, /* rotationDegrees = */ 0)
        recognizer.process(image).await()
            .textBlocks
            .flatMap { block -> block.lines.map { it.text } }
    }
}
