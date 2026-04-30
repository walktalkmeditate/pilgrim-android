// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
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
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeatureCollection
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPhoto
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimStats
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidPilgrimPhotoEmbedderTest {

    private lateinit var embedder: AndroidPilgrimPhotoEmbedder
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        embedder = AndroidPilgrimPhotoEmbedder(app)
        tempDir = File(app.cacheDir, "test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `embedPhotos returns empty result when walks have no photos`() {
        val result = embedder.embedPhotos(
            walks = listOf(walk(uuid = "a", photos = null)),
            tempDir = tempDir,
        )
        assertTrue(result.filenameMap.isEmpty())
        assertEquals(0, result.skippedCount)
        assertFalse("photos dir should not be created", File(tempDir, "photos").exists())
    }

    @Test
    fun `embedPhotos with valid synthetic image writes JPEG and maps filename`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val syntheticUri = registerSyntheticImage(app, name = "test1.png", width = 800, height = 600)
        val photo = PilgrimPhoto(
            localIdentifier = syntheticUri.toString(),
            capturedAt = Instant.ofEpochSecond(1_700_000_000),
            capturedLat = 47.6,
            capturedLng = -122.3,
            keptAt = Instant.ofEpochSecond(1_700_000_500),
        )
        val walk = walk(uuid = "a", photos = listOf(photo))

        val result = embedder.embedPhotos(walks = listOf(walk), tempDir = tempDir)
        assertEquals(1, result.filenameMap.size)
        assertEquals(0, result.skippedCount)
        val filename = result.filenameMap[syntheticUri.toString()]
        assertTrue(filename!!.endsWith(".jpg"))
        val written = File(tempDir, "photos/$filename")
        assertTrue("expected $written to exist", written.exists())
        assertTrue("file should have content", written.length() > 0)
    }

    @Test
    fun `dedupes by localIdentifier across walks`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val syntheticUri = registerSyntheticImage(app, name = "shared.png", width = 200, height = 200)
        val sharedPhoto = PilgrimPhoto(
            localIdentifier = syntheticUri.toString(),
            capturedAt = Instant.ofEpochSecond(1_700_000_000),
            capturedLat = 47.6,
            capturedLng = -122.3,
            keptAt = Instant.ofEpochSecond(1_700_000_500),
        )

        val result = embedder.embedPhotos(
            walks = listOf(
                walk(uuid = "a", photos = listOf(sharedPhoto)),
                walk(uuid = "b", photos = listOf(sharedPhoto)),
            ),
            tempDir = tempDir,
        )
        assertEquals(1, result.filenameMap.size)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `unresolvable URI counts as skipped`() {
        val photo = PilgrimPhoto(
            localIdentifier = "content://media/external/images/media/missing-99999",
            capturedAt = Instant.ofEpochSecond(1_700_000_000),
            capturedLat = 0.0,
            capturedLng = 0.0,
            keptAt = Instant.ofEpochSecond(1_700_000_000),
        )
        val result = embedder.embedPhotos(
            walks = listOf(walk(uuid = "a", photos = listOf(photo))),
            tempDir = tempDir,
        )
        assertTrue(result.filenameMap.isEmpty())
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `encodeAsDataUrl returns base64 data URL for valid synthetic image`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val syntheticUri = registerSyntheticImage(app, name = "thumb.png", width = 200, height = 200)

        val dataUrl = embedder.encodeAsDataUrl(syntheticUri.toString())

        assertNotNull(dataUrl)
        assertTrue(dataUrl!!.startsWith("data:image/jpeg;base64,"))
        val base64 = dataUrl.removePrefix("data:image/jpeg;base64,")
        assertTrue(base64.isNotEmpty())
        assertTrue(base64.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `encodeAsDataUrl returns null for unresolvable URI`() {
        val dataUrl = embedder.encodeAsDataUrl("content://media/external/images/media/missing-99999")
        assertNull(dataUrl)
    }

    @Test
    fun `sanitizedFilename replaces slashes with underscores`() {
        assertEquals(
            "ABC-123_L0_001.jpg",
            AndroidPilgrimPhotoEmbedder.sanitizedFilename("ABC-123/L0/001"),
        )
        assertEquals(
            "content:__media_external_images_media_42.jpg",
            AndroidPilgrimPhotoEmbedder.sanitizedFilename("content://media/external/images/media/42"),
        )
    }

    private fun registerSyntheticImage(app: Application, name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        val pngBytes = baos.toByteArray()
        val uri = Uri.parse("content://test/$name")
        val resolver = app.contentResolver
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(pngBytes))
        return uri
    }

    private fun walk(uuid: String, photos: List<PilgrimPhoto>?) = PilgrimWalk(
        schemaVersion = "1.0",
        id = uuid,
        type = "walking",
        startDate = Instant.ofEpochSecond(1_700_000_000),
        endDate = Instant.ofEpochSecond(1_700_003_600),
        stats = PilgrimStats(
            distance = 0.0,
            activeDuration = 0.0,
            pauseDuration = 0.0,
            ascent = 0.0,
            descent = 0.0,
            talkDuration = 0.0,
            meditateDuration = 0.0,
        ),
        weather = null,
        route = GeoJsonFeatureCollection(features = emptyList()),
        pauses = emptyList(),
        activities = emptyList(),
        voiceRecordings = emptyList(),
        intention = null,
        reflection = null,
        heartRates = emptyList(),
        workoutEvents = emptyList(),
        favicon = null,
        isRace = false,
        isUserModified = false,
        finishedRecording = true,
        photos = photos,
    )
}
