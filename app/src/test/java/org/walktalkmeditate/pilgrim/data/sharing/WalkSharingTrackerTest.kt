// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sharing

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSharingTrackerTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tracker: WalkSharingTracker
    private val scopeJob: Job = SupervisorJob()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storeName = "wst-${UUID.randomUUID()}"
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(storeName) },
        )
        tracker = WalkSharingTracker(dataStore)
    }

    @After
    fun tearDown() {
        scopeJob.cancel()
    }

    @Test
    fun hasShared_returnsFalseForUnknownUuid() = runTest {
        assertFalse(tracker.hasShared("unknown-uuid"))
    }

    @Test
    fun markShared_persistsAndHasSharedReturnsTrue() = runTest {
        tracker.markShared("uuid-1")
        advanceUntilIdle()
        assertTrue(tracker.hasShared("uuid-1"))
    }

    @Test
    fun markShared_isIdempotent() = runTest {
        tracker.markShared("uuid-2")
        tracker.markShared("uuid-2")
        advanceUntilIdle()
        assertTrue(tracker.hasShared("uuid-2"))
    }

    @Test
    fun markShared_doesNotAffectOtherUuids() = runTest {
        tracker.markShared("uuid-3")
        advanceUntilIdle()
        assertFalse(tracker.hasShared("uuid-4"))
    }
}
