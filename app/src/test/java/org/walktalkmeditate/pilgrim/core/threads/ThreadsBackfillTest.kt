// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.data.dao.TranscribedRecordingSnapshot

/**
 * [ThreadsBackfillRunner.sweep] state machine — U6 pinned semantics from
 * the parity spec's ThreadsBackfill section (BEH-24..31, DAT-36..39,
 * EDG-58..61): prune stale-version orphans before the sweep, batch-25
 * with a per-batch gate re-check, completion recorded only when every
 * snapshot item is accounted for (a tombstoned save counts), and the
 * Android-original checkpoint + import-generation re-arm this port adds
 * because the sweep runs under WorkManager (crosses process death)
 * rather than iOS's live app process.
 *
 * Wiring mirrors [TranscriptContextAnalyzerTest]: a REAL
 * [TranscriptContextStore] (Robolectric filesDir) and REAL
 * [ThreadsAnalysisEnvironment] (real WordNet + VADER assets), with only
 * the language client and the snapshot/gate test seams faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsBackfillTest {

    private lateinit var store: TranscriptContextStore
    private lateinit var environment: ThreadsAnalysisEnvironment
    private lateinit var preferences: FakeThreadsPreferencesRepository
    private lateinit var analyzer: TranscriptContextAnalyzer
    private lateinit var runner: ThreadsBackfillRunner
    private val allowGate: suspend () -> Boolean = { true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
        environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json))
        preferences = FakeThreadsPreferencesRepository()
        val languageClient = MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(LanguageGuess("en", 0.99f))
            },
        )
        analyzer = TranscriptContextAnalyzer(store, environment, languageClient, preferences)
        runner = ThreadsBackfillRunner(store, analyzer, preferences)
    }

    private fun snapshotOf(count: Int, prefix: String = "u"): List<TranscribedRecordingSnapshot> =
        (0 until count).map { i ->
            TranscribedRecordingSnapshot(
                uuid = "$prefix-%03d".format(i),
                transcription = "walking today I thought about the road ahead and where this path leads $i",
            )
        }

    // ---- fresh sweep ----

    @Test
    fun `fresh sweep analyzes every item and records completion`() = runTest {
        val items = snapshotOf(3)

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        for (item in items) assertTrue(store.hasCurrentContext(item.uuid))
        assertEquals(TranscriptContext.ANALYSIS_VERSION, preferences.backfillCompletedAtVersion())
        assertEquals(0, preferences.backfillCompletedAtImportGeneration())
    }

    @Test
    fun `an item that already has a current context is not re-analyzed`() = runTest {
        val items = snapshotOf(1)
        runner.sweep(snapshotProvider = { items }, gate = allowGate)
        val before = store.readRaw(items.single().uuid)

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertEquals(before, store.readRaw(items.single().uuid))
    }

    // ---- toggle-off inert ----

    @Test
    fun `toggle off returns ToggleOff without touching the store`() = runTest {
        preferences.setThreadsAfterWalks(false)
        val items = snapshotOf(2)

        val outcome = runner.sweep(snapshotProvider = { items }, gate = { error("gate must not be consulted while the toggle is off") })

        assertEquals(ThreadsBackfillOutcome.ToggleOff, outcome)
        for (item in items) assertFalse(store.hasContext(item.uuid))
        assertEquals(null, preferences.backfillCompletedAtVersion())
    }

    // ---- battery gate: worker run-start + per-batch ----

    @Test
    fun `gate closed at run start returns GateClosed without analyzing anything`() = runTest {
        val items = snapshotOf(2)

        val outcome = runner.sweep(snapshotProvider = { items }, gate = { false })

        assertEquals(ThreadsBackfillOutcome.GateClosed, outcome)
        for (item in items) assertFalse(store.hasContext(item.uuid))
    }

    @Test
    fun `gate closing mid-sweep stops the batch loop and leaves completion unset`() = runTest {
        // 30 items = batch 0 (0-24) + batch 1 (25-29). Gate admits exactly
        // one batch's worth of checks (the run-start check + the check
        // before batch 0), then closes before batch 1.
        val items = snapshotOf(30)
        var calls = 0
        val gate: suspend () -> Boolean = { calls++; calls <= 2 }

        val outcome = runner.sweep(snapshotProvider = { items }, gate = gate)

        assertEquals(ThreadsBackfillOutcome.GateClosed, outcome)
        assertEquals(null, preferences.backfillCompletedAtVersion())
        for (item in items.take(25)) assertTrue(store.hasCurrentContext(item.uuid))
        for (item in items.drop(25)) assertFalse(store.hasContext(item.uuid))
    }

    // ---- checkpoint resume after a simulated kill ----

    @Test
    fun `a fresh runner instance resumes from the persisted checkpoint instead of restarting`() = runTest {
        val items = snapshotOf(30)
        var calls = 0
        val gate: suspend () -> Boolean = { calls++; calls <= 2 }
        runner.sweep(snapshotProvider = { items }, gate = gate)
        assertEquals(25, preferences.backfillCheckpoint().processedCount)

        // Simulate a process kill: a brand-new runner instance (fresh
        // in-memory state), same persisted preferences + on-disk store.
        val revivedRunner = ThreadsBackfillRunner(store, analyzer, preferences)
        val outcome = revivedRunner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        for (item in items) assertTrue(store.hasCurrentContext(item.uuid))
    }

    @Test
    fun `a same-version checkpoint still resumes from the persisted processedCount instead of restarting`() = runTest {
        val items = snapshotOf(30)
        var calls = 0
        val gate: suspend () -> Boolean = { calls++; calls <= 2 }
        runner.sweep(snapshotProvider = { items }, gate = gate)
        assertEquals(25, preferences.backfillCheckpoint().processedCount)

        // A resume from index 25 has exactly one remaining batch
        // (25-29), so the per-batch gate is consulted once, plus the
        // sweep's unconditional entry check = 2 total. A restart from 0
        // would instead visit two batches (0-24, then 25-29), for 3 —
        // this count is what actually distinguishes "resumed" from
        // "restarted" here, since every item ends up accounted for
        // either way.
        val revivedRunner = ThreadsBackfillRunner(store, analyzer, preferences)
        var resumeGateCalls = 0
        val countingGate: suspend () -> Boolean = { resumeGateCalls++; true }
        val outcome = revivedRunner.sweep(snapshotProvider = { items }, gate = countingGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertEquals(
            "a same-version resume must process only the remaining batch, not restart from 0",
            2,
            resumeGateCalls,
        )
        for (item in items) assertTrue(store.hasCurrentContext(item.uuid))
    }

    // ---- checkpoint invalidated by an analysis-version bump ----

    @Test
    fun `a checkpoint whose analysis version predates a bump restarts from zero instead of resuming`() = runTest {
        // Reproduces the reviewer-proven gap: a sweep interrupted
        // mid-run persists a checkpoint, an ANALYSIS_VERSION bump lands,
        // and a naive resume that only checks forImportGeneration would
        // trust the stale processedCount — leaving the already-processed
        // prefix permanently stuck on stale-version contexts, even though
        // completion later records the NEW version.
        val items = snapshotOf(30)
        var calls = 0
        val gate: suspend () -> Boolean = { calls++; calls <= 2 }
        runner.sweep(snapshotProvider = { items }, gate = gate)
        val staleCheckpoint = preferences.backfillCheckpoint()
        assertEquals(25, staleCheckpoint.processedCount)

        // TranscriptContext.ANALYSIS_VERSION is a compile-time constant,
        // so a real version bump can't happen mid-test — simulate its
        // effect directly (the same "poke the persisted/stored state"
        // mechanism the version-stale-completion test below uses):
        // rewrite the already-processed items' stored contexts down to a
        // stale version, as a real bump would leave them, and rewrite the
        // checkpoint itself down to that same stale version, as it would
        // have been written before the (simulated) bump.
        for (item in items.take(25)) {
            val stored = store.readRaw(item.uuid)!!
            store.save(stored.copy(analysisVersion = TranscriptContext.ANALYSIS_VERSION - 1))
        }
        preferences.setBackfillCheckpoint(staleCheckpoint.copy(atAnalysisVersion = TranscriptContext.ANALYSIS_VERSION - 1))
        for (item in items.take(25)) assertFalse(store.hasCurrentContext(item.uuid))

        val revivedRunner = ThreadsBackfillRunner(store, analyzer, preferences)
        val outcome = revivedRunner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        for (item in items) {
            assertTrue(
                "every item must end up at the current analysis version, including the first 25 a stale checkpoint would have skipped",
                store.hasCurrentContext(item.uuid),
            )
        }
        assertEquals(TranscriptContext.ANALYSIS_VERSION, preferences.backfillCompletedAtVersion())
        assertEquals(0, preferences.backfillCompletedAtImportGeneration())
    }

    // ---- version-stale re-sweep ----

    @Test
    fun `a completion recorded at a stale ANALYSIS_VERSION does not short-circuit a fresh sweep`() = runTest {
        preferences.setBackfillCompleted(version = TranscriptContext.ANALYSIS_VERSION - 1, atImportGeneration = 0)
        val items = snapshotOf(2)

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertEquals(TranscriptContext.ANALYSIS_VERSION, preferences.backfillCompletedAtVersion())
        for (item in items) assertTrue(store.hasCurrentContext(item.uuid))
    }

    // ---- toggle re-enable re-arm (setEnabled(true) clears the key) ----

    @Test
    fun `clearing the completed key re-arms a real sweep on the next call`() = runTest {
        val items = snapshotOf(1)
        runner.sweep(snapshotProvider = { items }, gate = allowGate)

        preferences.clearBackfillCompleted()
        var gateCalls = 0
        val countingGate: suspend () -> Boolean = { gateCalls++; true }
        val outcome = runner.sweep(snapshotProvider = { items }, gate = countingGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertTrue("re-armed sweep must actually consult the gate, not short-circuit", gateCalls > 0)
    }

    // ---- import-generation re-arm ----

    @Test
    fun `an import landing after completion re-arms the sweep even though the version is still current`() = runTest {
        val items = snapshotOf(1)
        runner.sweep(snapshotProvider = { items }, gate = allowGate)
        assertEquals(0, preferences.backfillCompletedAtImportGeneration())

        // Before the bump: already complete at the current generation —
        // the gate must never be consulted (fast early-return).
        val outcomeBeforeImport = runner.sweep(
            snapshotProvider = { items },
            gate = { error("must short-circuit before an import lands") },
        )
        assertEquals(ThreadsBackfillOutcome.Completed, outcomeBeforeImport)

        preferences.bumpImportGeneration()
        var gateCalls = 0
        val countingGate: suspend () -> Boolean = { gateCalls++; true }
        val outcomeAfterImport = runner.sweep(snapshotProvider = { items }, gate = countingGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcomeAfterImport)
        assertTrue("import generation change must re-arm a real sweep pass", gateCalls > 0)
        assertEquals(1, preferences.backfillCompletedAtImportGeneration())
    }

    // ---- all-accounted completion, incl. tombstoned-counts-as-accounted ----

    @Test
    fun `a tombstoned recording counts as accounted even though no context file is ever written`() = runTest {
        val items = snapshotOf(1)
        store.insertTombstones(listOf(items.single().uuid))

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertFalse(
            "tombstone-blocked save must never actually write a file",
            store.hasContext(items.single().uuid),
        )
    }

    // ---- prune stale-version orphans before the sweep ----

    @Test
    fun `a stale-version orphan not present in the snapshot is pruned before the sweep runs`() = runTest {
        val orphanUuid = "orphan-stale"
        store.save(
            TranscriptContext(
                uuid = orphanUuid,
                languageCode = "en",
                wordCount = 5,
                themes = emptyList(),
                markers = TranscriptMarkers(
                    wordCount = 5,
                    absolutistCount = 0,
                    firstPersonCount = 0,
                    insightCount = 0,
                    causationCount = 0,
                    discrepancyCount = 0,
                    temporalLean = null,
                ),
                transcriptHash = "deadbeef",
                analysisVersion = TranscriptContext.ANALYSIS_VERSION - 1,
            ),
        )
        assertTrue(store.hasContext(orphanUuid))

        val outcome = runner.sweep(snapshotProvider = { snapshotOf(1) }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        assertFalse("stale-version orphan must be pruned, not left dangling forever", store.hasContext(orphanUuid))
    }

    @Test
    fun `a current-version context not present in the snapshot is left for the dossier builder to prune`() = runTest {
        val currentOrphanUuid = "orphan-current"
        val outcome0 = runner.sweep(snapshotProvider = { snapshotOf(1, prefix = currentOrphanUuid) }, gate = allowGate)
        assertEquals(ThreadsBackfillOutcome.Completed, outcome0)
        val savedUuid = "$currentOrphanUuid-000"
        assertTrue(store.hasCurrentContext(savedUuid))

        // A later sweep whose snapshot no longer includes this uuid
        // (recording deleted) must not touch it — current-schema orphan
        // pruning is ThreadsDossierBuilder's job, not the backfill's.
        preferences.clearBackfillCompleted()
        runner.sweep(snapshotProvider = { emptyList() }, gate = allowGate)

        assertTrue(store.hasCurrentContext(savedUuid))
    }

    // ---- I1: a later clean batch must not advance the checkpoint past an earlier failure ----

    @Test
    fun `a batch failure does not let a later clean batch advance the checkpoint past it`() = runTest {
        val items = snapshotOf(60)
        val failingUuid = items[10].uuid
        // Occupies the EXACT temp-file path TranscriptContextStore.writeAtomically
        // uses for this one uuid with a directory instead of a file, so
        // opening it as a FileOutputStream throws — a real, item-scoped
        // write failure, not a fake/mock. writeAtomically's own catch block
        // deletes `temp` (an empty dir deletes cleanly) after the first
        // failed attempt, so the SAME uuid succeeds on a later retry with
        // no manual cleanup needed here.
        val blocker = java.io.File(
            ApplicationProvider.getApplicationContext<Application>().filesDir,
            "transcript_contexts/$failingUuid.json.gz.tmp",
        )
        blocker.mkdirs()

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Incomplete, outcome)
        assertFalse("the failed item must not be accounted for", store.hasCurrentContext(failingUuid))
        val checkpointAfterFailure = preferences.backfillCheckpoint().processedCount
        assertTrue(
            "checkpoint must not advance past the batch containing the still-failing item — was $checkpointAfterFailure",
            checkpointAfterFailure <= 10,
        )

        // Retry: the blocker is already gone (writeAtomically's own catch
        // block removed it), so item 10 succeeds this time. The checkpoint
        // must have stayed at/before the failure so this retry actually
        // revisits it, rather than a stale "processed everything" prefix
        // stamping completion without it.
        val retryOutcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, retryOutcome)
        for (item in items) {
            assertTrue("item ${item.uuid} must be accounted for after the retry", store.hasCurrentContext(item.uuid))
        }
    }

    // ---- I3: D2D transfer restores completion without contexts ----

    @Test
    fun `stale completion with an empty store and a non-empty snapshot re-sweeps instead of trusting the flag`() = runTest {
        val items = snapshotOf(2)
        preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, preferences.importGeneration.value)
        // As if this device just received the completion flag via a device
        // transfer whose data-extraction rules exclude transcript_contexts/
        // (data_extraction_rules.xml) — the flag survived, the contexts did not.
        assertEquals(emptyList<String>(), store.allUuids())

        val outcome = runner.sweep(snapshotProvider = { items }, gate = allowGate)

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
        for (item in items) {
            assertTrue(
                "a distrusted completion must actually re-run analysis, not just re-stamp the flag",
                store.hasCurrentContext(item.uuid),
            )
        }
    }

    @Test
    fun `stale completion with a populated store still short-circuits (control)`() = runTest {
        val items = snapshotOf(1)
        runner.sweep(snapshotProvider = { items }, gate = allowGate)
        assertTrue("precondition: the store must be genuinely populated", store.allUuids()!!.isNotEmpty())

        val outcome = runner.sweep(
            snapshotProvider = { items },
            gate = { error("a legitimately populated store must short-circuit — gate must not be consulted") },
        )

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
    }

    @Test
    fun `stale completion short-circuits when the store's read fails, never mass-distrusting on a read error`() = runTest {
        val items = snapshotOf(2)
        preferences.setBackfillCompleted(TranscriptContext.ANALYSIS_VERSION, preferences.importGeneration.value)
        // Force allUuids()'s null "unreadable" signal by occupying the
        // contexts directory's own path with a plain file instead of a
        // directory — File.listFiles() returns null in that shape.
        val contextsPath = java.io.File(
            ApplicationProvider.getApplicationContext<Application>().filesDir,
            "transcript_contexts",
        )
        contextsPath.deleteRecursively()
        contextsPath.createNewFile()
        assertEquals(null, store.allUuids())

        val outcome = runner.sweep(
            snapshotProvider = { items },
            gate = { error("a read-error store signal must short-circuit — gate must not be consulted") },
        )

        assertEquals(ThreadsBackfillOutcome.Completed, outcome)
    }

    @Test
    fun `an empty snapshot is never treated as proof every stale orphan is safe to delete`() = runTest {
        val orphanUuid = "orphan-empty-snapshot"
        store.save(
            TranscriptContext(
                uuid = orphanUuid,
                languageCode = "en",
                wordCount = 5,
                themes = emptyList(),
                markers = TranscriptMarkers(
                    wordCount = 5,
                    absolutistCount = 0,
                    firstPersonCount = 0,
                    insightCount = 0,
                    causationCount = 0,
                    discrepancyCount = 0,
                    temporalLean = null,
                ),
                transcriptHash = "deadbeef",
                analysisVersion = TranscriptContext.ANALYSIS_VERSION - 1,
            ),
        )

        runner.sweep(snapshotProvider = { emptyList() }, gate = allowGate)

        assertTrue(
            "an empty snapshot must not be treated as proof of universal orphanhood (a failed read looks the same)",
            store.hasContext(orphanUuid),
        )
    }
}
