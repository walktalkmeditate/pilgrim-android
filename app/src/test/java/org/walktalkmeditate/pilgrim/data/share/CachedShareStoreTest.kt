// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CachedShareStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val store = CachedShareStore(context, json)

    @After
    fun cleanup() {
        File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
    }

    private fun sample(url: String = "https://walk.pilgrimapp.org/abc123") = CachedShare(
        url = url,
        id = "abc123",
        expiryEpochMs = 1_800_000_000_000L,
        shareDateEpochMs = 1_700_000_000_000L,
        expiryOption = ExpiryOption.Season,
    )

    @Test
    fun `observe returns null when no cache exists for the walk`() = runBlocking {
        assertNull(store.observe("nonexistent-uuid-xyz").first())
    }

    @Test
    fun `put then observe round-trips the CachedShare fields`() = runBlocking {
        val uuid = "abcd1234-5678-9abc-def0-0123456789ab"
        store.put(uuid, sample())
        val observed = store.observe(uuid).first()
        assertEquals(sample(), observed)
    }

    @Test
    fun `observe caches are walk-scoped — a different walk's key returns null`() = runBlocking {
        val uuidA = "aaaaaaaa-0000-0000-0000-000000000000"
        val uuidB = "bbbbbbbb-0000-0000-0000-000000000000"
        store.put(uuidA, sample(url = "https://walk.pilgrimapp.org/a"))
        assertNull(store.observe(uuidB).first())
        assertEquals(
            "https://walk.pilgrimapp.org/a",
            store.observe(uuidA).first()?.url,
        )
    }

    @Test
    fun `clear removes the cached share for the walk`() = runBlocking {
        val uuid = "cdcdcdcd-0000-0000-0000-000000000000"
        store.put(uuid, sample())
        assertEquals(sample(), store.observe(uuid).first())
        store.clear(uuid)
        assertNull(store.observe(uuid).first())
    }

    @Test
    fun `expiry option is null when cacheKey is absent`() = runBlocking {
        val uuid = "efefefef-0000-0000-0000-000000000000"
        val noOption = sample().copy(expiryOption = null)
        store.put(uuid, noOption)
        assertNull(store.observe(uuid).first()?.expiryOption)
    }
}
