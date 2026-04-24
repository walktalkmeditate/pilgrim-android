// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiCacheSweeperTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() {
        EtegamiPngWriter.cacheRoot(context).deleteRecursively()
    }

    @Test
    fun `sweepStale deletes files older than the cutoff`() = runBlocking {
        val root = EtegamiPngWriter.cacheRoot(context)
        // Use a realistic epoch so 25h-earlier doesn't go negative on
        // File.setLastModified (rejected by the JDK).
        val now = 1_700_000_000_000L
        val stale = File(root, "stale.png").apply {
            writeText("stale")
            setLastModified(now - 25 * 3600 * 1000L)
        }
        val fresh = File(root, "fresh.png").apply {
            writeText("fresh")
            setLastModified(now - 1 * 3600 * 1000L)
        }
        val deleted = EtegamiCacheSweeper.sweepStale(context, olderThan = 24.hours, now = { now })
        assertEquals(1, deleted)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `sweepStale tolerates an empty root`() = runBlocking {
        val count = EtegamiCacheSweeper.sweepStale(context)
        assertEquals(0, count)
    }

    @Test
    fun `sweepStale with zero duration deletes everything without throwing`() = runBlocking {
        val root = EtegamiPngWriter.cacheRoot(context)
        val now = 1_700_000_000_000L
        // Freshly-written files get lastModified = wall-clock now. Set
        // them explicitly so `now()` callback controls the relation.
        File(root, "a.png").apply { writeText("x"); setLastModified(now - 1000L) }
        File(root, "b.png").apply { writeText("y"); setLastModified(now - 1000L) }
        val deleted = EtegamiCacheSweeper.sweepStale(context, olderThan = 0.hours, now = { now })
        assertEquals(2, deleted)
    }
}
