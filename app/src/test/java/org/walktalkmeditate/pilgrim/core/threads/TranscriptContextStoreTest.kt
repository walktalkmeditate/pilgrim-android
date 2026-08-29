// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranscriptContextStoreTest {

    private lateinit var context: Application
    private lateinit var store: TranscriptContextStore
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "transcript_contexts").deleteRecursively()
        store = TranscriptContextStore(context, json)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "transcript_contexts").deleteRecursively()
    }

    private fun fixture(uuid: String, hash: String = "hash-$uuid", version: Int = TranscriptContext.ANALYSIS_VERSION) =
        TranscriptContext(
            uuid = uuid,
            languageCode = "en",
            wordCount = 30,
            themes = emptyList(),
            markers = TranscriptMarkers(
                wordCount = 30,
                absolutistCount = 0,
                firstPersonCount = 0,
                insightCount = 0,
                causationCount = 0,
                discrepancyCount = 0,
                temporalLean = TemporalLean.PRESENT,
            ),
            transcriptHash = hash,
            analysisVersion = version,
        )

    // ---- round trip ----

    @Test
    fun `save then read round-trips all fields`() = runTest {
        val ctx = fixture("u1").copy(
            themes = listOf(Theme("river", "river", 2, 0.1, listOf(LemmaMention("river", "river", 0, 5)))),
        )

        assertTrue(store.save(ctx))
        val loaded = store.read("u1", "hash-u1")

        assertEquals(ctx, loaded)
    }

    @Test
    fun `readRaw returns the stored context without hash or version gating`() = runTest {
        store.save(fixture("u1", hash = "original-hash"))

        val loaded = store.readRaw("u1")

        assertNotNull(loaded)
        assertEquals("original-hash", loaded!!.transcriptHash)
    }

    @Test
    fun `readRaw returns null for a uuid never saved`() = runTest {
        assertNull(store.readRaw("never-saved"))
    }

    // ---- hash / version mismatch (AE2) ----

    @Test
    fun `read returns null on hash mismatch`() = runTest {
        store.save(fixture("u1", hash = "correct-hash"))

        assertNull(store.read("u1", "wrong-hash"))
    }

    @Test
    fun `read returns null on version mismatch even with matching hash`() = runTest {
        store.save(fixture("u1", hash = "h", version = TranscriptContext.ANALYSIS_VERSION + 1))

        assertNull(store.read("u1", "h"))
    }

    @Test
    fun `read succeeds when both hash and version match`() = runTest {
        store.save(fixture("u1", hash = "h"))

        assertNotNull(store.read("u1", "h"))
    }

    @Test
    fun `hasContext is true regardless of version, hasCurrentContext is not`() = runTest {
        store.save(fixture("u1", version = TranscriptContext.ANALYSIS_VERSION + 1))

        assertTrue(store.hasContext("u1"))
        assertFalse(store.hasCurrentContext("u1"))
    }

    @Test
    fun `hasCurrentContext is true for a current-version file`() = runTest {
        store.save(fixture("u1"))

        assertTrue(store.hasCurrentContext("u1"))
    }

    // ---- ANALYSIS_VERSION pin (2026-08-28 iOS stoplist fold-in) ----

    @Test
    fun `ANALYSIS_VERSION is 2 so version-1 files read as stale and re-arm the backfill`() = runTest {
        // The mechanism tests above and in ThreadsBackfillTest use versions
        // RELATIVE to the constant; this pin proves the real bump landed —
        // a v1 file's themes may name filler ('yeah') or the new light
        // nouns ('time', 'person', 'app'), so it must re-analyze under
        // the tightened stoplists.
        assertEquals(2, TranscriptContext.ANALYSIS_VERSION)

        store.save(fixture("v1-era", version = 1))

        assertFalse(store.hasCurrentContext("v1-era"))
        assertNull(store.read("v1-era", "hash-v1-era"))
    }

    /**
     * A pre-fold-in file as it was ACTUALLY written: the key absent
     * entirely, because the property defaulted to the then-current
     * version and `encodeDefaults = false` dropped it. Hand-written
     * rather than round-tripped — round-tripping a `copy(analysisVersion
     * = 1)` produces a file that carries the key, which is precisely the
     * shape this test must not stand in for.
     */
    private fun plantKeylessFile(uuid: String, hash: String = "hash-$uuid") {
        val dir = File(context.filesDir, "transcript_contexts").apply { mkdirs() }
        val body = """
            {"uuid":"$uuid","languageCode":"en","wordCount":30,"themes":[],
            "markers":{"wordCount":30,"absolutistCount":0,"firstPersonCount":0,
            "insightCount":0,"causationCount":0,"discrepancyCount":0,
            "temporalLean":"PRESENT"},"transcriptHash":"$hash"}
        """.trimIndent().replace("\n", "")
        GZIPOutputStream(File(dir, "$uuid.json.gz").outputStream()).use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `a file written without an analysisVersion key reads as stale, never as current`() = runTest {
        plantKeylessFile("keyless")

        assertNull("a keyless file must not satisfy a current-version read", store.read("keyless", "hash-keyless"))
        assertFalse("a keyless file must not read as current", store.hasCurrentContext("keyless"))
        assertEquals(emptyList<String>(), store.loadAll().map { it.uuid })
        // The sweep still has to see it, or the stale file is never cleaned up.
        assertEquals(listOf("keyless"), store.loadAllIncludingStaleVersions().map { it.uuid })
    }

    @Test
    fun `a saved file carries the analysisVersion key on disk, not only in memory`() = runTest {
        store.save(fixture("written"))

        val body = GZIPInputStream(File(context.filesDir, "transcript_contexts/written.json.gz").inputStream())
            .use { it.bufferedReader(Charsets.UTF_8).readText() }

        assertTrue(
            "the version must reach disk or every future bump is inert: $body",
            body.contains("\"analysisVersion\":${TranscriptContext.ANALYSIS_VERSION}"),
        )
    }

    // ---- unreadable-dir signal must not read as empty ----

    @Test
    fun `allUuids is empty list before anything is ever saved`() = runTest {
        assertEquals(emptyList<String>(), store.allUuids())
    }

    @Test
    fun `allUuids lists every saved uuid`() = runTest {
        store.save(fixture("u1"))
        store.save(fixture("u2"))

        assertEquals(setOf("u1", "u2"), store.allUuids()?.toSet())
    }

    @Test
    fun `allUuids returns null when the directory cannot be listed, distinct from empty`() = runTest {
        // Occupy the store's directory path with a plain FILE instead of a
        // directory — File#listFiles() returns null in exactly this case,
        // simulating a real unreadable-dir failure without needing actual
        // filesystem permission tricks.
        val dirPath = File(context.filesDir, "transcript_contexts")
        dirPath.deleteRecursively()
        dirPath.parentFile?.mkdirs()
        dirPath.writeText("not a directory")

        val result = store.allUuids()

        assertNull("an unreadable dir must signal null, never an empty list", result)
    }

    // ---- three removal primitives, three semantics (BEH-20) ----

    @Test
    fun `delete tombstones and removes the file`() = runTest {
        store.save(fixture("u1"))

        store.delete("u1")

        assertFalse(store.hasContext("u1"))
        // A save attempted after delete must be blocked by the tombstone.
        assertTrue("tombstone-blocked save still reports true", store.save(fixture("u1")))
        assertFalse("the tombstone must block the resurrection", store.hasContext("u1"))
    }

    @Test
    fun `insertTombstones blocks future saves but does not remove an existing file`() = runTest {
        store.save(fixture("u1"))

        store.insertTombstones(listOf("u1"))

        assertTrue("insertTombstones must not delete the file itself", store.hasContext("u1"))
        assertTrue(store.save(fixture("u1", hash = "new-hash")))
        assertEquals(
            "the tombstone blocks the new save, so the OLD file must remain unchanged",
            "hash-u1",
            store.readRaw("u1")!!.transcriptHash,
        )
    }

    @Test
    fun `removeContext removes the file without tombstoning — a later save succeeds`() = runTest {
        store.save(fixture("u1", hash = "old-hash"))

        store.removeContext("u1")
        assertFalse(store.hasContext("u1"))

        assertTrue(store.save(fixture("u1", hash = "new-hash")))
        assertEquals("new-hash", store.readRaw("u1")!!.transcriptHash)
    }

    @Test
    fun `clearTombstones lifts a tombstone so a later save lands`() = runTest {
        store.insertTombstones(listOf("u1"))
        assertTrue("tombstoned save reports true but must not write", store.save(fixture("u1")))
        assertFalse(store.hasContext("u1"))

        store.clearTombstones(listOf("u1"))

        assertTrue(store.save(fixture("u1")))
        assertTrue(store.hasContext("u1"))
    }

    // ---- orphaned temp-file hygiene (interrupted writeAtomically) ----

    private fun plantTemp(uuid: String): File {
        val dir = File(context.filesDir, "transcript_contexts")
        dir.mkdirs()
        return File(dir, "$uuid.json.gz.tmp").also { it.writeText("interrupted write") }
    }

    @Test
    fun `delete removes an orphaned temp alongside the context file`() = runTest {
        store.save(fixture("u1"))
        val temp = plantTemp("u1")

        store.delete("u1")

        assertFalse(store.hasContext("u1"))
        assertFalse("delete must sweep the uuid's orphaned temp too", temp.exists())
    }

    @Test
    fun `removeContext removes an orphaned temp alongside the context file`() = runTest {
        store.save(fixture("u1"))
        val temp = plantTemp("u1")

        store.removeContext("u1")

        assertFalse(store.hasContext("u1"))
        assertFalse("removeContext must sweep the uuid's orphaned temp too", temp.exists())
    }

    @Test
    fun `a fresh store instance sweeps leftover temp files on first directory access`() = runTest {
        store.save(fixture("u1"))
        val temp = plantTemp("orphaned-by-process-kill")

        // A brand-new instance (fresh lazy) — a plain read triggers the
        // directory initializer and with it the sweep.
        val revived = TranscriptContextStore(context, json)
        assertNotNull(revived.readRaw("u1"))

        assertFalse("first directory access must sweep orphaned temps", temp.exists())
        assertTrue("the real context file must survive the sweep", store.hasContext("u1"))
    }

    // ---- tombstone race: analyzer write racing a delete must not resurrect ----

    @Test
    fun `a save queued after a delete's tombstone does not resurrect the file`() = runTest {
        store.save(fixture("u1"))
        store.delete("u1")

        // Simulates an analysis that was already in flight when the
        // delete landed — its save() call happens strictly AFTER the
        // tombstone is in place.
        val saveResult = store.save(fixture("u1", hash = "late-analysis-hash"))

        assertTrue("save-true-when-tombstoned: accounted for, not a failure", saveResult)
        assertFalse("the file must stay gone — no resurrection", store.hasContext("u1"))
    }

    @Test
    fun `deleteAll tombstones every uuid recovered from filenames`() = runTest {
        store.save(fixture("u1"))
        store.save(fixture("u2"))

        store.deleteAll()

        assertEquals(emptyList<String>(), store.allUuids())
        // Post-wipe saves for either uuid must be blocked by the
        // filename-derived tombstones deleteAll() inserted.
        assertTrue(store.save(fixture("u1")))
        assertFalse(store.hasContext("u1"))
        assertTrue(store.save(fixture("u2")))
        assertFalse(store.hasContext("u2"))
    }

    // ---- changeCount heartbeat ----

    @Test
    fun `changeCount increments once per save, not on a tombstone-blocked save`() = runTest {
        val before = store.changeCount.value

        store.save(fixture("u1"))
        assertEquals(before + 1, store.changeCount.value)

        store.delete("u1")
        assertEquals(before + 2, store.changeCount.value)

        // Tombstone-blocked: no real state change, no bump.
        store.save(fixture("u1"))
        assertEquals(before + 2, store.changeCount.value)
    }

    @Test
    fun `changeCount increments exactly once per delete call regardless of batch size`() = runTest {
        store.save(fixture("u1"))
        store.save(fixture("u2"))
        val before = store.changeCount.value

        store.delete(listOf("u1", "u2"))

        assertEquals(before + 1, store.changeCount.value)
    }

    // ---- in-memory decoded cache behind loadAll (P2 #8/#14) ----

    private fun deleteOnDiskBehindTheStore(uuid: String) {
        val file = File(context.filesDir, "transcript_contexts/$uuid.json.gz")
        assertTrue("test setup: the on-disk file must exist to be removed out-of-band", file.delete())
    }

    @Test
    fun `a second loadAll is served from memory, not a fresh disk scan`() = runTest {
        store.save(fixture("u1"))
        assertEquals(listOf("u1"), store.loadAll().map { it.uuid })

        // Out-of-band disk change, bypassing the store (impossible in
        // production, where this @Singleton is the directory's sole
        // writer) — a memory-served second call must not observe it.
        deleteOnDiskBehindTheStore("u1")

        assertEquals(listOf("u1"), store.loadAll().map { it.uuid })
    }

    @Test
    fun `a save after the cache is populated is visible without a disk re-scan`() = runTest {
        assertEquals(emptyList<TranscriptContext>(), store.loadAll())

        store.save(fixture("u1"))
        deleteOnDiskBehindTheStore("u1")

        assertEquals(listOf("u1"), store.loadAll().map { it.uuid })
    }

    @Test
    fun `delete and removeContext evict from the cache`() = runTest {
        store.save(fixture("u1"))
        store.save(fixture("u2"))
        assertEquals(setOf("u1", "u2"), store.loadAll().map { it.uuid }.toSet())

        store.delete("u1")
        assertEquals(listOf("u2"), store.loadAll().map { it.uuid })

        store.removeContext("u2")
        assertEquals(emptyList<TranscriptContext>(), store.loadAll())
    }

    @Test
    fun `a tombstone-blocked save does not enter the cache`() = runTest {
        store.delete("u1")
        assertEquals(emptyList<TranscriptContext>(), store.loadAll())

        assertTrue("blocked save still reports accounted-for", store.save(fixture("u1")))

        assertEquals(
            "nothing reached disk, so nothing may enter the cache",
            emptyList<TranscriptContext>(),
            store.loadAll(),
        )
    }

    @Test
    fun `deleteAll empties the cache`() = runTest {
        store.save(fixture("u1"))
        assertEquals(listOf("u1"), store.loadAll().map { it.uuid })

        store.deleteAll()

        assertEquals(emptyList<TranscriptContext>(), store.loadAll())
    }

    // ---- corrupt file on disk ----

    @Test
    fun `a corrupt file on disk decodes as absent rather than throwing`() = runTest {
        val dir = File(context.filesDir, "transcript_contexts").apply { mkdirs() }
        File(dir, "corrupt.json.gz").writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(store.readRaw("corrupt"))
        // loadAll must skip it silently rather than propagating a decode
        // exception up to callers that just want the good contexts.
        assertEquals(emptyList<TranscriptContext>(), store.loadAll())
    }

    // ---- loadAll vs loadAllIncludingStaleVersions ----

    @Test
    fun `loadAll excludes stale-version files that loadAllIncludingStaleVersions still sees`() = runTest {
        store.save(fixture("current"))
        store.save(fixture("stale", version = TranscriptContext.ANALYSIS_VERSION - 1))

        assertEquals(listOf("current"), store.loadAll().map { it.uuid })
        assertEquals(setOf("current", "stale"), store.loadAllIncludingStaleVersions().map { it.uuid }.toSet())
    }
}
