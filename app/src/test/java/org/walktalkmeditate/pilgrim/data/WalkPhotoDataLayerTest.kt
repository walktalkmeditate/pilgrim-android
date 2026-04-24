// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

/**
 * Repository-facing coverage for Stage 7-A photo methods. Mirrors
 * [VoiceRecordingDataLayerTest]'s in-memory Room pattern so the
 * behavior contract is tested where consumers (VM, UI) will read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkPhotoDataLayerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `pinPhoto inserts a readable row`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        val id = repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://x/1",
            takenAt = 500L,
            pinnedAt = 2_000L,
        )

        assertTrue("expected id > 0, got $id", id > 0)
        repository.observePhotosFor(walk.id).test {
            val rows = awaitItem()
            assertEquals(1, rows.size)
            assertEquals("content://x/1", rows.first().photoUri)
            assertEquals(500L, rows.first().takenAt)
            assertEquals(2_000L, rows.first().pinnedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pinPhotos commits a batch and returns ids`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        val refs = listOf(
            PhotoPinRef("content://x/1", takenAt = null),
            PhotoPinRef("content://x/2", takenAt = 10L),
            PhotoPinRef("content://x/3", takenAt = 20L),
        )

        val ids = repository.pinPhotos(walk.id, refs, pinnedAt = 5_000L)

        assertEquals(3, ids.size)
        assertEquals(3, repository.countPhotosFor(walk.id))
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `pinPhotos with empty refs is a no-op`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        val ids = repository.pinPhotos(walk.id, refs = emptyList(), pinnedAt = 5_000L)

        assertTrue(ids.isEmpty())
        assertEquals(0, repository.countPhotosFor(walk.id))
    }

    @Test
    fun `unpinPhoto returns NotFound for non-existent id`() = runTest {
        assertSame(UnpinPhotoResult.NotFound, repository.unpinPhoto(photoId = 99_999L))
    }

    @Test
    fun `unpinPhoto returns Removed with wasLastReference=true for a solo pin`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        val id = repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://x/1",
            takenAt = null,
            pinnedAt = 2_000L,
        )

        val result = repository.unpinPhoto(id) as UnpinPhotoResult.Removed

        assertEquals("content://x/1", result.photoUri)
        assertTrue(result.wasLastReference)
        assertEquals(0, repository.countPhotosFor(walk.id))
    }

    @Test
    fun `unpinPhoto returns wasLastReference=false when another walk still pins the same URI`() = runTest {
        // The persistable URI grant is shared app-wide; releasing while
        // another walk still references the URI would tombstone that
        // walk's tile. The repo reports the reference count so the VM
        // can decide whether to call releasePersistableUriPermission.
        val walkA = repository.startWalk(startTimestamp = 1_000L)
        val walkB = repository.startWalk(startTimestamp = 2_000L)
        val sharedUri = "content://media/picker/0/shared"
        val idA = repository.pinPhoto(
            walkId = walkA.id,
            photoUri = sharedUri,
            takenAt = null,
            pinnedAt = 3_000L,
        )
        repository.pinPhoto(
            walkId = walkB.id,
            photoUri = sharedUri,
            takenAt = null,
            pinnedAt = 4_000L,
        )

        val result = repository.unpinPhoto(idA) as UnpinPhotoResult.Removed

        assertEquals(sharedUri, result.photoUri)
        assertFalse(
            "wasLastReference should be false when walk B still pins the URI",
            result.wasLastReference,
        )
        assertEquals(0, repository.countPhotosFor(walkA.id))
        assertEquals(1, repository.countPhotosFor(walkB.id))
    }

    @Test
    fun `observePhotosFor emits on pin and on unpin`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        repository.observePhotosFor(walk.id).test {
            assertEquals(emptyList<WalkPhoto>(), awaitItem())

            val id = repository.pinPhoto(
                walkId = walk.id,
                photoUri = "content://x/1",
                takenAt = null,
                pinnedAt = 2_000L,
            )
            assertEquals(1, awaitItem().size)

            repository.unpinPhoto(id)
            assertEquals(0, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pinPhotos clips the batch to the cap when the walk is already near the limit`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        // Seed 18 pins of a 20-cap walk.
        repository.pinPhotos(
            walkId = walk.id,
            refs = (1..18).map { PhotoPinRef("content://x/$it", takenAt = null) },
            pinnedAt = 1_000L,
            cap = 20,
        )
        assertEquals(18, repository.countPhotosFor(walk.id))

        // Try to add 5 more under a 20 cap — only 2 should land.
        val returnedIds = repository.pinPhotos(
            walkId = walk.id,
            refs = (100..104).map { PhotoPinRef("content://y/$it", takenAt = null) },
            pinnedAt = 2_000L,
            cap = 20,
        )

        assertEquals(2, returnedIds.size)
        assertEquals(20, repository.countPhotosFor(walk.id))
    }

    @Test
    fun `pinPhotos returns empty when the walk is already at the cap`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.pinPhotos(
            walkId = walk.id,
            refs = (1..5).map { PhotoPinRef("content://x/$it", takenAt = null) },
            pinnedAt = 1_000L,
            cap = 5,
        )
        assertEquals(5, repository.countPhotosFor(walk.id))

        val returnedIds = repository.pinPhotos(
            walkId = walk.id,
            refs = listOf(PhotoPinRef("content://y/1", takenAt = null)),
            pinnedAt = 2_000L,
            cap = 5,
        )

        assertTrue(returnedIds.isEmpty())
        assertEquals(5, repository.countPhotosFor(walk.id))
    }

    @Test
    fun `deleting a walk cascades to its pinned photos`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://x/1",
            takenAt = null,
            pinnedAt = 2_000L,
        )
        assertEquals(1, repository.countPhotosFor(walk.id))

        repository.deleteWalk(walk)

        assertEquals(0, repository.countPhotosFor(walk.id))
    }
}
