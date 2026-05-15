// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.intention

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataStoreIntentionHistoryRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.cacheDir, "intention-test-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    private fun repo() = DataStoreIntentionHistoryRepository(dataStore, scope)

    @Test
    fun `default is empty`() = runTest(dispatcher) {
        assertEquals(emptyList<String>(), repo().intentions.first())
    }

    @Test
    fun `add prepends most-recent-first`() = runTest(dispatcher) {
        val r = repo()
        r.add("first")
        r.add("second")
        assertEquals(listOf("second", "first"), r.intentions.first())
    }

    @Test
    fun `add trims whitespace`() = runTest(dispatcher) {
        val r = repo()
        r.add("  walk well  ")
        assertEquals(listOf("walk well"), r.intentions.first())
    }

    @Test
    fun `blank input is ignored`() = runTest(dispatcher) {
        val r = repo()
        r.add("   ")
        r.add("")
        assertEquals(emptyList<String>(), r.intentions.first())
    }

    @Test
    fun `re-adding an existing intention moves it to the front`() = runTest(dispatcher) {
        val r = repo()
        r.add("a")
        r.add("b")
        r.add("c")
        r.add("a")
        assertEquals(listOf("a", "c", "b"), r.intentions.first())
    }

    @Test
    fun `caps at five most-recent`() = runTest(dispatcher) {
        val r = repo()
        listOf("1", "2", "3", "4", "5", "6", "7").forEach { r.add(it) }
        assertEquals(listOf("7", "6", "5", "4", "3"), r.intentions.first())
    }

    @Test
    fun `clear empties the history`() = runTest(dispatcher) {
        val r = repo()
        r.add("a")
        r.clear()
        assertEquals(emptyList<String>(), r.intentions.first())
    }

    @Test
    fun `persists across repository instances`() = runTest(dispatcher) {
        repo().add("durable")
        assertEquals(listOf("durable"), repo().intentions.first())
    }
}
