// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkPhotoDaoTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var photoDao: WalkPhotoDao
    private lateinit var walkDao: WalkDao
    private var walkId: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        photoDao = db.walkPhotoDao()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newWalk(start: Long = 1_000L): Long {
        walkId = walkDao.insert(Walk(startTimestamp = start))
        return walkId
    }

    private fun samplePhoto(
        walk: Long,
        uri: String = "content://media/picker/0/com.android.providers.media.photopicker/media/$walk-001",
        pinnedAt: Long = 5_000L,
        takenAt: Long? = null,
    ) = WalkPhoto(
        walkId = walk,
        photoUri = uri,
        pinnedAt = pinnedAt,
        takenAt = takenAt,
    )

    @Test
    fun `insert and read back preserves all fields`() = runTest {
        val w = newWalk()
        val input = samplePhoto(w, takenAt = 9_999L)

        val id = photoDao.insert(input)
        val read = photoDao.getById(id)

        assertNotNull(read)
        assertEquals(w, read?.walkId)
        assertEquals(input.photoUri, read?.photoUri)
        assertEquals(5_000L, read?.pinnedAt)
        assertEquals(9_999L, read?.takenAt)
    }

    @Test
    fun `getForWalk orders by pinned_at ascending`() = runTest {
        val w = newWalk()
        photoDao.insert(samplePhoto(w, uri = "content://x/3", pinnedAt = 3_000L))
        photoDao.insert(samplePhoto(w, uri = "content://x/1", pinnedAt = 1_000L))
        photoDao.insert(samplePhoto(w, uri = "content://x/2", pinnedAt = 2_000L))

        val list = photoDao.getForWalk(w)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), list.map { it.pinnedAt })
    }

    @Test
    fun `observeForWalk emits new List on insert and delete`() = runTest {
        val w = newWalk()

        photoDao.observeForWalk(w).test {
            assertEquals(emptyList<WalkPhoto>(), awaitItem())

            val id1 = photoDao.insert(samplePhoto(w, uri = "content://x/1"))
            assertEquals(1, awaitItem().size)

            photoDao.insert(samplePhoto(w, uri = "content://x/2", pinnedAt = 6_000L))
            assertEquals(2, awaitItem().size)

            photoDao.deleteById(id1)
            assertEquals(1, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `countForWalk reflects inserted rows`() = runTest {
        val w = newWalk()
        assertEquals(0, photoDao.countForWalk(w))
        photoDao.insert(samplePhoto(w, uri = "content://x/1"))
        photoDao.insert(samplePhoto(w, uri = "content://x/2", pinnedAt = 6_000L))
        assertEquals(2, photoDao.countForWalk(w))
    }

    @Test
    fun `deleteById removes the matching row`() = runTest {
        val w = newWalk()
        val id = photoDao.insert(samplePhoto(w))

        val affected = photoDao.deleteById(id)

        assertEquals(1, affected)
        assertNull(photoDao.getById(id))
    }

    @Test
    fun `deleteById returns zero for non-existent id`() = runTest {
        assertEquals(0, photoDao.deleteById(99_999L))
    }

    @Test
    fun `unique uuid constraint rejects duplicate inserts`() = runTest {
        val w = newWalk()
        val first = samplePhoto(w, uri = "content://x/1")
        photoDao.insert(first)
        // Same uuid (carried via copy), different uri → must collide on uuid index.
        val duplicate = first.copy(id = 0, photoUri = "content://x/2", pinnedAt = 6_000L)

        val thrown = try {
            photoDao.insert(duplicate)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("expected exception for duplicate uuid", thrown)
        assertTrue(
            "expected unique-constraint message, got: ${thrown?.message}",
            thrown?.message?.contains("UNIQUE", ignoreCase = true) == true,
        )
        assertTrue(
            "expected SQLiteConstraintException, got ${thrown?.javaClass?.name}",
            thrown is SQLiteConstraintException,
        )
    }

    @Test
    fun `walk deletion cascades to its photos`() = runTest {
        val w = newWalk()
        photoDao.insert(samplePhoto(w, uri = "content://x/1"))
        photoDao.insert(samplePhoto(w, uri = "content://x/2", pinnedAt = 6_000L))
        assertEquals(2, photoDao.countForWalk(w))

        walkDao.delete(walkDao.getById(w)!!)

        assertEquals(0, photoDao.countForWalk(w))
    }

    @Test
    fun `insertAll commits a batch and returns ids`() = runTest {
        val w = newWalk()
        val ids = photoDao.insertAll(
            listOf(
                samplePhoto(w, uri = "content://x/1", pinnedAt = 1_000L),
                samplePhoto(w, uri = "content://x/2", pinnedAt = 2_000L),
                samplePhoto(w, uri = "content://x/3", pinnedAt = 3_000L),
            ),
        )

        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0 })
        assertEquals(3, photoDao.countForWalk(w))
    }

    @Test
    fun `entity invariant rejects zero or negative walkId`() {
        try {
            WalkPhoto(walkId = 0L, photoUri = "content://x", pinnedAt = 1L)
            org.junit.Assert.fail("expected IllegalArgumentException for walkId == 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `entity invariant rejects blank photoUri`() {
        try {
            WalkPhoto(walkId = 1L, photoUri = "  ", pinnedAt = 1L)
            org.junit.Assert.fail("expected IllegalArgumentException for blank photoUri")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `entity invariant rejects non-positive pinnedAt`() {
        try {
            WalkPhoto(walkId = 1L, photoUri = "content://x", pinnedAt = 0L)
            org.junit.Assert.fail("expected IllegalArgumentException for pinnedAt == 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // --- Stage 7-B: analysis fields --------------------------------------

    @Test
    fun `updateAnalysis writes label confidence and analyzedAt`() = runTest {
        val w = newWalk()
        val id = photoDao.insert(samplePhoto(w))

        photoDao.updateAnalysis(
            id = id,
            label = "Plant",
            confidence = 0.82,
            analyzedAt = 7_777L,
        )

        val read = photoDao.getById(id)
        assertEquals("Plant", read?.topLabel)
        assertEquals(0.82, read?.topLabelConfidence ?: 0.0, 0.0001)
        assertEquals(7_777L, read?.analyzedAt)
    }

    @Test
    fun `updateAnalysis accepts null label and confidence with analyzedAt set`() = runTest {
        // Tombstone path: a photo that was analyzed but produced no
        // label (URI unreadable, or labeler returned no result above
        // threshold) must still be markable as analyzed so the worker
        // doesn't retry forever.
        val w = newWalk()
        val id = photoDao.insert(samplePhoto(w))

        photoDao.updateAnalysis(
            id = id,
            label = null,
            confidence = null,
            analyzedAt = 9_999L,
        )

        val read = photoDao.getById(id)
        assertNull(read?.topLabel)
        assertNull(read?.topLabelConfidence)
        assertEquals(9_999L, read?.analyzedAt)
    }

    @Test
    fun `getPendingAnalysisForWalk returns only analyzed_at IS NULL rows in pin order`() = runTest {
        val w = newWalk()
        val id1 = photoDao.insert(samplePhoto(w, uri = "content://x/1", pinnedAt = 1_000L))
        val id2 = photoDao.insert(samplePhoto(w, uri = "content://x/2", pinnedAt = 2_000L))
        val id3 = photoDao.insert(samplePhoto(w, uri = "content://x/3", pinnedAt = 3_000L))

        // Mark the middle one as analyzed.
        photoDao.updateAnalysis(id2, label = "Wall", confidence = 0.7, analyzedAt = 4_000L)

        val pending = photoDao.getPendingAnalysisForWalk(w)

        assertEquals(listOf(id1, id3), pending.map { it.id })
        assertTrue(pending.all { it.analyzedAt == null })
    }

    @Test
    fun `entity invariant rejects half-populated analysis pair`() {
        try {
            WalkPhoto(
                walkId = 1L,
                photoUri = "content://x",
                pinnedAt = 1L,
                topLabel = "Plant",
                topLabelConfidence = null,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for half-populated analysis")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `entity invariant rejects confidence out of range`() {
        try {
            WalkPhoto(
                walkId = 1L,
                photoUri = "content://x",
                pinnedAt = 1L,
                topLabel = "Plant",
                topLabelConfidence = 1.5,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for confidence > 1.0")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `entity invariant rejects zero analyzedAt`() {
        try {
            WalkPhoto(
                walkId = 1L,
                photoUri = "content://x",
                pinnedAt = 1L,
                analyzedAt = 0L,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for analyzedAt == 0")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `getPendingAnalysisForWalk returns empty when all rows are analyzed`() = runTest {
        val w = newWalk()
        val id = photoDao.insert(samplePhoto(w))
        photoDao.updateAnalysis(id, label = null, confidence = null, analyzedAt = 10L)

        assertTrue(photoDao.getPendingAnalysisForWalk(w).isEmpty())
    }
}
