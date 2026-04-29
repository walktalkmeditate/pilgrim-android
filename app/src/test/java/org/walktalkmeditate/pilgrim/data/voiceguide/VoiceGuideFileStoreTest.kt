// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlin.time.Duration.Companion.seconds
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideFileStoreTest {

    private lateinit var context: Application
    private lateinit var store: VoiceGuideFileStore

    private val promptsRoot: File
        get() = File(context.filesDir, "voice_guide_prompts")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        promptsRoot.deleteRecursively()
        store = VoiceGuideFileStore(context)
    }

    @After fun tearDown() {
        promptsRoot.deleteRecursively()
    }

    private fun prompt(r2Key: String, size: Long) = VoiceGuidePrompt(
        id = r2Key.substringAfterLast('/'),
        seq = 1,
        durationSec = 1.0,
        fileSizeBytes = size,
        r2Key = r2Key,
    )

    private fun pack(
        id: String,
        prompts: List<VoiceGuidePrompt>,
        meditationPrompts: List<VoiceGuidePrompt>? = null,
    ) = VoiceGuidePack(
        id = id,
        version = "1",
        name = id,
        tagline = "",
        description = "",
        theme = "",
        iconName = "",
        type = "walk",
        walkTypes = emptyList(),
        scheduling = PromptDensity(0, 0, 0, 0, 0),
        totalDurationSec = 0.0,
        totalSizeBytes = 0L,
        prompts = prompts,
        meditationPrompts = meditationPrompts,
    )

    @Test fun `empty pack is considered downloaded`() {
        val p = pack("empty", emptyList())
        assertTrue(store.isPackDownloaded(p))
        assertTrue(store.missingPrompts(p).isEmpty())
    }

    @Test fun `missing file marks pack not-downloaded`() {
        val p = pack("p", listOf(prompt("p/a.aac", 10)))
        assertFalse(store.isPackDownloaded(p))
        assertEquals(1, store.missingPrompts(p).size)
    }

    @Test fun `file with correct size marks prompt available`() {
        val pr = prompt("p/a.aac", 10)
        store.fileForPrompt(pr.r2Key).writeBytes(ByteArray(10))
        assertTrue(store.isPromptAvailable(pr))
    }

    @Test fun `file with wrong size marks prompt unavailable`() {
        val pr = prompt("p/a.aac", 10)
        store.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        assertFalse(store.isPromptAvailable(pr))
    }

    @Test fun `fileForPrompt creates parent directories`() {
        val f = store.fileForPrompt("deeply/nested/pack/prompt.aac")
        assertTrue(f.parentFile?.exists() == true)
    }

    @Test fun `meditation prompts counted in isPackDownloaded`() {
        val walk = prompt("p/w.aac", 5)
        val med = prompt("p/m.aac", 5)
        val p = pack("p", listOf(walk), listOf(med))

        store.fileForPrompt(walk.r2Key).writeBytes(ByteArray(5))
        assertFalse(store.isPackDownloaded(p))

        store.fileForPrompt(med.r2Key).writeBytes(ByteArray(5))
        assertTrue(store.isPackDownloaded(p))
    }

    @Test fun `missingPrompts filters already-available files`() {
        val a = prompt("p/a.aac", 5)
        val b = prompt("p/b.aac", 5)
        val p = pack("p", listOf(a, b))

        store.fileForPrompt(a.r2Key).writeBytes(ByteArray(5))
        val missing = store.missingPrompts(p)
        assertEquals(1, missing.size)
        assertEquals("b.aac", missing.first().id)
    }

    @Test fun `deletePack removes directory and emits invalidation`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val p = pack("p", listOf(pr))
        store.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        assertTrue(store.isPackDownloaded(p))

        store.invalidations.test(timeout = 5.seconds) {
            store.deletePack(p)
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(File(promptsRoot, "p").exists())
        assertFalse(store.isPackDownloaded(p))
    }

    @Test fun `deletePack on absent pack still emits invalidation`() = runTest {
        val p = pack("never-downloaded", emptyList())
        store.invalidations.test(timeout = 5.seconds) {
            store.deletePack(p)
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
