// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionAskedStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var store: PermissionAskedStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file = File(ctx.filesDir, "datastore/perm-asked-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = PermissionAskedStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `defaults to false for every permission`() = runTest {
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Location).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Microphone).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Motion).first())
    }

    @Test
    fun `markAsked persists per key`() = runTest {
        store.markAsked(PermissionAskedStore.Key.Microphone)
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Location).first())
        assertTrue(store.askedFlow(PermissionAskedStore.Key.Microphone).first())
        assertFalse(store.askedFlow(PermissionAskedStore.Key.Motion).first())
    }
}
