// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.walktalkmeditate.pilgrim.data.PhotoPinRef
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhotoAnalysisRunnerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var labeler: FakePhotoLabeler
    private lateinit var bitmapLoader: FakeBitmapLoader
    private lateinit var runner: PhotoAnalysisRunner

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
        labeler = FakePhotoLabeler()
        bitmapLoader = FakeBitmapLoader()
        runner = PhotoAnalysisRunner(repository, labeler, bitmapLoader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedWalkWithPinnedPhotos(
        uris: List<String>,
    ): Pair<Long, List<Long>> {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        val result = repository.pinPhotos(
            walkId = walk.id,
            refs = uris.map { PhotoPinRef(it, takenAt = null) },
            pinnedAt = 2_000L,
        )
        return walk.id to result.insertedIds
    }

    @Test
    fun `happy path labels all pending photos`() = runTest {
        labeler.nextResult = listOf(
            LabeledResult("Plant", 0.82),
            LabeledResult("Flower", 0.65),
        )
        val (walkId, ids) = seedWalkWithPinnedPhotos(
            listOf("content://x/1", "content://x/2"),
        )

        val outcome = runner.analyzePending(walkId, clock = { 5_000L })

        assertEquals(2, outcome.getOrNull())
        val photos = repository.observePhotosFor(walkId).let { flow ->
            // Synchronous peek via first-run of the suspend list getter
            db.walkPhotoDao().getForWalk(walkId)
        }
        assertEquals(2, photos.size)
        photos.forEach { photo ->
            assertEquals("Plant", photo.topLabel)
            assertEquals(0.82, photo.topLabelConfidence ?: 0.0, 0.0001)
            assertEquals(5_000L, photo.analyzedAt)
        }
        assertEquals(ids.sorted(), photos.map { it.id }.sorted())
    }

    @Test
    fun `unreadable URI stamps analyzedAt with null label`() = runTest {
        val unreadableUri = "content://broken/1"
        bitmapLoader.unreadableUris += unreadableUri
        val (walkId, _) = seedWalkWithPinnedPhotos(listOf(unreadableUri))

        val outcome = runner.analyzePending(walkId, clock = { 7_777L })

        assertEquals(1, outcome.getOrNull())
        val photo = db.walkPhotoDao().getForWalk(walkId).single()
        assertNull(photo.topLabel)
        assertNull(photo.topLabelConfidence)
        assertEquals(7_777L, photo.analyzedAt)
    }

    @Test
    fun `labeler throwing writes null label with analyzedAt`() = runTest {
        labeler.throwOnLabel = RuntimeException("simulated labeler failure")
        val (walkId, _) = seedWalkWithPinnedPhotos(listOf("content://x/1"))

        val outcome = runner.analyzePending(walkId, clock = { 9_999L })

        assertEquals(1, outcome.getOrNull())
        val photo = db.walkPhotoDao().getForWalk(walkId).single()
        assertNull(photo.topLabel)
        assertNull(photo.topLabelConfidence)
        assertEquals(9_999L, photo.analyzedAt)
    }

    @Test
    fun `empty labeler result stamps analyzedAt with null label`() = runTest {
        labeler.nextResult = emptyList()
        val (walkId, _) = seedWalkWithPinnedPhotos(listOf("content://x/1"))

        val outcome = runner.analyzePending(walkId, clock = { 1_234L })

        assertEquals(1, outcome.getOrNull())
        val photo = db.walkPhotoDao().getForWalk(walkId).single()
        assertNull(photo.topLabel)
        assertNull(photo.topLabelConfidence)
        assertEquals(1_234L, photo.analyzedAt)
    }

    @Test
    fun `empty pending returns success zero`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        val outcome = runner.analyzePending(walk.id, clock = { 0L })

        assertTrue(outcome.isSuccess)
        assertEquals(0, outcome.getOrNull())
    }

    @Test
    fun `previously-analyzed rows are not re-processed`() = runTest {
        val (walkId, ids) = seedWalkWithPinnedPhotos(
            listOf("content://x/1", "content://x/2"),
        )
        // Pre-analyze the first photo.
        repository.updatePhotoAnalysis(
            photoId = ids.first(),
            label = "Already",
            confidence = 0.5,
            analyzedAt = 100L,
        )

        labeler.nextResult = listOf(LabeledResult("Fresh", 0.9))
        val outcome = runner.analyzePending(walkId, clock = { 200L })

        assertEquals(1, outcome.getOrNull())
        val rows = db.walkPhotoDao().getForWalk(walkId)
        val preAnalyzed = rows.single { it.id == ids.first() }
        val freshlyAnalyzed = rows.single { it.id == ids.last() }
        assertEquals("Already", preAnalyzed.topLabel)
        assertEquals(100L, preAnalyzed.analyzedAt)
        assertEquals("Fresh", freshlyAnalyzed.topLabel)
        assertEquals(200L, freshlyAnalyzed.analyzedAt)
    }
}
