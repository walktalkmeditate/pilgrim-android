// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

/**
 * iOS `FakeTileRegionLoader` (`UnitTests/Honor/FakeTileRegionLoader.swift@7c200bf`,
 * spec D §C2.8): records every call, completes on demand, fails when told
 * to. Loads queue so a test completes them one at a time and observes the
 * manager between them; a cancelled load stays queued, so its late
 * completion can still be delivered. Driven on the manager's thread, the
 * test's, as production's callbacks are hopped there.
 *
 * Android additions: [failRegions], the partial completion, and the
 * store's [firstAnswer], which the first [releaseRegions] or [failRegions]
 * gives.
 */
internal class FakeTileRegionLoader : TileRegionLoading {

    class Handle : TileLoadHandle {
        var isCancelled = false
            private set

        override fun cancel() {
            isCancelled = true
        }
    }

    class PendingRegion(
        val request: TileRegionRequest,
        val progress: (Long, Long) -> Unit,
        val completion: (TileLoadResult<TileRegionSummary>) -> Unit,
        val handle: Handle,
    )

    class PendingPack(
        val pack: StylePackRequest,
        val completion: (TileLoadResult<Unit>) -> Unit,
        val handle: Handle,
    )

    /**
     * Fired wherever the real loader fires it, labelled as it labels it
     * (regions on a stored region and a removal, packs on a stored pack),
     * so a test can't prove the opposite of what production does.
     */
    override var onChange: ((TileStoreChange) -> Unit)? = null

    var stylePacks: Set<StylePackRequest> = emptySet()

    private val stored = HashMap<String, TileRegionSummary>()

    var regionsReadCount = 0
        private set

    val regionRequests = mutableListOf<TileRegionRequest>()

    private val _packRequests = mutableListOf<StylePackRequest>()
    val packRequests: List<StylePackRequest> get() = _packRequests

    private val _removedIds = mutableListOf<String>()
    val removedIds: List<String> get() = _removedIds

    private val _pendingRegions = ArrayDeque<PendingRegion>()
    val pendingRegions: List<PendingRegion> get() = _pendingRegions

    private val _pendingPacks = ArrayDeque<PendingPack>()
    val pendingPacks: List<PendingPack> get() = _pendingPacks

    /**
     * Whether there is a load for a test to complete. A test that drives the
     * fake before the save has reached its next load parks the save on a
     * continuation nobody will resume.
     */
    val hasPendingWork: Boolean get() = _pendingRegions.isNotEmpty() || _pendingPacks.isNotEmpty()

    /** When set, [completeNextRegion] fails with it once instead of storing. */
    var nextRegionFailure: TileRegionLoadingError? = null

    /** Resource count a completed region reports; tests that care set it. */
    var requiredResourcesPerRegion = 10L
    var bytesPerRegion = 100_000L

    var firstAnswerRequests = 0
        private set

    private var answered: TileStoreRead? = null
    private val firstAnswerWaiters = mutableListOf<(TileStoreRead) -> Unit>()
    private val pendingRegionsCompletions = mutableListOf<() -> Unit>()

    override fun hasStylePack(pack: StylePackRequest): Boolean = pack in stylePacks

    override fun loadStylePack(pack: StylePackRequest, completion: (TileLoadResult<Unit>) -> Unit): TileLoadHandle {
        _packRequests += pack
        val handle = Handle()
        _pendingPacks.addLast(PendingPack(pack, completion, handle))
        return handle
    }

    override fun loadRegion(
        request: TileRegionRequest,
        progress: (completed: Long, required: Long) -> Unit,
        completion: (TileLoadResult<TileRegionSummary>) -> Unit,
    ): TileLoadHandle {
        regionRequests += request
        val handle = Handle()
        _pendingRegions.addLast(PendingRegion(request, progress, completion, handle))
        return handle
    }

    override fun regions(): List<TileRegionSummary> {
        regionsReadCount += 1
        return stored.values.toList()
    }

