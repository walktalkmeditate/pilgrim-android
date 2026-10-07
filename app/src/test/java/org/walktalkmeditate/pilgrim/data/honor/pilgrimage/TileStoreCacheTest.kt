// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The production loader's state between store reads (spec D C3 §6–§7),
 * driven as the loader drives it once its SDK answers have hopped to the
 * main thread: C3 §6.7's signal table row by row, the store's first answer
 * with its fresh read on a stale answer and its failed packs half, and the
 * rule that a completion runs only after the bookkeeping it reports. iOS
 * can't unit-test this part of its loader ("only a real store can fail
 * here"); the cache has no Mapbox type, so Android can.
 */
class TileStoreCacheTest {

    /** Every read the cache asked the SDK for, by token. */
    private val reads = mutableListOf<Int>()
    private val signals = mutableListOf<TileStoreChange>()
    private val cache = TileStoreCache(startRead = { reads += it })

    @Before
    fun setUp() {
        cache.onChange = { signals += it }
        // The loader's first call opens the store and starts this read.
        cache.refresh()
    }

    private fun latest() = reads.last()

    // ---- Loads and removals (§6.7 rows 1–5) ---------------------------------------------

    @Test
    fun `a region load replaces by id, sorts, and always signals regions`() {
        cache.regionsAnswered(latest(), listOf(region("b"), region("c")))
        signals.clear()

        cache.regionLoaded(region("a")) {}
        cache.regionLoaded(region("a")) {}

        assertEquals(listOf("a", "b", "c"), cache.regions().map { it.id })
        assertEquals(listOf(TileStoreChange.REGIONS, TileStoreChange.REGIONS), signals)
    }

    @Test
    fun `a region load leaves a read already in flight stale`() {
        val before = latest()

        cache.regionLoaded(region("a")) {}
        cache.regionsAnswered(before, emptyList())

        assertEquals(listOf("a"), cache.regions().map { it.id })
    }

    /** On the main dispatcher the completion resumes the save inline, and the save reads back what the load wrote. */
    @Test
    fun `a region load completes only after the write, the bump and the signal`() {
        val before = latest()
        val seen = mutableListOf<String>()
        cache.onChange = { seen += "signal $it" }
        var regionsInCompletion: List<String>? = null

        cache.regionLoaded(region("a")) { result ->
            seen += "completion"
            assertEquals(TileLoadResult.Success(region("a")), result)
            cache.regionsAnswered(before, emptyList())
            regionsInCompletion = cache.regions().map { it.id }
        }

        assertEquals(listOf("signal REGIONS", "completion"), seen)
        assertEquals("written and the older read already stale", listOf("a"), regionsInCompletion)
    }

    @Test
    fun `a pack load marks the pack present without the completeness rule, then completes`() {
        val seen = mutableListOf<String>()
        cache.onChange = { seen += "signal $it" }
        var presentInCompletion = false

        cache.packLoaded(StylePackRequest.LIGHT) {
            seen += "completion"
            presentInCompletion = cache.hasStylePack(StylePackRequest.LIGHT)
        }
        cache.packLoaded(StylePackRequest.LIGHT) {}

        assertTrue(presentInCompletion)
        assertEquals("a pack already present signals again", listOf("signal PACKS", "completion", "signal PACKS"), seen)
        assertFalse(cache.hasStylePack(StylePackRequest.DARK))
    }

    @Test
    fun `a removal drops the id and signals regions, even for an id the cache never held`() {
        cache.regionsAnswered(latest(), listOf(region("a"), region("b")))
        signals.clear()

        cache.regionRemoved("a")
        cache.regionRemoved("never-held")

        assertEquals(listOf("b"), cache.regions().map { it.id })
        assertEquals(listOf(TileStoreChange.REGIONS, TileStoreChange.REGIONS), signals)
    }

    @Test
    fun `a removal leaves a read already in flight stale`() {
        cache.regionsAnswered(latest(), listOf(region("a")))
        cache.refresh()
        val before = latest()

        cache.regionRemoved("a")
        cache.regionsAnswered(before, listOf(region("a")))

        assertTrue("the older snapshot doesn't put it back", cache.regions().isEmpty())
    }

    // ---- Regions answers (§6.7 rows 6–10) -----------------------------------------------

    @Test
    fun `a current answer whose settled projection moved writes, signals, then drains its waiters`() {
        var regionsInWaiter: List<String>? = null
        cache.refreshRegions { regionsInWaiter = cache.regions().map { it.id } }

        cache.regionsAnswered(latest(), listOf(region("b"), region("a")))

        assertEquals("after the write, sorted", listOf("a", "b"), regionsInWaiter)
        assertEquals(listOf(TileStoreChange.REGIONS), signals)
    }

