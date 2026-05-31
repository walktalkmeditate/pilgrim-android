// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the per-pack snapshot semantics introduced when the
 * voice-guide progress repo switched from walk-id keying to pack-id
 * keying (iOS parity — `VoiceGuideManagement.loadHistory` /
 * `persistHistory`). The earlier walk-id implementation cleared
 * progress on Finished, which made every walk re-open at the pack's
 * `seq=1` prompt; this test pins the new cross-walk behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideProgressRepositoryTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scope: CoroutineScope
    private lateinit var repo: DataStoreVoiceGuideProgressRepository

    @Before
    fun setUp() {
        val name = "vg_progress_${UUID.randomUUID()}"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(name) },
        )
        repo = DataStoreVoiceGuideProgressRepository(ds)
    }

    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun `load on a cold pack returns an empty set`() = runBlocking {
        assertEquals(emptySet<String>(), repo.load("sage"))
    }

    @Test
    fun `save then load round-trips the played set`() = runBlocking {
        repo.save("sage", setOf("sage_01", "sage_02"))
        assertEquals(setOf("sage_01", "sage_02"), repo.load("sage"))
    }

    @Test
    fun `save overwrites — the snapshot replaces, never merges`() = runBlocking {
        // Cycle scenario: scheduler clears `played` in memory when every
        // prompt has been used, then the next save must shrink the
        // stored set to match. An incremental implementation would
        // leave the pack permanently exhausted on disk.
        repo.save("sage", setOf("sage_01", "sage_02", "sage_03"))
        repo.save("sage", setOf("sage_01"))
        assertEquals(setOf("sage_01"), repo.load("sage"))
    }

    @Test
    fun `each pack keeps its own played set`() = runBlocking {
        repo.save("sage", setOf("sage_01"))
        repo.save("stone", setOf("stone_03"))
        assertEquals(setOf("sage_01"), repo.load("sage"))
        assertEquals(setOf("stone_03"), repo.load("stone"))
    }

    @Test
    fun `played set survives across walks for the same pack (iOS parity)`() = runBlocking {
        // Walk 1: opens with sage_01, completes it.
        repo.save("sage", setOf("sage_01"))
        // Walk 2: orchestrator loads pack-keyed progress on start.
        val onWalk2Start = repo.load("sage")
        assertEquals(
            "walk 2 must see walk 1's played set so it skips the opener",
            setOf("sage_01"),
            onWalk2Start,
        )
    }
}
