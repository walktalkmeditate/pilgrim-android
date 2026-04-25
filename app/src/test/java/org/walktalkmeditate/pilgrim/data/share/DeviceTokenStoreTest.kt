// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceTokenStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = DeviceTokenStore(context)

    @After
    fun cleanup() {
        // DataStore writes a `<name>.preferences_pb` file under
        // `<dataDir>/files/datastore/`. Remove it so tests don't
        // observe each other's tokens.
        val dir = File(context.filesDir, "datastore")
        dir.resolve("share_device_token.preferences_pb").delete()
    }

    @Test
    fun `getToken generates a UUID on first call`() = runBlocking {
        val token = store.getToken()
        assertTrue(
            "expected UUID-shape token, got '$token'",
            token.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")),
        )
    }

    @Test
    fun `getToken is idempotent — second call returns the same token`() = runBlocking {
        val t1 = store.getToken()
        val t2 = store.getToken()
        assertEquals(t1, t2)
    }

    @Test
    fun `getToken persists across store instances`() = runBlocking {
        val first = store.getToken()
        // New store instance, same DataStore file → same token.
        val reopened = DeviceTokenStore(context)
        val second = reopened.getToken()
        assertEquals(first, second)
    }
}
