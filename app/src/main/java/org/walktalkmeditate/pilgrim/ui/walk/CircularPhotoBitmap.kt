// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind

/**
 * Decode [uri] to a square circular bitmap suitable for use as a
 * Mapbox PointAnnotation icon. Same overall geometry as
 * `createCircleBitmap` (size + parchment stroke) but the fill is the
 * downsampled, center-cropped photo content rather than a flat color.
 *
 * iOS parity `PilgrimAnnotation.photo` (`WalkSummaryView+Map.swift:34-46@db4196e`)
 * — photos pinned to a walk render as circular thumbnails at their
 * EXIF GPS coordinates on the Walk Summary map.
 *
 * Returns null when:
 *   - URI is unreadable (deleted between pin and render)
 *   - BitmapFactory fails to decode (corrupt photo, unsupported format)
 *   - OOM (rare at the 240×240 target size, but defensive null)
 *
 * Caller hops to Dispatchers.IO — this function is pure I/O + CPU
 * (decode + Canvas operations).
 */
internal fun loadCircularPhotoBitmap(
    context: Context,
    uri: Uri,
    targetSizePx: Int,
    darkMode: Boolean,
): Bitmap? = try {
    // First pass: read bounds only so we can compute the inSampleSize
    // for downsampling. ContentResolver.openInputStream returns a fresh
    // stream each call; reusing the same stream after bounds-decode
    // would read past EOF.
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, boundsOpts)
    }
    val srcW = boundsOpts.outWidth
    val srcH = boundsOpts.outHeight
    if (srcW <= 0 || srcH <= 0) {
        Log.w(TAG, "decode bounds failed for $uri (w=$srcW h=$srcH)")
        null
    } else {
        val sample = computeInSampleSize(srcW, srcH, targetSizePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
        if (sampled == null) {
            Log.w(TAG, "decode failed for $uri")
            null
        } else {
            val masked = circularCenterCrop(sampled, targetSizePx, darkMode)
            if (sampled !== masked) sampled.recycle()
            masked
        }
    }
} catch (t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
    Log.w(TAG, "circular photo bitmap failed for $uri", t)
    null
}

private fun computeInSampleSize(srcW: Int, srcH: Int, targetPx: Int): Int {
    var sample = 1
    val short = minOf(srcW, srcH)
    while (short / (sample * 2) >= targetPx) sample *= 2
    return sample
}

private fun circularCenterCrop(src: Bitmap, sizePx: Int, darkMode: Boolean): Bitmap {
    val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val strokeWidth = sizePx * 0.08f
    val radius = (sizePx / 2f) - strokeWidth

    // Center-crop square region of source so the circular mask doesn't
    // distort the aspect ratio. The shader-coordinate Matrix maps the
    // square portion of `src` to the (sizePx × sizePx) output bounds.
    val srcSquare = minOf(src.width, src.height)
    val srcX = (src.width - srcSquare) / 2
    val srcY = (src.height - srcSquare) / 2
    val cropped = Bitmap.createBitmap(src, srcX, srcY, srcSquare, srcSquare)
    val shader = BitmapShader(cropped, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val scale = sizePx.toFloat() / srcSquare.toFloat()
    val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
    shader.setLocalMatrix(matrix)

    val fill = Paint().apply {
        isAntiAlias = true
        this.shader = shader
    }
    canvas.drawCircle(cx, cy, radius, fill)
    if (cropped !== src) cropped.recycle()

    // Parchment hex-pair matches createCircleBitmap — keep in sync if
    // the palette ever shifts.
    val parchment = if (darkMode) 0xFF1A1814.toInt() else 0xFFF5F0E6.toInt()
    val stroke = Paint().apply {
        isAntiAlias = true
        color = parchment
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    canvas.drawCircle(cx, cy, radius, stroke)

    return out
}

private const val TAG = "CircularPhotoBitmap"

private val PHOTO_PIN_TARGET_DP = 56.dp

/**
 * Per-photo circular bitmap cache for use as Mapbox PointAnnotation
 * icons. Loads each photo URI asynchronously on Dispatchers.IO; the
 * returned map starts empty and fills in as decodes complete. Caller
 * keys the annotation-render snapshot on this map so the AndroidView
 * update lambda re-fires once a bitmap is available, swapping the
 * placeholder colored circle for the real thumbnail.
 *
 * Cache is keyed by walkPhotoId. Bitmaps survive recomposition but
 * not Activity recreation — caller pays the (cheap, downsampled)
 * decode cost again on rotation.
 */
@Composable
internal fun rememberPhotoPinBitmaps(
    walkAnnotations: List<WalkMapAnnotation>,
    darkMode: Boolean,
): SnapshotStateMap<Long, Bitmap> {
    val context = LocalContext.current
    val targetSizePx = with(LocalDensity.current) { PHOTO_PIN_TARGET_DP.toPx().toInt() }
    val cache = remember { mutableStateMapOf<Long, Bitmap>() }
    val photoRefs = remember(walkAnnotations) {
        walkAnnotations.mapNotNull { ann ->
            (ann.kind as? WalkMapAnnotationKind.Photo)?.let { it.walkPhotoId to it.photoUri }
        }
    }
    // Invalidate the cache when dark-mode flips so the parchment stroke
    // matches the active theme. Decode is cheap (~30ms per photo at
    // sample size 4-8 for typical 12 MP source); recompute is preferable
    // to drift between cached stroke color + current theme.
    LaunchedEffect(photoRefs, darkMode) {
        if (cache.isNotEmpty()) cache.clear()
        for ((id, uriString) in photoRefs) {
            if (cache.containsKey(id)) continue
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    loadCircularPhotoBitmap(
                        context = context,
                        uri = Uri.parse(uriString),
                        targetSizePx = targetSizePx,
                        darkMode = darkMode,
                    )
                }.getOrNull()
            }
            if (bitmap != null) cache[id] = bitmap
        }
    }
    return cache
}