    /**
     * Never answered inline: production always answers asynchronously, and
     * a fake that answered on the spot would let a test prove a launch
     * sweep ran before the store had spoken. [releaseRegions] is the store
     * speaking.
     */
    override fun refreshRegions(completion: () -> Unit) {
        regionsReadCount += 1
        pendingRegionsCompletions += completion
    }

    override fun removeRegion(id: String) {
        _removedIds += id
        stored.remove(id)
        onChange?.invoke(TileStoreChange.REGIONS)
    }

    override fun firstAnswer(completion: (TileStoreRead) -> Unit) {
        firstAnswerRequests += 1
        val known = answered
        if (known != null) completion(known) else firstAnswerWaiters += completion
    }

    // ---- Driving the fake ---------------------------------------------------------

    /**
     * Driving a completion with nothing pending means the test is a step
     * ahead of the manager; failing here beats leaving the manager on a
     * continuation nobody will resume.
     */
    fun completeNextPack() {
        val pending = _pendingPacks.removeFirstOrNull()
            ?: throw AssertionError("completeNextPack called with nothing pending")
        stylePacks = stylePacks + pending.pack
        onChange?.invoke(TileStoreChange.PACKS)
        pending.completion(TileLoadResult.Success(Unit))
    }

    /**
     * Stores the next region complete and signals before it completes, as
     * the real loader writes its cache first. [partial] succeeds with a
     * resource short, as Mapbox 11.23.1 can (spec D flow item 7).
     */
    fun completeNextRegion(partial: Boolean = false) {
        val pending = _pendingRegions.removeFirstOrNull()
            ?: throw AssertionError("completeNextRegion called with nothing pending")
        nextRegionFailure?.let { failure ->
            nextRegionFailure = null
            pending.completion(TileLoadResult.Failure(failure))
            return
        }
        val summary = TileRegionSummary(
            id = pending.request.id,
            completedResourceCount = if (partial) requiredResourcesPerRegion - 1 else requiredResourcesPerRegion,
            requiredResourceCount = requiredResourcesPerRegion,
            completedResourceSize = bytesPerRegion,
            metadata = mapOf(TileRegionSummary.CORRIDOR_HASH_KEY to pending.request.corridorHash),
        )
        stored[summary.id] = summary
        onChange?.invoke(TileStoreChange.REGIONS)
        pending.completion(TileLoadResult.Success(summary))
    }

    /**
     * The store answering: every refresh waiting on it runs once, after the
     * cache. The real loader is silent when the answer matches its cache,
     * and an empty store answering an empty cache is the launch case; a
     * fake that signalled there would let a test pass on a signal
     * production never sends. The first answer, if it was still to come,
     * reads.
     */
    fun releaseRegions() {
        drainRefreshes()
        answerFirst(TileStoreRead.READ)
        if (stored.isNotEmpty()) onChange?.invoke(TileStoreChange.REGIONS)
    }

    /** A failed regions read: the waiting refreshes are answered, the cache stays, nothing signals. */
    fun failRegions() {
        drainRefreshes()
        answerFirst(TileStoreRead.FAILED)
    }

    /** The style-pack answer on its own: on a phone with saved maps, the signal that arrives first. */
    fun firePacksChange() {
        onChange?.invoke(TileStoreChange.PACKS)
    }

    /** Seeds a region as though a previous save stored it, without a signal. */
    fun seed(id: String, corridorHash: String, complete: Boolean = true, bytes: Long = 100_000L) {
        stored[id] = TileRegionSummary(
            id = id,
            completedResourceCount = if (complete) 10L else 4L,
            requiredResourceCount = 10L,
            completedResourceSize = bytes,
            metadata = mapOf(TileRegionSummary.CORRIDOR_HASH_KEY to corridorHash),
        )
    }

    fun seedStylePacks() {
        stylePacks = StylePackRequest.entries.toSet()
    }

    private fun drainRefreshes() {
        val waiting = pendingRegionsCompletions.toList()
        pendingRegionsCompletions.clear()
        waiting.forEach { it() }
    }

    private fun answerFirst(read: TileStoreRead) {
        if (answered != null) return
        answered = read
        val waiting = firstAnswerWaiters.toList()
        firstAnswerWaiters.clear()
        waiting.forEach { it(read) }
    }
}
