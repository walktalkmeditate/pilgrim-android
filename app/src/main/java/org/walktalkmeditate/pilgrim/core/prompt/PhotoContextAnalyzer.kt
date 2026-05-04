// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.data.photo.BitmapLoader

/**
 * Single-tag result returned by [ImageLabelerClient]. Mirrors the shape
 * returned by ML Kit's `ImageLabel` (text + confidence float in `[0, 1]`)
 * but without the ML Kit dependency on the test classpath.
 */
data class LabeledTag(val text: String, val confidence: Float)

/**
 * Narrow interface around ML Kit's image-labeling client so unit tests
 * can fake the pipeline without bringing the GMS / TFLite stack in.
 *
 * Implementations are responsible for serializing concurrent calls
 * (ML Kit's interpreters aren't thread-safe). See
 * [MlKitImageLabelerClient].
 */
interface ImageLabelerClient {
    suspend fun label(bitmap: Bitmap): List<LabeledTag>
}

/**
 * Narrow interface around ML Kit's text-recognition client. Returns
 * already-flattened individual line strings (caller doesn't need the
 * block / line / element hierarchy).
 */
interface TextRecognizerClient {
    suspend fun recognize(bitmap: Bitmap): List<String>
}

/**
 * Narrow interface around ML Kit's face-detection client. Returns the
 * count of detected faces only — the prompt doesn't surface bounding
 * boxes or landmarks.
 */
interface FaceDetectorClient {
    suspend fun detect(bitmap: Bitmap): Int
}

/**
 * Builds a [PhotoContext] for a `content://` photo URI by running three
 * ML Kit pipelines (image labeling + text recognition + face detection)
 * plus a Bitmap pixel-average dominant-color extraction. Results are
 * cached in DataStore Preferences keyed by the URI; subsequent calls
 * hit the cache and skip the ML pipelines entirely.
 *
 * Failure handling: any non-CancellationException raised during the
 * cache-miss pipeline returns a sentinel-empty [PhotoContext] WITHOUT
 * writing it to the cache. A transient ML Kit / decoder failure stays
 * retryable on the next analyze call rather than poisoning the cache
 * forever. CancellationException propagates so structured-concurrency
 * teardown still works.
 *
 * Cache invalidation: never. Photo content is immutable per URI in
 * MediaStore — re-edits produce a new URI.
 */
