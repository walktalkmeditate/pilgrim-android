// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeFileStoreTest {

    private lateinit var context: Application
    private lateinit var store: SoundscapeFileStore

    private val soundscapeRoot: File
        get() = File(context.filesDir, "audio/soundscape")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        soundscapeRoot.deleteRecursively()
        store = SoundscapeFileStore(context)
    }

    @After fun tearDown() {
        soundscapeRoot.deleteRecursively()
    }

    private fun asset(id: String, size: Long = 100L) = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = id,
        displayName = id,
        durationSec = 120.0,
        r2Key = "soundscape/$id.aac",
        fileSizeBytes = size,
    )

    @Test fun `missing file marks asset not available`() {
        val a = asset("x", size = 100)
        assertFalse(store.isAvailable(a))
    }

    @Test fun `file with correct size marks asset available`() {
        val a = asset("x", size = 50)
        store.fileFor(a).writeBytes(ByteArray(50))
        assertTrue(store.isAvailable(a))
    }

    @Test fun `file with wrong size marks asset unavailable`() {
        val a = asset("x", size = 100)
        store.fileFor(a).writeBytes(ByteArray(50))
        assertFalse(store.isAvailable(a))
    }

    @Test fun `fileFor creates parent directories`() {
        val f = store.fileFor(asset("x"))
        assertTrue(f.parentFile?.exists() == true)
    }

    @Test fun `delete removes file and emits invalidation`() = runTest {
        val a = asset("x", size = 10)
        store.fileFor(a).writeBytes(ByteArray(10))
        assertTrue(store.isAvailable(a))

        store.invalidations.test {
            store.delete(a)
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(store.fileFor(a).exists())
        assertFalse(store.isAvailable(a))
    }

    @Test fun `delete on absent file still emits invalidation`() = runTest {
        val a = asset("never-downloaded")
        store.invalidations.test {
            store.delete(a)
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
