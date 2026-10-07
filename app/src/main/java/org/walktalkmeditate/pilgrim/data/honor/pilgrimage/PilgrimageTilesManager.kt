// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import androidx.annotation.MainThread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.walktalkmeditate.pilgrim.data.honor.WayStore

/**
 * The saved basemap for the one installed pilgrimage, one tile region per
 * stage keyed like the stage's Way (iOS `PilgrimageTilesManager`,
 * `PilgrimageTilesManager.swift@7c200bf`, spec D C2). Every store call goes
 * through [TileRegionLoading]; nothing here touches Mapbox, and building
 * the manager makes no store call.
 *
 * **One thread.** iOS's manager is `@MainActor`; this one is confined to
 * the main thread, which [mainDispatcher] is in production
 * (`Dispatchers.Main.immediate`), since the loader calls `@MainThread`
 * Mapbox APIs. The readers and [cancel] are main-only and synchronous, as
 * iOS's are. [remove], [removeRegions], [reconcile] and [warm] post to the
 * main thread and return without waiting: the package manager calls the
 * first two on IO under its actor, and Mapbox's removal is asynchronous
 * anyway (§C2.12 points 1–2).
 *
 * **Owned work.** A save runs in [scope], app-scoped and a `SupervisorJob`,
 * so leaving the route page never cancels one. Its [Deferred] carries the
 * outcome for a caller that wants it, and cancelling it stops nothing, as
 * cancelling iOS's waiting task never reaches its save; the [phase] carries
 * the outcome for the row.
 * The scope must carry a `CoroutineExceptionHandler`: what escapes the
 * posted work (a loader bug in a hook or the sweep) reaches it rather than
 * crashing the UI process. Nothing in this package logs, so the handler
 * that logs is the scope provider's, as the walk-finalization scope's is.
 *
 * **Readers wait for the store, hooks don't** (owner decision 2): a surface
 * calls [awaitStore] once before its first read; [remove], [removeRegions],
 * [reconcile] and [save] read the cache as iOS's do (D6, matched).
 */
