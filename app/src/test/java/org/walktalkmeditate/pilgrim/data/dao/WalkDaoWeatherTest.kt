// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.Walk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkDaoWeatherTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var walkDao: WalkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateWeather_writesAllFields() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000L))

        walkDao.updateWeather(
            id = id,
            condition = "clear",
            temperature = 18.5,
            humidity = 0.62,
            windSpeed = 3.1,
        )

        val updated = walkDao.getById(id)
        assertNotNull(updated)
        assertEquals("clear", updated?.weatherCondition)
        assertEquals(18.5, updated?.weatherTemperature ?: 0.0, 0.0001)
        assertEquals(0.62, updated?.weatherHumidity ?: 0.0, 0.0001)
        assertEquals(3.1, updated?.weatherWindSpeed ?: 0.0, 0.0001)
    }

    @Test
    fun updateWeather_supportsNullableHumidityAndWindSpeed() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000L))

        walkDao.updateWeather(
            id = id,
            condition = "fog",
            temperature = 5.0,
            humidity = null,
            windSpeed = null,
        )

        val updated = walkDao.getById(id)
        assertNotNull(updated)
        assertEquals("fog", updated?.weatherCondition)
        assertEquals(5.0, updated?.weatherTemperature ?: 0.0, 0.0001)
        assertNull(updated?.weatherHumidity)
        assertNull(updated?.weatherWindSpeed)
    }
}
