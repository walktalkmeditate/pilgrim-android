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
class WalkDaoCacheColsTest {

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
    fun updateAggregates_writesBothFields() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000L))

        walkDao.updateAggregates(id, distanceMeters = 1234.5, meditationSeconds = 600L)

        val read = walkDao.getById(id)
        assertNotNull(read)
        assertEquals(1234.5, read?.distanceMeters ?: 0.0, 0.0001)
        assertEquals(600L, read?.meditationSeconds)
    }

    @Test
    fun updateAggregates_supportsNullValues() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 1_000L))

        walkDao.updateAggregates(id, distanceMeters = null, meditationSeconds = null)

        val read = walkDao.getById(id)
        assertNotNull(read)
        assertNull(read?.distanceMeters)
        assertNull(read?.meditationSeconds)
    }
}
