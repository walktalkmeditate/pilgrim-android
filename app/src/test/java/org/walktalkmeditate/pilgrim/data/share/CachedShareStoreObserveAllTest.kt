// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CachedShareStoreObserveAllTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `observeAll emits keys reconstructed with hyphens`() = runBlocking {
        // Over its OWN DataStore file, not the process-wide named one
        // [CachedShareStore]'s Context constructor reaches for: this is
        // the only test that asserts over the WHOLE key space, and the
        // `preferencesDataStore` delegate is shared per classloader, so
        // any sibling test class caching a share in the same Gradle fork
        // (WalkShareInteractiveTest caches one per share it drives) would
        // otherwise inflate the count.
        val store = CachedShareStore(
            PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { File(context.filesDir, "datastore/observe-all-${UUID.randomUUID()}.preferences_pb") },
            ),
            Json { ignoreUnknownKeys = true },
        )
        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()
        store.put(uuid1, sample(uuid1))
        store.put(uuid2, sample(uuid2))
        val map = store.observeAll().first()
        assertTrue("contains uuid1", map.containsKey(uuid1))
        assertTrue("contains uuid2", map.containsKey(uuid2))
        assertEquals(2, map.size)
    }

    private fun sample(uuid: String) = CachedShare(
        url = "https://walk.pilgrimapp.org/share/$uuid",
        id = uuid,
        expiryEpochMs = Long.MAX_VALUE,
        shareDateEpochMs = 0L,
        expiryOption = null,
    )
}