@Singleton
class PhotoContextAnalyzer @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val bitmapLoader: BitmapLoader,
    private val imageLabeler: ImageLabelerClient,
    private val textRecognizer: TextRecognizerClient,
    private val faceDetector: FaceDetectorClient,
) {
    private val mutex = Mutex()

    suspend fun analyze(uri: Uri): PhotoContext {
        val cacheKey = stringPreferencesKey(cacheKeyFor(uri))

        readCached(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                readCached(cacheKey)?.let { return@withLock it }

                val computed = runPipeline(uri)
                if (computed != null) {
                    writeCached(cacheKey, computed)
                    computed
                } else {
                    EMPTY
                }
            }
        }
    }

    private suspend fun readCached(key: androidx.datastore.preferences.core.Preferences.Key<String>): PhotoContext? {
        val raw = try {
            dataStore.data.first()[key]
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "datastore read failed; treating as cache miss", t)
            return null
        } ?: return null

        return try {
            json.decodeFromString(PhotoContext.serializer(), raw)
        } catch (t: SerializationException) {
            Log.w(TAG, "cached PhotoContext decode failed; treating as cache miss", t)
            null
        }
    }

    private suspend fun writeCached(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        value: PhotoContext,
    ) {
        try {
            dataStore.edit { prefs ->
                prefs[key] = json.encodeToString(PhotoContext.serializer(), value)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "datastore write failed; cache will miss next time", t)
        }
    }

    /**
     * Runs the four-stage analysis. Returns null on any non-CE failure so
     * the caller can substitute the sentinel-empty fallback without
     * caching it.
     *
     * Bitmap lifetime mirrors [org.walktalkmeditate.pilgrim.data.photo.PhotoAnalysisRunner]:
     * we own the bitmap from BitmapLoader and only recycle once we've
     * confirmed every ML Kit call has settled. CE is re-thrown without
     * recycling — ML Kit's task may still be reading the native pixel
     * buffer from a background thread on cancellation.
     */
    private suspend fun runPipeline(uri: Uri): PhotoContext? {
        val bitmap: Bitmap = try {
            bitmapLoader.load(uri)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "bitmap load failed for $uri; returning fallback", t)
            return null
        } ?: return null

        var settled = false
        try {
            val tags = labelTags(bitmap)
            val detectedText = recognizeText(bitmap)
            val people = detectFaces(bitmap)
            val outdoor = tags.any { it in OUTDOOR_TAG_SET }
            val dominantColor = computeDominantColor(bitmap)
            settled = true
            return PhotoContext(
                tags = tags,
                detectedText = detectedText,
                people = people,
                outdoor = outdoor,
                dominantColor = dominantColor,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "PhotoContext pipeline failed for $uri; returning fallback (uncached)", t)
            settled = true
            return null
        } finally {
            if (settled) bitmap.recycle()
        }
    }

    private suspend fun labelTags(bitmap: Bitmap): List<String> = imageLabeler.label(bitmap)
        .filter { it.confidence > CONFIDENCE_THRESHOLD }
        .sortedByDescending { it.confidence }
        .take(MAX_TAGS)
        .map { it.text.lowercase() }

    private suspend fun recognizeText(bitmap: Bitmap): List<String> = textRecognizer.recognize(bitmap)
        .filter { line ->
            line.length <= MAX_TEXT_LINE_LENGTH && !PERSONAL_INFO_REGEX.containsMatchIn(line)
        }
        .take(MAX_TEXT_LINES)

    private suspend fun detectFaces(bitmap: Bitmap): Int = faceDetector.detect(bitmap)

    /**
     * Bitmap pixel-average via 1×1 [Bitmap.createScaledBitmap]. Returns
     * `"#RRGGBB"` (no alpha — the prompt LLM doesn't care about opacity).
     * The 1×1 bitmap is always recycled before returning, regardless of
     * outcome, to avoid leaking the temporary alongside the source bitmap.
     */
    private fun computeDominantColor(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, /* filter = */ true)
        return try {
            val rgb = scaled.getPixel(0, 0) and 0x00FFFFFF
            "#%06X".format(rgb)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {
        private const val TAG = "PhotoContextAnalyzer"
        private const val CONFIDENCE_THRESHOLD: Float = 0.3f
        private const val MAX_TAGS = 8
        private const val MAX_TEXT_LINES = 5
        private const val MAX_TEXT_LINE_LENGTH = 50
        private const val CACHE_KEY_PREFIX = "photo_context_"

        /**
         * Sentinel result emitted on pipeline failure. Empty fields and
         * a neutral `#000000` color so prompt formatting still has
         * non-null values to reference. NOT written to the cache so the
         * next analyze attempt re-runs the pipeline.
         */
        private val EMPTY = PhotoContext(
            tags = emptyList(),
            detectedText = emptyList(),
            people = 0,
            outdoor = false,
            dominantColor = "#000000",
        )

        /**
         * Tag intersection used as the proxy for iOS's
         * `VNDetectHorizonRequest`. Lowercased; matched against the
         * already-lowercased label texts. Conservative set — false
         * positives feed the LLM; false negatives are recoverable from
         * other cues in the prompt.
         */
        private val OUTDOOR_TAG_SET = setOf(
            "outdoor",
            "nature",
            "sky",
            "landscape",
            "field",
            "mountain",
            "forest",
            "park",
            "beach",
            "ocean",
        )

        /**
         * Drops phone numbers, emails, URLs, and `www.` shortlinks from
         * the OCR pass before it reaches the LLM. Conservative —
         * combined with the 50-char length cap, keeps user-pinned
         * receipts / signage in but filters most accidental personal
         * info captured in the frame.
         */
        private val PERSONAL_INFO_REGEX = Regex(
            """(\d{3}[-.\s]?\d{3}[-.\s]?\d{4})|(\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b)|(https?://\S+)|(\bwww\.\S+)""",
            RegexOption.IGNORE_CASE,
        )

        private fun cacheKeyFor(uri: Uri): String =
            CACHE_KEY_PREFIX + uri.toString().replace("/", "_")
    }
}
