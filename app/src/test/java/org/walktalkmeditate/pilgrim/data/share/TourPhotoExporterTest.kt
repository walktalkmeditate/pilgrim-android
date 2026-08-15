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
import java.util.UUID
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
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * iOS parity `UnitTests/TourPhotoExporterTests.swift@3f9f9e8` (the
 * ladder-envelope tests) plus the Android-original scenarios the U5
 * plan/spec call for: EXIF-orientation-into-pixels, the 20-photo
 * `prefix` cap, unresolvable-URI short counting, the wall-clock
 * deadline, downscale-only math, cancellation, and U4/U5 cleanup
 * coverage of the `photos/` subdirectory.
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
    private lateinit var clock: FakeClock
    private lateinit var exporter: TourPhotoExporter
    private lateinit var fixtureDir: File

    private val walkUuid = "walk-export-1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharePrepStore = SharePrepStore(context, FakeShareAudioTranscoder(), VoiceRecordingFileSystem(context))
        clock = FakeClock(initial = 1_700_000_000_000L)
        exporter = TourPhotoExporter(context, sharePrepStore, clock)
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

    // --- wall-clock deadline (Clock-driven, not System.currentTimeMillis) ---

    @Test
    fun `a per-photo wall-clock overrun stops the batch and the rest count short`() = runBlocking {
        val candidates = (1..5).map { walkPhoto(registerJpeg("d$it", 20, 20)) }

        val result = exporter.export(walkUuid, candidates) { completed, _ ->
            if (completed == 2) {
                clock.advanceBy(TourPhotoExporter.PER_PHOTO_TIMEOUT_MS + TourPhotoExporter.BACKSTOP_GRACE_MS)
            }
        }

        assertEquals(5, result.requested)
        assertEquals(2, result.exported)
        assertTrue(result.isShort)
    }

    @Test
    fun `no wall-clock overrun exports the full requested set`() = runBlocking {
        val candidates = (1..4).map { walkPhoto(registerJpeg("nd$it", 20, 20)) }

        val result = exporter.export(walkUuid, candidates)

        assertEquals(4, result.requested)
        assertEquals(4, result.exported)
        assertFalse(result.isShort)
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

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceBy(deltaMs: Long) {
        current += deltaMs
    }
}