    @Test
    fun `a current answer that moves only counts or bytes writes it silently and drains`() {
        cache.regionsAnswered(latest(), listOf(region("a", completed = 3, bytes = 300)))
        signals.clear()
        var drained = false
        cache.refreshRegions { drained = true }

        cache.regionsAnswered(latest(), listOf(region("a", completed = 7, bytes = 700)))

        assertTrue(drained)
        assertTrue(signals.isEmpty())
        assertEquals(700L, cache.regions().single().completedResourceSize)
    }

    /** The launch case: an empty store answering an empty cache is heard through the waiters alone. */
    @Test
    fun `an identical current answer signals nothing and still drains`() {
        var drained = 0
        cache.refreshRegions { drained += 1 }

        cache.regionsAnswered(latest(), emptyList())

        assertEquals(1, drained)
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a failed current read leaves the cache, signals nothing, and drains`() {
        cache.regionsAnswered(latest(), listOf(region("a")))
        signals.clear()
        var drained = false
        cache.refreshRegions { drained = true }

        cache.regionsAnswered(latest(), null)

        assertTrue(drained)
        assertTrue(signals.isEmpty())
        assertEquals(listOf("a"), cache.regions().map { it.id })
    }

    @Test
    fun `a stale answer or failure changes nothing and leaves its waiters for a later current answer`() {
        var drained = 0
        cache.refreshRegions { drained += 1 }
        val stale = latest()
        cache.refresh()

        cache.regionsAnswered(stale, listOf(region("a")))
        cache.regionsAnswered(stale, null)

        assertEquals(0, drained)
        assertTrue(cache.regions().isEmpty())
        assertTrue(signals.isEmpty())

        cache.regionsAnswered(latest(), emptyList())
        assertEquals(1, drained)
    }

    @Test
    fun `regions answers from the cache as it is now and starts a read on every call`() {
        val readsBefore = reads.size

        assertTrue(cache.regions().isEmpty())
        cache.regions()

        assertEquals(readsBefore + 2, reads.size)
    }

    // ---- Packs answers (§6.7 rows 11–12) ------------------------------------------------

    @Test
    fun `a current packs answer that differs writes and signals packs`() {
        cache.packsAnswered(latest(), setOf(StylePackRequest.DARK))

        assertTrue(cache.hasStylePack(StylePackRequest.DARK))
        assertFalse(cache.hasStylePack(StylePackRequest.LIGHT))
        assertEquals(listOf(TileStoreChange.PACKS), signals)
    }

    @Test
    fun `a packs answer that is stale, unchanged or failed changes nothing`() {
        cache.packsAnswered(latest(), setOf(StylePackRequest.LIGHT))
        signals.clear()
        val stale = latest()
        cache.refresh()

        cache.packsAnswered(stale, StylePackRequest.entries.toSet())
        cache.packsAnswered(latest(), setOf(StylePackRequest.LIGHT))
        cache.packsAnswered(latest(), null)

        assertTrue(signals.isEmpty())
        assertTrue(cache.hasStylePack(StylePackRequest.LIGHT))
        assertFalse(cache.hasStylePack(StylePackRequest.DARK))
    }

    @Test
    fun `a packs answer never drains a regions waiter`() {
        var drained = false
        cache.refreshRegions { drained = true }

        cache.packsAnswered(latest(), StylePackRequest.entries.toSet())

        assertFalse(drained)
    }

    // ---- The first answer (§7) ------------------------------------------------------------

    @Test
    fun `the first answer reads once a current regions answer and a current packs answer have both landed`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }

        cache.regionsAnswered(latest(), emptyList())
        assertTrue("the packs are still to come", answers.isEmpty())
        cache.packsAnswered(latest(), emptySet())

        assertEquals(listOf(TileStoreRead.READ), answers)
    }

    @Test
    fun `a failed regions read makes the first answer failed`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }

        cache.regionsAnswered(latest(), null)
        cache.packsAnswered(latest(), StylePackRequest.entries.toSet())

        assertEquals(listOf(TileStoreRead.FAILED), answers)
    }

    /** iOS drops a failed packs read on its worker thread; Android's is heard, or "n of n saved" would read as known. */
    @Test
    fun `a failed packs read makes the first answer failed`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }

        cache.packsAnswered(latest(), null)
        cache.regionsAnswered(latest(), listOf(region("a")))

        assertEquals(listOf(TileStoreRead.FAILED), answers)
    }

    /** A removal or a load landing before the first answer leaves nothing in flight to correct the cache. */
    @Test
    fun `a stale answer while the first is pending reads the store again, and that read answers`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }
        val first = latest()
        cache.regionRemoved("pilgrimage:camino-frances:0")

        cache.regionsAnswered(first, listOf(region("pilgrimage:camino-frances:0")))

        assertEquals("one more read", 2, reads.size)
        cache.packsAnswered(first, emptySet())
        assertEquals("the same read's packs half starts no third", 2, reads.size)
        cache.regionsAnswered(latest(), emptyList())
        cache.packsAnswered(latest(), emptySet())
        assertEquals(listOf(TileStoreRead.READ), answers)
    }

    /** A load's success moves the generation as a removal does, so the read it overtook answers stale. */
    @Test
    fun `a region load while the first answer is pending reads the store again, and that read answers`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }
        val first = latest()
        cache.regionLoaded(region("a")) {}

        cache.regionsAnswered(first, emptyList())
        cache.packsAnswered(first, emptySet())

        assertEquals("one more read", 2, reads.size)
        assertTrue(answers.isEmpty())
        cache.regionsAnswered(latest(), listOf(region("a")))
        cache.packsAnswered(latest(), emptySet())
        assertEquals(listOf(TileStoreRead.READ), answers)
        assertEquals(listOf("a"), cache.regions().map { it.id })
    }

    /** The reconcile's sweep removes from inside its waiter; a waiter may also read, or wait again. */
    @Test
    fun `a waiter that calls back into the cache mid-drain leaves the other waiters and the first answer whole`() {
        val seen = mutableListOf<String>()
        cache.firstAnswer { seen += "first answer $it" }
        cache.refreshRegions {
            cache.regionRemoved("a")
            cache.refreshRegions { seen += "waiter queued mid-drain" }
            seen += "re-entering waiter"
        }
        cache.refreshRegions { seen += "second waiter: ${cache.regions().map { it.id }}" }
        cache.packsAnswered(latest(), emptySet())

        cache.regionsAnswered(latest(), listOf(region("a"), region("b")))

        assertEquals(listOf("re-entering waiter", "second waiter: [b]", "first answer READ"), seen)
        cache.regionsAnswered(latest(), listOf(region("b")))
        assertEquals("waits for the next current answer", "waiter queued mid-drain", seen.last())
    }

    @Test
    fun `a stale answer with a newer read in flight starts no read of its own`() {
        val first = latest()
        cache.regions()
        val readsBefore = reads.size

        cache.regionsAnswered(first, emptyList())
        cache.packsAnswered(first, emptySet())

        assertEquals(readsBefore, reads.size)
    }

    @Test
    fun `once the first answer is known, a stale answer is dropped as iOS drops it`() {
        cache.regionsAnswered(latest(), emptyList())
        cache.packsAnswered(latest(), emptySet())
        val stale = latest()
        cache.regionRemoved("x")
        val readsBefore = reads.size

        cache.regionsAnswered(stale, listOf(region("x")))

        assertEquals(readsBefore, reads.size)
    }

    @Test
    fun `a read first answer is settled for the process and answered at once`() {
        cache.regionsAnswered(latest(), emptyList())
        cache.packsAnswered(latest(), emptySet())
        val readsBefore = reads.size
        val answers = mutableListOf<TileStoreRead>()

        cache.firstAnswer { answers += it }
        cache.regionsAnswered(latest(), null)
        cache.firstAnswer { answers += it }

        assertEquals(listOf(TileStoreRead.READ, TileStoreRead.READ), answers)
        assertEquals("and starts no read", readsBefore, reads.size)
    }

    @Test
    fun `a call after a failed first answer reads the store afresh rather than replaying the failure`() {
        val answers = mutableListOf<TileStoreRead>()
        cache.firstAnswer { answers += it }
        cache.regionsAnswered(latest(), null)
        cache.packsAnswered(latest(), emptySet())
        val readsBefore = reads.size

        cache.firstAnswer { answers += it }

        assertEquals(listOf(TileStoreRead.FAILED), answers)
        assertEquals(readsBefore + 1, reads.size)
        cache.regionsAnswered(latest(), emptyList())
        cache.packsAnswered(latest(), emptySet())
        assertEquals(listOf(TileStoreRead.FAILED, TileStoreRead.READ), answers)
    }

    /** The first answer comes after the cache it vouches for, as a waiter's does. */
    @Test
    fun `the first answer completes after the waiters and the write`() {
        val seen = mutableListOf<String>()
        cache.packsAnswered(latest(), emptySet())
        cache.refreshRegions { seen += "waiter" }
        cache.firstAnswer { seen += "first answer: ${cache.regions().map { it.id }}" }

        cache.regionsAnswered(latest(), listOf(region("a")))

        assertEquals(listOf("waiter", "first answer: [a]"), seen)
    }

    private companion object {
        fun region(id: String, completed: Long = 10, bytes: Long = 1_000) = TileRegionSummary(
            id = id,
            completedResourceCount = completed,
            requiredResourceCount = 10,
            completedResourceSize = bytes,
            metadata = mapOf(TileRegionSummary.CORRIDOR_HASH_KEY to "h"),
        )
    }
}
