// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.Walk

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WalkRepositoryActivitySumsTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PilgrimDatabase::class.java,
        ).allowMainThreadQueries().build()
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
    fun tearDown() = db.close()

    @Test
    fun `activitySumsFor returns zero talk and meditationSeconds when populated`() = runBlocking {
        val walk = Walk(startTimestamp = 0L, endTimestamp = 1000L, meditationSeconds = 600L)
        val (talk, meditate) = repo.activitySumsFor(walkId = 1L, walk = walk)
        assertEquals(0L, talk)
        assertEquals(600L, meditate)
    }

    @Test
    fun `activitySumsFor returns zero meditate when meditationSeconds null`() = runBlocking {
        val walk = Walk(startTimestamp = 0L, endTimestamp = 1000L, meditationSeconds = null)
        val (talk, meditate) = repo.activitySumsFor(walkId = 1L, walk = walk)
        assertEquals(0L, talk)
        assertEquals(0L, meditate)
    }

    @Test
    fun `walkEventsFor returns empty list when no events recorded`() = runBlocking {
        val events = repo.walkEventsFor(walkId = 999L)
        assertEquals(emptyList<Any>(), events)
    }
}