class PilgrimageTilesManager internal constructor(
    private val loader: TileRegionLoading,
    private val calibration: PilgrimageTilesCalibration,
    /** Only [PilgrimageWalkSignals.walkScreenUp] and [PilgrimageWalkSignals.walkActive] are read. */
    private val signals: PilgrimageWalkSignals,
    private val mainDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : PilgrimageTiles {

    sealed interface Status {
        data object None : Status

        data class Partial(val saved: Int, val of: Int) : Status

        data class Saved(val bytes: Long) : Status
    }

    sealed interface Phase {
        data object Idle : Phase

        /** [total] counts the two style packs plus one region per stage. */
        data class Saving(val done: Int, val total: Int) : Phase

        /** Kept until the next save past the one-at-a-time check, or a [cancel]. */
        data class Failed(val error: PilgrimageError) : Phase
    }

    /** What Settings → Data shows: the stages saved, and the bytes of every region with the route's prefix. */
    data class Footprint(val savedStages: Int, val bytes: Long)

    /** What the launch reconcile keeps: the installed route's regions below its stage count. */
    data class InstalledRoute(val routeId: String, val stageCount: Int)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)

    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _regionsChanged = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * For readers that care only about what is on disk: fires on the
     * loader's regions answer, never on the packs' or on a save's progress.
     * No replay, as iOS's `PassthroughSubject`.
     */
    val regionsChanged: SharedFlow<Unit> = _regionsChanged.asSharedFlow()

    override val isSaving: Boolean get() = _phase.value is Phase.Saving

    /** Moved by [cancel] only: a cancelled save's late callbacks and its own resumed loop then do nothing. */
    private var generation = 0

    /** Moved by a save past its door and by [reconcile]: only the newest reconcile sweeps, and never after a save. */
    private var sweepGeneration = 0

    /** The continuation the in-flight load resumes; [cancel] resumes it itself. */
    private var pending: CancellableContinuation<Unit>? = null

    private var inFlight: TileLoadHandle? = null

    /** Held across the door's suspending walk read, so a second tap meanwhile starts no second loop. */
    private var doorClaimed = false

    /** The latch [awaitStore] waits on: null until asked, and again once an answer has failed. */
    private var storeAnswer: CompletableDeferred<TileStoreRead>? = null

    init {
        require(scope.coroutineContext[CoroutineExceptionHandler] != null) {
            "the tiles scope needs an exception handler, or a failed hook crashes the UI process"
        }
        loader.onChange = { change ->
            if (change == TileStoreChange.REGIONS) _regionsChanged.tryEmit(Unit)
        }
    }

    // ---- Readers ----------------------------------------------------------------

    /** The single-stage entry point, for the morning card: complete, and loaded for the stage's current line. */
    @MainThread
    fun isStageSaved(stage: TileStage): Boolean = isSaved(stage, loader.regions().firstOrNull { it.id == stage.id })

    /** From one store read; `Saved` needs both style packs, so every region saved with a pack missing is "n of n" (D4, matched). */
    @MainThread
    fun status(stages: List<TileStage>): Status {
        val byId = regionsById()
        val saved = stages.filter { isSaved(it, byId[it.id]) }
        if (saved.isEmpty()) return Status.None
        val packsPresent = StylePackRequest.entries.all(loader::hasStylePack)
        if (saved.size != stages.size || !packsPresent) return Status.Partial(saved = saved.size, of = stages.size)
        return Status.Saved(bytes = saved.mapNotNull { byId[it.id] }.sumOf { it.completedResourceSize })
    }

    /** From one store read. Stale, partial and retired regions' bytes count, since Delete reaches them. */
    @MainThread
    fun footprint(routeId: String, stages: List<TileStage>): Footprint {
        val byId = regionsById()
        val prefix = PilgrimageTilesCorridor.regionPrefix(routeId)
        return Footprint(
            savedStages = stages.count { isSaved(it, byId[it.id]) },
            bytes = byId.values.filter { it.id.startsWith(prefix) }.sumOf { it.completedResourceSize },
        )
    }

    fun packCount(stages: List<TileStage>): Int = PilgrimageTilesCorridor.packCount(stages)

    /** A DataStore read, so it suspends where iOS's `UserDefaults` read doesn't. */
    suspend fun bytesPerPack(routeId: String): Long =
        calibration.stored(routeId)?.takeIf { it > 0 } ?: SEED_BYTES_PER_PACK

    suspend fun estimateBytes(routeId: String, stages: List<TileStage>): Long =
        packCount(stages).toLong() * bytesPerPack(routeId)

    /**
     * Whether the store has answered since the process started, waiting up
     * to [STORE_WAIT_MILLIS] from the call. False on a failed answer or
     * past the bound: "unknown", never "nothing saved". The first call, or
     * [warm], asks the loader; a read answer holds for the process, and the
     * next call after a failed one asks again (spec D §C2.12 point 7). A
     * request the loader throws on is a failed answer, given at once; what
     * it threw goes to [scope]'s handler.
     */
    suspend fun awaitStore(): Boolean = withContext(mainDispatcher) {
        val answer = requestStoreAnswer()
        withTimeoutOrNull(STORE_WAIT_MILLIS) { answer.await() } == TileStoreRead.READ
    }

    /** For the launch work: asks for the store's first answer without waiting on it. */
    fun warm() {
        post { requestStoreAnswer() }
    }

    private fun requestStoreAnswer(): CompletableDeferred<TileStoreRead> {
        storeAnswer?.let { return it }
        val answer = CompletableDeferred<TileStoreRead>()
        storeAnswer = answer
        fun settle(read: TileStoreRead) {
            // Before the readers resume, so one that asks again at once asks afresh.
            if (read == TileStoreRead.FAILED && storeAnswer === answer) storeAnswer = null
            answer.complete(read)
        }
        try {
            loader.firstAnswer(::settle)
        } catch (failure: Throwable) {
            settle(TileStoreRead.FAILED)
            post { throw failure }
        }
        return answer
    }

    private fun regionsById(): Map<String, TileRegionSummary> = loader.regions().distinctBy { it.id }.associateBy { it.id }

    private fun isSaved(stage: TileStage, region: TileRegionSummary?): Boolean =
        region != null && region.isComplete && region.corridorHash == stage.corridorHash

    // ---- Save -------------------------------------------------------------------

    /**
     * Style packs first, then one region per stage by index, skipping any
     * region that is complete and still matches its stage's corridor
     * (§C2.5, Android's door per §C2.12 point 3). One save at a time: a
     * call while one runs returns normally and does nothing.
     *
     * The deferred completes when the save ends. A refusal or a failed load
     * fails it with a [PilgrimageException]: `WALK_IN_PROGRESS` for a walk
     * at the door or at any step, `INCOMPLETE` for a load that failed or
     * was cancelled or a walk read that threw, `DISK_FULL`, `MAP_TOO_LARGE`.
     * A loader that throws fails it with that `Throwable` as it is, its
     * phase `Failed(INCOMPLETE)`. Every failure but a cancel's also lands in
     * [phase]. Cancelling the deferred only drops the caller's handle: the
     * save runs on, and [cancel] is what stops one. It ends cancelled only
     * when [scope] is.
     *
     * The walk: iOS's clause, the walk screen up, wherever iOS checks (the
     * door and every step, a skipped one too); Android's walk row, a Room
     * read, at the door and before each real load, each read followed by a
     * check that no cancel landed meanwhile (owner decision 4).
     */
    fun save(routeId: String, stages: List<TileStage>): Deferred<Unit> {
        val outcome = CompletableDeferred<Unit>()
        val work = scope.launch(mainDispatcher) {
            outcome.completeWith(runCatching { saveOnMain(routeId, stages) })
        }
        // A scope cancelled before the save starts never runs its body.
        work.invokeOnCompletion { cause -> if (cause != null) outcome.completeExceptionally(cause) }
        return outcome
    }

    private suspend fun saveOnMain(routeId: String, stages: List<TileStage>) {
        if (_phase.value is Phase.Saving || doorClaimed) return
        if (signals.walkScreenUp()) refuseForAWalk()
        doorClaimed = true
        val myGeneration = generation
        val walking = try {
            readWalkActive()
        } catch (failure: PilgrimageException) {
            if (generation == myGeneration) _phase.value = Phase.Failed(failure.error)
            throw failure
        } finally {
            doorClaimed = false
        }
        if (generation != myGeneration) throw PilgrimageException(PilgrimageError.INCOMPLETE)
        if (walking) refuseForAWalk()
        // A save past its door outranks a launch sweep still waiting on the
        // store; one refused at the door writes nothing, so the sweep stays.
        sweepGeneration += 1
        val total = StylePackRequest.entries.size + stages.size
        _phase.value = Phase.Saving(done = 0, total = total)
        var done = 0
        try {
            for (pack in StylePackRequest.entries) {
                if (signals.walkScreenUp()) throw PilgrimageException(PilgrimageError.WALK_IN_PROGRESS)
                if (!loader.hasStylePack(pack)) {
                    guardTheLoad(myGeneration)
                    awaitLoad(myGeneration) { onResult -> loader.loadStylePack(pack, onResult) }
                }
                // A cancel can land between a load's completion and this
                // hop; without this check the loop would start the next load
                // under a stale generation, whose completion would never
                // resume the save.
                if (generation != myGeneration) throw PilgrimageException(PilgrimageError.INCOMPLETE)
                done += 1
                _phase.value = Phase.Saving(done = done, total = total)
            }
            // One snapshot for the whole loop: every region the loop loads is
            // one this snapshot said was missing.
            val byId = regionsById()
            for (stage in stages.sortedBy { it.index }) {
                if (signals.walkScreenUp()) throw PilgrimageException(PilgrimageError.WALK_IN_PROGRESS)
                if (!isSaved(stage, byId[stage.id])) {
                    guardTheLoad(myGeneration)
                    val request = TileRegionRequest(
                        id = stage.id,
                        rings = stage.rings,
                        corridorHash = stage.corridorHash,
                        acceptExpired = true,
                    )
                    awaitLoad(myGeneration) { onResult -> loader.loadRegion(request, progress = { _, _ -> }, onResult) }
                }
                if (generation != myGeneration) throw PilgrimageException(PilgrimageError.INCOMPLETE)
                done += 1
                _phase.value = Phase.Saving(done = done, total = total)
            }
            calibrate(routeId, stages)
            _phase.value = Phase.Idle
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // A cancel already put the phase back, and a save started since
            // may own `inFlight`. A genuine failure, an Error from a native
            // included, shows until the next save or cancel clears it.
            if (generation == myGeneration) {
                inFlight?.cancel()
                inFlight = null
                _phase.value = Phase.Failed((failure as? PilgrimageException)?.error ?: PilgrimageError.INCOMPLETE)
            }
            throw failure
        }
    }

    private fun refuseForAWalk(): Nothing {
        _phase.value = Phase.Failed(PilgrimageError.WALK_IN_PROGRESS)
        throw PilgrimageException(PilgrimageError.WALK_IN_PROGRESS)
    }

    private suspend fun guardTheLoad(myGeneration: Int) {
        val walking = readWalkActive()
        if (generation != myGeneration) throw PilgrimageException(PilgrimageError.INCOMPLETE)
        if (walking) throw PilgrimageException(PilgrimageError.WALK_IN_PROGRESS)
    }

    /** iOS's walk closure can't fail; a Room read can, and the phase must carry every failure. */
    private suspend fun readWalkActive(): Boolean = try {
        signals.walkActive()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (readFailure: Throwable) {
        throw PilgrimageException(PilgrimageError.INCOMPLETE)
    }

    /**
     * One load, resumed once: by its completion while its generation is
     * current, or by [cancel]. `pending === cont` also turns away a
     * callback delivered twice.
     */
    private suspend fun awaitLoad(myGeneration: Int, start: ((TileLoadResult<*>) -> Unit) -> TileLoadHandle) {
        suspendCancellableCoroutine { cont ->
            pending = cont
            val handle = try {
                start onResult@{ result ->
                    if (generation != myGeneration || pending !== cont) return@onResult
                    pending = null
                    inFlight = null
                    when (result) {
                        is TileLoadResult.Success -> cont.resume(Unit)
                        is TileLoadResult.Failure -> cont.resumeWithException(PilgrimageException(mapped(result.error)))
                    }
                }
            } catch (failure: Throwable) {
                if (pending === cont) pending = null
                throw failure
            }
            inFlight = handle
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    /**
     * After a whole loop: the stages' regions' bytes over the route's pack
     * count replace the seed, by integer division, written only when both
     * are at least 1. A quotient of 0 is written, and reads as the seed.
     */
    @MainThread
    internal fun calibrate(routeId: String, stages: List<TileStage>) {
        val byId = regionsById()
        val bytes = stages.mapNotNull { byId[it.id] }.sumOf { it.completedResourceSize }
        val packs = packCount(stages)
        if (bytes < 1 || packs < 1) return
        calibration.store(routeId, bytes / packs)
    }

    /** Regions already complete stay. Clears a failed phase too, and never touches a pending launch sweep. */
    @MainThread
    fun cancel() {
        generation += 1
        inFlight?.cancel()
        inFlight = null
        pending?.let { cont ->
            pending = null
            cont.resumeWithException(PilgrimageException(PilgrimageError.INCOMPLETE))
        }
        _phase.value = Phase.Idle
    }

    // ---- Lifecycle --------------------------------------------------------------

    /** Cancels any save, then every region with the route's prefix goes. The style packs are shared and stay (D5, matched). */
    override fun remove(routeId: String) {
        post {
            cancel()
            val prefix = PilgrimageTilesCorridor.regionPrefix(routeId)
            for (region in loader.regions()) {
                if (region.id.startsWith(prefix)) loader.removeRegion(region.id)
            }
        }
    }

    /** Update's retired indices, the range the package manager retires. Nothing is downloaded on the walker's behalf. */
    override fun removeRegions(routeId: String, atOrAbove: Int) {
        post {
            val prefix = PilgrimageTilesCorridor.regionPrefix(routeId)
            for (region in loader.regions()) {
                val index = PilgrimageTilesCorridor.stageIndex(region.id, prefix) ?: continue
                if (index >= atOrAbove.toLong()) loader.removeRegion(region.id)
            }
        }
    }

    /**
     * Once per launch: everything the installed route doesn't account for
     * goes, on the store's answer to this reconcile's own read, unless a
     * later reconcile or a save past its door came first. With nothing
     * installed, every saved map goes (D3, matched).
     */
    fun reconcile(installed: InstalledRoute?) {
        post {
            sweepGeneration += 1
            val mySweep = sweepGeneration
            loader.refreshRegions {
                if (sweepGeneration == mySweep) sweep(installed)
            }
        }
    }

    private fun sweep(installed: InstalledRoute?) {
        for (region in loader.regions()) {
            if (!region.id.startsWith(WayStore.STAGE_ID_PREFIX)) continue
            if (installed != null) {
                // Null for another route's region, so one test covers a
                // foreign prefix and an unreadable index.
                val index = PilgrimageTilesCorridor.stageIndex(region.id, PilgrimageTilesCorridor.regionPrefix(installed.routeId))
                if (index != null && index < installed.stageCount.toLong()) continue
            }
            loader.removeRegion(region.id)
        }
    }

    private fun post(block: () -> Unit) {
        scope.launch(mainDispatcher) { block() }
    }

    private fun mapped(error: TileRegionLoadingError): PilgrimageError = when (error) {
        TileRegionLoadingError.DISK_FULL -> PilgrimageError.DISK_FULL
        TileRegionLoadingError.TILE_COUNT_EXCEEDED -> PilgrimageError.MAP_TOO_LARGE
        TileRegionLoadingError.FAILED, TileRegionLoadingError.CANCELLED -> PilgrimageError.INCOMPLETE
    }

    companion object {
        /**
         * One z11 cell of the corridor, both tilesets together, as iOS
         * measured it on 2026-09-15 (3.8 MB on the Francés, 2.7 MB on the
         * Nakahechi): every route's figure until its first save calibrates it.
         */
        const val SEED_BYTES_PER_PACK = 4_000_000L

        /** How long a surface waits for the store's first answer (spec D §C2.12 point 7). */
        const val STORE_WAIT_MILLIS = 5_000L

    }
}
