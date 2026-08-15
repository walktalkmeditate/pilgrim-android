// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.audio.FakeShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * iOS parity `UnitTests/TourPhotoExporterTests.swift@3f9f9e8` (the
 * ladder-envelope tests) plus the Android-original scenarios the U5
 * plan/spec call for: EXIF-orientation-into-pixels, the 20-photo
 * `prefix` cap, unresolvable-URI short counting, the per-candidate
 * deadline, sampled-decode + downscale math, cancellation, and U4/U5
 * cleanup coverage of the `photos/` subdirectory.
 *
 * [GraphicsMode.Mode.NATIVE] is required (same reasoning as
 * `GlyphAssetTest`/`MapGlyphBitmapsTest`): this project's Robolectric
 * default is LEGACY shadows, whose `Bitmap.compress`/`BitmapFactory`
 * round-trip does not produce real, size-accurate pixel data — every
 * byte-size and dimension assertion below needs the real Skia backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourPhotoExporterTest {

    private lateinit var context: Context
    private lateinit var sharePrepStore: SharePrepStore
    private lateinit var exporter: TourPhotoExporter
    private lateinit var fixtureDir: File

    private val walkUuid = "walk-export-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharePrepStore = SharePrepStore(context, FakeShareAudioTranscoder(), VoiceRecordingFileSystem(context))
        exporter = TourPhotoExporter(context, sharePrepStore)
        fixtureDir = File(context.cacheDir, "tpe-fixtures-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        fixtureDir.deleteRecursively()
    }

    // --- jpegDataUnder ladder (mirrors iOS TourPhotoExporterTests) ---

    @Test
    fun `jpegDataUnder steps the ladder down until output fits the cap`() {
        val bitmap = noisyBitmap(side = 1600)
        try {
            // Self-calibrated rather than a hardcoded byte count: JPEG encoders
            // compress the SAME "noisy" pixels differently across platforms (the
            // parity spec's DAT-52 drift note flags exactly this), so pin the cap
            // relative to THIS platform's own quality-80-vs-quality-20 spread
            // instead of assuming iOS's ~890KB-2.15MB envelope carries over.
            val highQualityBytes = ByteArrayOutputStream().let {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
                it.size()
            }
            val lowQualityBytes = ByteArrayOutputStream().let {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 20, it)
                it.size()
            }
            assertTrue(
                "fixture must actually compress smaller at quality 20 than quality 80 " +
                    "(80=$highQualityBytes, 20=$lowQualityBytes) for this test to prove anything",
                lowQualityBytes < highQualityBytes,
            )
            val capBytes = (highQualityBytes + lowQualityBytes) / 2

            val result = TourPhotoExporter.jpegDataUnder(bitmap, capBytes)

            assertNotNull("a lower ladder rung must fit under a cap between the two extremes", result)
            assertTrue(result!!.isNotEmpty())
            assertTrue(result.size <= capBytes)
            assertTrue("must actually have stepped down from quality 80's own size", result.size < highQualityBytes)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `jpegDataUnder returns null when no ladder rung fits`() {
        val bitmap = noisyBitmap(side = 1600)
        try {
            assertNull(TourPhotoExporter.jpegDataUnder(bitmap, capBytes = 10))
        } finally {
            bitmap.recycle()
        }
    }

    // --- EXIF orientation baked into pixels, dropped from the output ---

    @Test
    fun `export applies EXIF orientation into pixels and the output carries no EXIF orientation`() = runBlocking {
        // 40x20 landscape source + EXIF "rotate 90" — an upright decode must be 20x40 (swapped).
        val uri = registerJpeg("rotated", width = 40, height = 20, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val photo = walkPhoto(uri)

        val result = exporter.export(walkUuid, listOf(photo))

        assertEquals(1, result.exported)
        val outFile = result.photos.single().file
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outFile.absolutePath, bounds)
        assertEquals("output width must reflect the EXIF rotation baked into pixels", 20, bounds.outWidth)
        assertEquals("output height must reflect the EXIF rotation baked into pixels", 40, bounds.outHeight)

        // androidx.exifinterface reads a JPEG with no EXIF/APP1 segment at all as
        // TAG_ORIENTATION="0" (ORIENTATION_UNDEFINED) rather than a null/absent
        // attribute (verified empirically against a plain Bitmap.compress output
        // with zero EXIF writing — unrelated tags like TAG_MAKE genuinely read
        // back null, confirming this isn't a leftover from this test's input).
        // UNDEFINED and NORMAL both mean "apply no rotation" to any EXIF-aware
        // consumer, so both count as "no meaningful orientation metadata" here.
        val outOrientation = ExifInterface(outFile.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        assertTrue(
            "re-encoded JPEG must carry no meaningful EXIF orientation (was $outOrientation)",
            outOrientation == ExifInterface.ORIENTATION_UNDEFINED || outOrientation == ExifInterface.ORIENTATION_NORMAL,
        )
    }

    // --- EXIF orientation: pixel-level coverage for the remaining branches (U5 review Minor 4) ---
    // 2x2 four-quadrant fixture (not the 2x1 two-tone fixture the finding floated) because a
    // height-1 bitmap makes ROTATE_180 and FLIP_HORIZONTAL produce IDENTICAL pixel output
    // (rotating 180 degrees with no vertical extent degenerates to a pure horizontal mirror) —
    // verified empirically before writing these assertions. 2x2 keeps every branch distinguishable
    // while staying cheap. All 8 `applyExifOrientation` branches remain implemented; these three
    // (plus the existing ROTATE_90 dimension-swap test above) are the ones the review flagged.

    @Test
    fun `applyExifOrientation ROTATE_180 rotates both axes`() {
        val bitmap = fourQuadrantBitmap()
        val result = TourPhotoExporter.applyExifOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_180)
        try {
            assertEquals(2, result.width)
            assertEquals(2, result.height)
            assertEquals(Color.YELLOW, result.getPixel(0, 0))
            assertEquals(Color.GREEN, result.getPixel(1, 0))
            assertEquals(Color.BLUE, result.getPixel(0, 1))
            assertEquals(Color.RED, result.getPixel(1, 1))
        } finally {
            if (result !== bitmap) result.recycle()
            bitmap.recycle()
        }
    }

    @Test
    fun `applyExifOrientation ROTATE_270 swaps and rotates`() {
        val bitmap = fourQuadrantBitmap()
        val result = TourPhotoExporter.applyExifOrientation(bitmap, ExifInterface.ORIENTATION_ROTATE_270)
        try {
            assertEquals(2, result.width)
            assertEquals(2, result.height)
            assertEquals(Color.BLUE, result.getPixel(0, 0))
            assertEquals(Color.YELLOW, result.getPixel(1, 0))
            assertEquals(Color.RED, result.getPixel(0, 1))
            assertEquals(Color.GREEN, result.getPixel(1, 1))
        } finally {
            if (result !== bitmap) result.recycle()
            bitmap.recycle()
        }
    }

    @Test
    fun `applyExifOrientation FLIP_HORIZONTAL mirrors left-right only`() {
        val bitmap = fourQuadrantBitmap()
        val result = TourPhotoExporter.applyExifOrientation(bitmap, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        try {
            assertEquals(2, result.width)
            assertEquals(2, result.height)
            assertEquals(Color.BLUE, result.getPixel(0, 0))
            assertEquals(Color.RED, result.getPixel(1, 0))
            assertEquals(Color.YELLOW, result.getPixel(0, 1))
            assertEquals(Color.GREEN, result.getPixel(1, 1))
        } finally {
            if (result !== bitmap) result.recycle()
            bitmap.recycle()
        }
    }

    // --- unresolvable URI ---

    @Test
    fun `unresolvable URI counts toward the shortfall while other photos still export`() = runBlocking {
        val good1 = walkPhoto(registerJpeg("good1", 100, 60))
        val bad = walkPhoto(Uri.parse("content://test/does-not-exist"))
        val good2 = walkPhoto(registerJpeg("good2", 100, 60))

        val result = exporter.export(walkUuid, listOf(good1, bad, good2))

        assertEquals(3, result.requested)
        assertEquals(2, result.exported)
        assertTrue(result.isShort)
        assertEquals(setOf(good1.photoUri, good2.photoUri), result.photos.map { it.sourceUri }.toSet())
    }

    // --- filename numbering: dense over successes, not positional (U5 review Minor 3) ---

    @Test
    fun `exported filenames are dense over successes, not positional in the original candidate list`() = runBlocking {
        val good1 = walkPhoto(registerJpeg("fn-good1", 20, 20))
        val bad = walkPhoto(Uri.parse("content://test/fn-does-not-exist"))
        val good2 = walkPhoto(registerJpeg("fn-good2", 20, 20))

        val result = exporter.export(walkUuid, listOf(good1, bad, good2))

        assertEquals(2, result.exported)
        assertEquals(listOf("1.jpg", "2.jpg"), result.photos.map { it.file.name })
        assertTrue(sharePrepStore.photoFile(walkUuid, 1).exists())
        assertTrue(sharePrepStore.photoFile(walkUuid, 2).exists())
        assertFalse("no gap file for the failed candidate", sharePrepStore.photoFile(walkUuid, 3).exists())
    }

    // --- prefix(20) cap ---

    @Test
    fun `caps the export list at 20 even when more candidates are pinned`() = runBlocking {
        val candidates = (1..25).map { walkPhoto(registerJpeg("p$it", 20, 20)) }

        val result = exporter.export(walkUuid, candidates)

        assertEquals(20, result.requested)
        assertEquals(20, result.exported)
        assertFalse(result.isShort)
        assertFalse("photo 21 must never have been attempted", sharePrepStore.photoFile(walkUuid, 21).exists())
    }

    // --- per-candidate deadline (U5 review Important-1: bound the CANDIDATE, not the batch) ---

    @Test
    fun `a per-candidate timeout fails only that candidate and the batch continues`() = runBlocking {
        val slowUri = registerSlowJpeg("slow", 20, 20, sleepMs = 300L)
        val candidates = listOf(
            walkPhoto(slowUri),
            walkPhoto(registerJpeg("healthyB", 20, 20)),
            walkPhoto(registerJpeg("healthyC", 20, 20)),
        )

        val result = exporter.exportBounded(walkUuid, candidates, perCandidateBudgetMs = 50L)

        assertEquals(3, result.requested)
        assertEquals(2, result.exported)
        assertTrue(result.isShort)
        assertEquals("short by exactly the one slow candidate", 1, result.requested - result.exported)
        assertEquals(
            "the two healthy candidates must still be exported despite the slow first one",
            setOf(candidates[1].photoUri, candidates[2].photoUri),
            result.photos.map { it.sourceUri }.toSet(),
        )
    }

    @Test
    fun `no per-candidate timeout exports the full requested set`() = runBlocking {
        val candidates = (1..4).map { walkPhoto(registerJpeg("nd$it", 20, 20)) }

        val result = exporter.export(walkUuid, candidates)

        assertEquals(4, result.requested)
        assertEquals(4, result.exported)
        assertFalse(result.isShort)
    }

    // --- sampled decode (U5 review Important-2: kill the OOM path) ---

    @Test
    fun `computeInSampleSize picks the largest power of two keeping the long edge at or above target`() {
        assertEquals(4, TourPhotoExporter.computeInSampleSize(6400, 3200, 1600))
        assertEquals(1, TourPhotoExporter.computeInSampleSize(1200, 900, 1600))
    }

    @Test
    fun `a very large source is sampled before decode and still lands on the exact target after scaling`() =
        runBlocking {
            val uri = registerJpeg("huge", width = 6400, height = 3200)

            val result = exporter.export(walkUuid, listOf(walkPhoto(uri)))

            assertEquals(1, result.exported)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(result.photos.single().file.absolutePath, bounds)
            assertEquals(1600, bounds.outWidth)
            assertEquals(800, bounds.outHeight)
        }

    // --- downscale math ---

    @Test
    fun `scales a larger-than-target long edge down while preserving aspect ratio`() = runBlocking {
        val uri = registerJpeg("large", width = 3200, height = 1600)

        val result = exporter.export(walkUuid, listOf(walkPhoto(uri)))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.photos.single().file.absolutePath, bounds)
        assertEquals(1600, bounds.outWidth)
        assertEquals(800, bounds.outHeight)
    }

    @Test
    fun `does not upscale a source smaller than the target long edge`() = runBlocking {
        val uri = registerJpeg("small", width = 400, height = 300)

        val result = exporter.export(walkUuid, listOf(walkPhoto(uri)))

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.photos.single().file.absolutePath, bounds)
        assertEquals("must not upscale", 400, bounds.outWidth)
        assertEquals("must not upscale", 300, bounds.outHeight)
    }

    // --- metadata shape ---

    @Test
    fun `exported photo meta carries lat lon and epoch-second timestamp with no inline data`() = runBlocking {
        val uri = registerJpeg("meta", 100, 60)
        val photo = walkPhoto(uri, lat = 35.6812, lng = 139.767, takenAtMs = 1_700_000_123_000L)

        val result = exporter.export(walkUuid, listOf(photo))

        val meta = result.photos.single().meta
        assertEquals(35.6812, meta.lat, 0.0001)
        assertEquals(139.767, meta.lon, 0.0001)
        assertEquals(1_700_000_123L, meta.ts)
        assertNull("interactive photo metadata must omit inline data — bytes travel via a separate PUT", meta.data)
        assertEquals("1.jpg", result.photos.single().file.name)
    }

    // --- cancellation between photos ---

    @Test
    fun `cancellation stops the export before the next photo starts`() = runBlocking {
        val candidates = (1..5).map { walkPhoto(registerJpeg("cancel$it", 20, 20)) }
        var job: Job? = null
        var lastCompleted = 0
        job = launch {
            exporter.export(walkUuid, candidates) { completed, _ ->
                lastCompleted = completed
                if (completed == 1) job?.cancel()
            }
        }
        job.join()

        assertTrue("the launching job must observe cancellation", job.isCancelled)
        assertEquals("only the first photo's progress tick must have fired", 1, lastCompleted)
        assertFalse(sharePrepStore.photoFile(walkUuid, 2).exists())
    }

    @Test
    fun `outer cancellation mid-batch propagates as CancellationException instead of a partial result`() =
        runBlocking {
            val candidates = (1..5).map { walkPhoto(registerJpeg("oc$it", 20, 20)) }
            var job: Job? = null
            var completedTicks = 0
            var caught: Throwable? = null
            job = launch {
                try {
                    exporter.export(walkUuid, candidates) { completed, _ ->
                        completedTicks = completed
                        if (completed == 1) job?.cancel()
                    }
                } catch (t: Throwable) {
                    caught = t
                    throw t
                }
            }
            job.join()

            assertTrue(
                "a real outer cancellation must re-throw as CancellationException, not be swallowed",
                caught is CancellationException,
            )
            assertEquals(1, completedTicks)
            assertFalse(sharePrepStore.photoFile(walkUuid, 2).exists())
        }

    // --- cleanup coverage (U4's cancelAndCleanupWalk must sweep U5's photos/ subdir) ---

    @Test
    fun `cancelAndCleanupWalk removes the exported photos directory`() = runBlocking {
        val candidates = (1..3).map { walkPhoto(registerJpeg("c$it", 20, 20)) }

        val result = exporter.export(walkUuid, candidates)

        assertEquals(3, result.exported)
        val photosDir = result.photos.first().file.parentFile!!
        assertTrue(photosDir.exists())

        sharePrepStore.cancelAndCleanupWalk(walkUuid)

        assertFalse("photos subdir must not survive walk cleanup", photosDir.exists())
        result.photos.forEach { assertFalse(it.file.exists()) }
    }

    // --- helpers ---

    private fun walkPhoto(
        uri: Uri,
        lat: Double? = 35.0,
        lng: Double? = 139.0,
        takenAtMs: Long? = 1_700_000_000_000L,
    ): WalkPhoto = WalkPhoto(
        walkId = 1L,
        photoUri = uri.toString(),
        pinnedAt = 1_700_000_000_000L,
        takenAt = takenAtMs,
        capturedLat = lat,
        capturedLng = lng,
    )

    private fun registerJpeg(name: String, width: Int, height: Int, orientation: Int? = null): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(120, 140, 160))
        val fixtureFile = File(fixtureDir, "$name.jpg")
        ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            fixtureFile.writeBytes(baos.toByteArray())
        }
        bitmap.recycle()
        if (orientation != null) {
            val exif = ExifInterface(fixtureFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        val bytes = fixtureFile.readBytes()
        val uri = Uri.parse("content://test/$name-${UUID.randomUUID()}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    /**
     * Same JPEG fixture as [registerJpeg], but the registered
     * `InputStream` sleeps (real wall-clock, once, on its first read)
     * before yielding any bytes — simulates a slow/wedged content
     * provider for the per-candidate deadline test above, independent
     * of [TourPhotoExporter]'s production 22s budget (the test calls
     * [TourPhotoExporter.exportBounded] with a short budget instead).
     */
    private fun registerSlowJpeg(name: String, width: Int, height: Int, sleepMs: Long): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(120, 140, 160))
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        bitmap.recycle()
        val uri = Uri.parse("content://test/$name-${UUID.randomUUID()}")
        shadowOf(context.contentResolver).registerInputStream(uri, SlowInputStream(baos.toByteArray(), sleepMs))
        return uri
    }

    private fun fourQuadrantBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, Color.RED)
        setPixel(1, 0, Color.BLUE)
        setPixel(0, 1, Color.GREEN)
        setPixel(1, 1, Color.YELLOW)
    }

    /**
     * Mirrors iOS `TourPhotoExporterTests.noisyImage` (same
     * `(x*31 + y*17) % 100` block-hue formula, 8px blocks): fully
     * uncorrelated blocks defeat JPEG's neighbor prediction. Android's
     * Skia encoder compresses this pattern to a meaningfully different
     * byte envelope than iOS's encoder does (measured: quality-80 output
     * lands well under iOS's ~2.15MB reference for the same formula) —
     * the ladder test above self-calibrates its cap from this bitmap's
     * own quality-80-vs-quality-20 sizes rather than assuming iOS's
     * envelope carries over, per the parity spec's DAT-52 drift note.
     */
    private fun noisyBitmap(side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        var x = 0
        while (x < side) {
            var y = 0
            while (y < side) {
                val hue = ((x * 31 + y * 17) % 100).toFloat() * 3.6f
                paint.color = Color.HSVToColor(floatArrayOf(hue, 0.8f, 0.9f))
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
                y += 8
            }
            x += 8
        }
        return bitmap
    }
}

/**
 * Sleeps once (on the first read) before delegating to a real
 * in-memory stream — see [TourPhotoExporterTest.registerSlowJpeg].
 */
private class SlowInputStream(bytes: ByteArray, private val sleepMs: Long) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private var slept = false

    private fun maybeSleep() {
        if (!slept) {
            slept = true
            Thread.sleep(sleepMs)
        }
    }

    override fun read(): Int {
        maybeSleep()
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        maybeSleep()
        return delegate.read(b, off, len)
    }
}
