// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CachedShareStoreObserveAllTest {

    @Test
    fun `observeAll emits keys reconstructed with hyphens`() = runBlocking {
        val store = CachedShareStore(
            ApplicationProvider.getApplicationContext(),
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
