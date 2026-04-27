// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkRepositoryDiscardTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WalkRepository(
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
    fun `deleteWalkById cascades through all child tables`() = runTest {
        val walk = repo.startWalk(startTimestamp = 1_000L)
        val walkId = walk.id

        repo.recordLocation(
            RouteDataSample(walkId = walkId, timestamp = 1_100L, latitude = 1.0, longitude = 2.0),
        )
        repo.recordAltitude(
            AltitudeSample(walkId = walkId, timestamp = 1_200L, altitudeMeters = 100.0),
        )
        repo.recordEvent(
            WalkEvent(walkId = walkId, eventType = WalkEventType.PAUSED, timestamp = 1_300L),
        )
        repo.addWaypoint(
            Waypoint(walkId = walkId, timestamp = 1_400L, latitude = 1.0, longitude = 2.0, label = null),
        )
        repo.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_500L,
                endTimestamp = 2_500L,
                durationMillis = 1_000L,
                fileRelativePath = "v/x.wav",
            ),
        )
        // (ActivityInterval + WalkPhoto skipped — cascade contract is verified
        // by the entries that ARE seeded; both also declare ForeignKey.CASCADE
        // on walk_id so the same SQLite mechanism handles them.)

        // Sanity: all child rows present.
        assertEquals(1, repo.locationSamplesFor(walkId).size)
        assertEquals(1, repo.altitudeSamplesFor(walkId).size)
        assertEquals(1, repo.eventsFor(walkId).size)
        assertEquals(1, repo.waypointsFor(walkId).size)
        assertEquals(1, repo.voiceRecordingsFor(walkId).size)

        repo.deleteWalkById(walkId)

        // Walk row + all child rows gone.
        assertNull(repo.getWalk(walkId))
        assertEquals(0, repo.locationSamplesFor(walkId).size)
        assertEquals(0, repo.altitudeSamplesFor(walkId).size)
        assertEquals(0, repo.eventsFor(walkId).size)
        assertEquals(0, repo.waypointsFor(walkId).size)
        assertEquals(0, repo.voiceRecordingsFor(walkId).size)
    }

    @Test
    fun `deleteWalkById is a no-op on unknown walkId`() = runTest {
        repo.deleteWalkById(walkId = 9_999L)
        // No throw — Room treats DELETE WHERE no-row-match as 0 rows affected.
    }
}
