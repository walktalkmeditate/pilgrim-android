// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
class ThreadsPreferencesTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "threads-prefs-test-${UUID.randomUUID()}"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        storeFile = File(context.filesDir, "datastore/$name.preferences_pb")
        storeFile.parentFile?.mkdirs()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    /**
     * A committed DataStore write reaches the repository's StateFlow only
     * once its `stateIn` collector — running on the real `Dispatchers.Default`
     * scope, not this test's — has processed the emission. `runBlocking` is
     * what bridges to that real dispatch; `withTimeout` under `runTest`'s
     * virtual clock would not bound wall-clock work. The bound is generous
     * enough that only a genuine regression (a value that never arrives)
     * spends it, so a broken expectation fails rather than hangs.
     */
    private fun <T> awaitValue(flow: StateFlow<T>, predicate: (T) -> Boolean) {
        runBlocking { withTimeout(5_000) { flow.first(predicate) } }
    }

    @Test
    fun `threadsAfterWalks defaults to true on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertTrue(repo.threadsAfterWalks.value)
    }

    @Test
    fun `importGeneration defaults to zero on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(0, repo.importGeneration.value)
    }

    @Test
    fun `setThreadsAfterWalks persists across instances`() = runTest {
        val repo1 = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo1.setThreadsAfterWalks(false)
        // No wait needed here: setThreadsAfterWalks suspends until the write
        // is committed, so repo2 already sees the persisted value on disk.
        // Only repo2's own StateFlow has propagation left to wait for.
        val repo2 = DataStoreThreadsPreferencesRepository(dataStore, scope)
        awaitValue(repo2.threadsAfterWalks) { !it }
        assertFalse(repo2.threadsAfterWalks.value)
    }

    @Test
    fun `bumpImportGeneration increments by exactly one per call`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.bumpImportGeneration()
        awaitValue(repo.importGeneration) { it == 1 }
        assertEquals(1, repo.importGeneration.value)
        repo.bumpImportGeneration()
        awaitValue(repo.importGeneration) { it == 2 }
        assertEquals(2, repo.importGeneration.value)
    }

    @Test
    fun `clearMoonLineIndex removes the key without touching other prefs`() = runTest {
        val moonKey = intPreferencesKey("threadsMoonLineLastLunationIndex")
        val threadsAfterWalksKey = booleanPreferencesKey("threadsAfterWalks")
        dataStore.edit { it[moonKey] = 7 }
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setThreadsAfterWalks(false)

        repo.clearMoonLineIndex()

        // Survival is a did-NOT-change claim, so there is no value to await
        // for: both keys come off the one snapshot clearMoonLineIndex has
        // already committed. A StateFlow read would instead be racing its own
        // propagation, and could report the pre-clear value for a stray
        // removal that did happen.
        val prefs = dataStore.data.first()
        assertEquals(null, prefs[moonKey])
        assertEquals("unrelated prefs must survive the clear", false, prefs[threadsAfterWalksKey])
    }

    // ---- U9: moon-line last-reported lunation index ----

    @Test
    fun `moonLineLastLunationIndex is null (never shown) on a fresh install, not zero-defaulted`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.moonLineLastLunationIndex())
    }

    @Test
    fun `setMoonLineLastLunationIndex persists and is read fresh, not cached`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setMoonLineLastLunationIndex(0)
        assertEquals("a real index of 0 must read back as 0, not null", 0, repo.moonLineLastLunationIndex())

        repo.setMoonLineLastLunationIndex(42)
        assertEquals(42, repo.moonLineLastLunationIndex())
    }

    @Test
    fun `clearMoonLineIndex makes moonLineLastLunationIndex read null again`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setMoonLineLastLunationIndex(9)
        repo.clearMoonLineIndex()
        assertEquals(null, repo.moonLineLastLunationIndex())
    }

    // ---- U6: backfill completion + checkpoint keys ----

    @Test
    fun `backfillCompletedAtVersion is null on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(null, repo.backfillCompletedAtVersion())
    }

    @Test
    fun `backfillCompletedAtImportGeneration defaults to zero on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(0, repo.backfillCompletedAtImportGeneration())
    }

    @Test
    fun `setBackfillCompleted persists both the version and the import generation`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCompleted(version = 3, atImportGeneration = 5)

        assertEquals(3, repo.backfillCompletedAtVersion())
        assertEquals(5, repo.backfillCompletedAtImportGeneration())
    }

    @Test
    fun `clearBackfillCompleted resets the version to null without touching unrelated prefs`() = runTest {
        val threadsAfterWalksKey = booleanPreferencesKey("threadsAfterWalks")
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCompleted(version = 3, atImportGeneration = 5)
        repo.setThreadsAfterWalks(false)

        repo.clearBackfillCompleted()

        assertEquals(null, repo.backfillCompletedAtVersion())
        // Same did-NOT-change reasoning as `clearMoonLineIndex removes the
        // key...` above: read the committed snapshot, not the StateFlow.
        assertEquals(
            "unrelated prefs must survive the clear",
            false,
            dataStore.data.first()[threadsAfterWalksKey],
        )
    }

    @Test
    fun `backfillCheckpoint is empty on a fresh install`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        assertEquals(BackfillCheckpoint.EMPTY, repo.backfillCheckpoint())
    }

    @Test
    fun `setBackfillCheckpoint persists watermark, forImportGeneration, and atAnalysisVersion`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCheckpoint(BackfillCheckpoint(watermark = "u-050", forImportGeneration = 2, atAnalysisVersion = 7))

        assertEquals(BackfillCheckpoint("u-050", 2, 7), repo.backfillCheckpoint())
    }

    @Test
    fun `a null-watermark checkpoint round-trips (no clean prefix yet, but a checkpoint exists)`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCheckpoint(BackfillCheckpoint(watermark = "u-050", forImportGeneration = 2, atAnalysisVersion = 7))

        repo.setBackfillCheckpoint(BackfillCheckpoint(watermark = null, forImportGeneration = 2, atAnalysisVersion = 7))

        assertEquals(
            "overwriting with a null watermark must remove the stale one, not leave it behind",
            BackfillCheckpoint(null, 2, 7),
            repo.backfillCheckpoint(),
        )
    }

    @Test
    fun `clearBackfillCheckpoint resets to EMPTY`() = runTest {
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)
        repo.setBackfillCheckpoint(BackfillCheckpoint(watermark = "u-050", forImportGeneration = 2, atAnalysisVersion = 7))

        repo.clearBackfillCheckpoint()

        assertEquals(BackfillCheckpoint.EMPTY, repo.backfillCheckpoint())
    }

    @Test
    fun `a checkpoint persisted without atAnalysisVersion decodes as a version mismatch`() = runTest {
        // Simulates a checkpoint missing its version key on disk, matching
        // this repository's own key names (ThreadsPreferencesTest's
        // established pattern of duplicating verbatim key literals locally
        // rather than reaching into the production class's private
        // companion object — see `clearMoonLineIndex removes the key...`
        // above).
        val watermarkKey = stringPreferencesKey("backfillCheckpointWatermark")
        val importGenerationKey = intPreferencesKey("backfillCheckpointImportGeneration")
        dataStore.edit { prefs ->
            prefs[watermarkKey] = "u-024"
            prefs[importGenerationKey] = 0
        }
        val repo = DataStoreThreadsPreferencesRepository(dataStore, scope)

        val decoded = repo.backfillCheckpoint()

        assertEquals("u-024", decoded.watermark)
        assertEquals(0, decoded.forImportGeneration)
        assertTrue(
            "a checkpoint with no recorded version must never coincide with a real ANALYSIS_VERSION",
            decoded.atAnalysisVersion != TranscriptContext.ANALYSIS_VERSION,
        )
    }
}
