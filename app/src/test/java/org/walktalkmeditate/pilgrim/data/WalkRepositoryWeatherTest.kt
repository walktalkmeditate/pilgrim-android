// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkRepositoryWeatherTest {

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
    fun `updateWeather persists all four columns from snapshot`() = runTest {
        val walk = repo.startWalk(startTimestamp = 1_000L)

        repo.updateWeather(
            walkId = walk.id,
            snapshot = WeatherSnapshot(
                condition = WeatherCondition.LIGHT_RAIN,
                temperatureCelsius = 12.4,
                humidityFraction = 0.78,
                windSpeedMps = 4.2,
            ),
        )

        val updated = repo.getWalk(walk.id)
        assertNotNull(updated)
        assertEquals(WeatherCondition.LIGHT_RAIN.rawValue, updated?.weatherCondition)
        assertEquals(12.4, updated?.weatherTemperature ?: 0.0, 0.0001)
        assertEquals(0.78, updated?.weatherHumidity ?: 0.0, 0.0001)
        assertEquals(4.2, updated?.weatherWindSpeed ?: 0.0, 0.0001)
    }
}
