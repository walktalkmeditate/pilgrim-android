// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

/**
 * What [MapboxTileRegionLoader] knows of the tile store, with no Mapbox type
 * in it (iOS's loader state, `MapboxTileRegionLoader.swift:48-66,157-256@7c200bf`,
 * spec D C3 §6–§7): the regions and complete style packs as last read, the
 * refresh generation that keeps an older read from overwriting a newer
 * write, the callers waiting for the next regions answer, and the store's
 * first answer. The loader hands it what the SDK answered; [startRead] is
 * how it asks the SDK for a read, under the token the answers bring back.
 *
 * Main-thread only, as the loader is. The signals follow C3 §6.7's table,
 * and every callback it makes (a signal, a load's completion, a waiter)
 * comes after the bookkeeping it reports: on `Dispatchers.Main.immediate` a
 * completion resumes the tiles manager's save inline, and the save reads
 * back what was just written.
 */
internal class TileStoreCache(private val startRead: (token: Int) -> Unit) {

    /** Set by the tiles manager's constructor, on whatever thread builds it. */
    @Volatile var onChange: ((TileStoreChange) -> Unit)? = null

    private var cached: List<TileRegionSummary> = emptyList()

    private var cachedPacks: Set<StylePackRequest> = emptySet()

    /**
     * [regions] reads on every call, so several reads can be in flight and
     * answer in any order. Only an answer carrying the current generation
     * writes: an older snapshot landing last would put back regions a
     * removal took out, or bytes a save has overtaken, which calibration
     * divides by. A load's success and a removal move it too.
     */
    private var refreshGeneration = 0

    /** The newest read's token, so a stale answer can tell a newer read in flight from none. */
    private var newestRead = 0

    private var pendingRegionsCompletions = mutableListOf<() -> Unit>()

    /** Null while an attempt at the first answer is in progress: from the first read, and again from a call after [TileStoreRead.FAILED]. */
    private var firstAnswer: TileStoreRead? = null

    /** The attempt's first current regions answer: true when it read, false when it failed. */
    private var regionsRead: Boolean? = null

    private var packsRead: Boolean? = null

    private val firstAnswerWaiters = mutableListOf<(TileStoreRead) -> Unit>()

    /** The cache as it is now; the read this starts lands later. */
    fun regions(): List<TileRegionSummary> {
        refresh()
        return cached.toList()
    }

    /** Complete packs as last read, and any a load has saved since. Starts no read. */
    fun hasStylePack(pack: StylePackRequest): Boolean = pack in cachedPacks

    /** [completion] runs on the next current regions answer, success or failure, after the cache is written. */
    fun refreshRegions(completion: () -> Unit) {
        pendingRegionsCompletions += completion
        refresh()
    }

    /**
     * The store's first answer since the process started. [TileStoreRead.READ]
     * is settled for the process and answered at once; a call after a
     * [TileStoreRead.FAILED] starts a fresh attempt with a new read rather
     * than replaying the failure.
     */
    fun firstAnswer(completion: (TileStoreRead) -> Unit) {
        when (firstAnswer) {
            TileStoreRead.READ -> completion(TileStoreRead.READ)
            TileStoreRead.FAILED -> {
                firstAnswer = null
                regionsRead = null
                packsRead = null
                firstAnswerWaiters += completion
                refresh()
            }
            null -> firstAnswerWaiters += completion
        }
    }

    /** One store read, the regions and the packs together, under a new generation. */
    fun refresh() {
        refreshGeneration += 1
        newestRead = refreshGeneration
        startRead(refreshGeneration)
    }

    /**
     * A region load's success, partial ones included (11.23.1 succeeds with
     * resources missing; matched as shipped). A read already in flight is
     * older than this write, and its snapshot would drop the region again.
     */
    fun regionLoaded(summary: TileRegionSummary, completion: (TileLoadResult<TileRegionSummary>) -> Unit) {
        refreshGeneration += 1
        // Sorted as an answer is, or the next answer would read as a change and signal twice for one save.
        cached = (cached.filterNot { it.id == summary.id } + summary).sortedBy { it.id }
        onChange?.invoke(TileStoreChange.REGIONS)
        completion(TileLoadResult.Success(summary))
    }

    /** A pack load's success marks the pack present without the completeness rule a read applies (C3-D4, matched). */
    fun packLoaded(pack: StylePackRequest, completion: (TileLoadResult<Unit>) -> Unit) {
        cachedPacks = cachedPacks + pack
        onChange?.invoke(TileStoreChange.PACKS)
        completion(TileLoadResult.Success(Unit))
    }

    /** Signals even for an id the cache never held. A read in flight is older than this removal and would put it back. */
    fun regionRemoved(id: String) {
        refreshGeneration += 1
        cached = cached.filterNot { it.id == id }
        onChange?.invoke(TileStoreChange.REGIONS)
    }

    /**
     * The store's regions under [token], null when the read failed. A
     * current answer drains every waiter, after the write; it signals only
     * when the settled projection moved, so a save's progress never does,
     * and an empty store answering an empty cache is heard through the
     * waiters alone. A stale one changes nothing and drains nothing.
     */
    fun regionsAnswered(token: Int, regions: List<TileRegionSummary>?) {
        if (token != refreshGeneration) return readAgainForTheFirstAnswer(token)
        val waiting = pendingRegionsCompletions
        pendingRegionsCompletions = mutableListOf()
        if (regionsRead == null) regionsRead = regions != null
        if (regions != null) {
            val sorted = regions.sortedBy { it.id }
            val settledChanged = MapboxTileRegionLoader.settled(cached) != MapboxTileRegionLoader.settled(sorted)
            cached = sorted
            if (settledChanged) onChange?.invoke(TileStoreChange.REGIONS)
        }
        // After the write, never before: a waiter reads the regions and must see the answer it waited for.
        waiting.forEach { it() }
        answerFirstOnceBothRead()
    }

    /** The complete packs under [token], null when the read failed. Only a current answer that differs writes and signals. */
    fun packsAnswered(token: Int, packs: Set<StylePackRequest>?) {
        if (token != refreshGeneration) return readAgainForTheFirstAnswer(token)
        if (packsRead == null) packsRead = packs != null
        if (packs != null && packs != cachedPacks) {
            cachedPacks = packs
            onChange?.invoke(TileStoreChange.PACKS)
        }
        answerFirstOnceBothRead()
    }

    /**
     * A stale answer while the first is still to come leaves the cache
     * holding only what the event that overtook it wrote, with nothing in
     * flight to correct it, so the store is read again (C3 §7). Not when a
     * newer read is in flight: that one will answer. Once the first answer
     * is known, a stale one is dropped, as iOS drops it (C3-D2, matched).
     */
    private fun readAgainForTheFirstAnswer(token: Int) {
        if (firstAnswer == null && token == newestRead) refresh()
    }

    private fun answerFirstOnceBothRead() {
        if (firstAnswer != null) return
        val regions = regionsRead ?: return
        val packs = packsRead ?: return
        val read = if (regions && packs) TileStoreRead.READ else TileStoreRead.FAILED
        firstAnswer = read
        val waiting = firstAnswerWaiters.toList()
        firstAnswerWaiters.clear()
        waiting.forEach { it(read) }
    }
}
