// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

class PilgrimPackagePhotoConverterTest {

    @Test
    fun `opt-out yields null photos`() {
        val result = PilgrimPackagePhotoConverter.exportPhotos(
            walkPhotos = listOf(photo(id = 1, pinnedAt = 1_000)),
            routeSamples = listOf(sample(1_000, 0.0, 0.0)),
            includePhotos = false,
        )
        assertNull(result.photos)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `opt-in with no pinned photos yields empty list`() {
        val result = PilgrimPackagePhotoConverter.exportPhotos(
            walkPhotos = emptyList(),
            routeSamples = listOf(sample(1_000, 0.0, 0.0)),
            includePhotos = true,
        )
        assertNotNull(result.photos)
        assertTrue(result.photos!!.isEmpty())
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `nearest route sample provides GPS for each photo`() {
        val photos = listOf(
            photo(id = 1, pinnedAt = 1_000, takenAt = 1_500),
            photo(id = 2, pinnedAt = 5_000, takenAt = 5_200),
        )
        val samples = listOf(
            sample(1_000, 47.6, -122.3),
            sample(2_000, 47.7, -122.4),
            sample(5_000, 47.8, -122.5),
        )
        val result = PilgrimPackagePhotoConverter.exportPhotos(photos, samples, includePhotos = true)
        assertNotNull(result.photos)
        assertEquals(2, result.photos!!.size)
        assertEquals(0, result.skippedCount)
        assertEquals(47.6, result.photos!![0].capturedLat, 0.001)
        assertEquals(-122.3, result.photos!![0].capturedLng, 0.001)
        assertEquals(47.8, result.photos!![1].capturedLat, 0.001)
    }

    @Test
    fun `walk with no route samples drops all photos and counts them skipped`() {
        val photos = listOf(photo(id = 1, pinnedAt = 1_000))
        val result = PilgrimPackagePhotoConverter.exportPhotos(
            walkPhotos = photos,
            routeSamples = emptyList(),
            includePhotos = true,
        )
        assertNotNull(result.photos)
        assertTrue(result.photos!!.isEmpty())
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `falls back to pinnedAt when takenAt is null`() {
        val photos = listOf(photo(id = 1, pinnedAt = 5_000, takenAt = null))
        val samples = listOf(
            sample(1_000, 47.6, -122.3),
            sample(5_000, 47.8, -122.5),
        )
        val result = PilgrimPackagePhotoConverter.exportPhotos(photos, samples, includePhotos = true)
        assertEquals(47.8, result.photos!![0].capturedLat, 0.001)
    }

    @Test
    fun `import rebuilds WalkPhoto rows with takenAt = capturedAt`() {
        val pilgrim = listOf(
            org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPhoto(
                localIdentifier = "content://media/12345",
                capturedAt = java.time.Instant.ofEpochMilli(1_500),
                capturedLat = 47.6,
                capturedLng = -122.3,
                keptAt = java.time.Instant.ofEpochMilli(2_000),
            ),
        )
        val rebuilt = PilgrimPackagePhotoConverter.importPhotos(walkId = 42L, exported = pilgrim)
        assertEquals(1, rebuilt.size)
        assertEquals(42L, rebuilt[0].walkId)
        assertEquals("content://media/12345", rebuilt[0].photoUri)
        assertEquals(2_000L, rebuilt[0].pinnedAt)
        assertEquals(1_500L, rebuilt[0].takenAt)
    }

    @Test
    fun `import returns empty list for null exported photos`() {
        val rebuilt = PilgrimPackagePhotoConverter.importPhotos(walkId = 42L, exported = null)
        assertTrue(rebuilt.isEmpty())
    }

    private fun photo(id: Long, pinnedAt: Long, takenAt: Long? = null) = WalkPhoto(
        id = id,
        walkId = 1L,
        photoUri = "content://media/external/images/media/$id",
        pinnedAt = pinnedAt,
        takenAt = takenAt,
    )

    private fun sample(timestamp: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = timestamp, latitude = lat, longitude = lng,
    )
}
