// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkState
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl

/**
 * The one walk-delete path: every way a walk row goes removes the walk's
 * live Honor rows and its staged Way, and keeps its link file and marker,
 * as iOS keeps a walk's link until its Way is deleted (parity spec A §23).
 * The archive strip and the tended replace are covered in
 * `PilgrimPackageImporterTest`, beside the archives they need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkRepositoryDeleteTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var db: PilgrimDatabase
    private lateinit var store: WayStore
    private lateinit var repository: WalkRepository
    private lateinit var honor: HonorWalkState

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = WayStore({ File(folder.root, "Ways") })
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
            wayStore = store,
        )
        honor = HonorWalkState(db, store)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun honorWalk(): Walk {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        honor.seed(walk.id, walk.uuid)
        return walk
    }

    @Test
    fun `deleteWalk removes the live Honor rows and staging and keeps the link and marker`() = runTest {
        val walk = honorWalk()

        repository.deleteWalk(walk)

        assertNull(repository.getWalk(walk.id))
        honor.assertReleased(walk.id, walk.uuid)
    }

    @Test
    fun `deleteWalkById removes the live Honor rows and staging and keeps the link and marker`() = runTest {
        val walk = honorWalk()

        repository.deleteWalkById(walk.id)

        assertNull(repository.getWalk(walk.id))
        honor.assertReleased(walk.id, walk.uuid)
    }

    @Test
    fun `a discard's PurgeWalk goes through the same path`() = runTest {
        val controller = WalkControllerImpl(repository, Clock { 1_000L }, fakeStepCounter(), FixedReleaseFlags(honor = true))
        val walk = controller.startWalk()
        honor.seed(walk.id, walk.uuid)

        controller.discardWalk()

        assertNull(repository.getWalk(walk.id))
        honor.assertReleased(walk.id, walk.uuid)
    }

    @Test
    fun `deleting one walk leaves another walk's Honor state alone`() = runTest {
        val gone = honorWalk()
        val kept = honorWalk()

        repository.deleteWalkById(gone.id)

        honor.assertLive(kept.id, kept.uuid)
    }

    @Test
    fun `deleting a missing walk changes nothing`() = runTest {
        val walk = honorWalk()

        repository.deleteWalkById(walkId = walk.id + 1_000)

        assertNotNull(repository.getWalk(walk.id))
        honor.assertLive(walk.id, walk.uuid)
    }

    @Test
    fun `a delete keeps the listed Way and the links other walks hold to it`() = runTest {
        val walk = honorWalk()
        store.save(honor.way())
        val earlier = "3f2a1b0c-9d8e-4f7a-a6b5-c4d3e2f1a0b9"
        store.link(earlier, HonorWalkState.WAY_ID, arrival = null)

        repository.deleteWalk(walk)

        assertEquals(honor.way(), store.load(HonorWalkState.WAY_ID))
        assertEquals(HonorWalkState.WAY_ID, store.wayId(earlier))
    }
}
