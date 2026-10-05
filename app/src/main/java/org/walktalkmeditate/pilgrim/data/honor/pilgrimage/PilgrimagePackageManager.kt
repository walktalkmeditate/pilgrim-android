// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import androidx.annotation.VisibleForTesting
import android.content.Context
import android.content.res.Resources
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.WayMediaDownloadWorker
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.stayingOnTheHost
import org.walktalkmeditate.pilgrim.di.PilgrimagePackageHttpClient
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayJson
import org.walktalkmeditate.pilgrim.domain.honor.swiftCompareTo

/**
 * Stage 21-3's offline maps as the package manager reaches them (iOS
 * `PilgrimageTilesManager`, `PilgrimagePackageManager.swift:42-45,248,268,283@7c200bf`).
 * Nothing implements it in Stage 21-2, so the manager holds none. Its calls
 * run inside the manager's own steps, so they must not call back into it.
 */
interface PilgrimageTiles {

    /** A route's saved regions, all of them: Remove, and the route a Replace lets go. */
    fun remove(routeId: String)

    /** The regions at stage indices an Update's route no longer has. */
    fun removeRegions(routeId: String, atOrAbove: Int)

    /** A map save in flight, which the route page's busy state counts. */
    val isSaving: Boolean
}

/**
 * One route at a time, all or nothing: iOS `PilgrimagePackageManager`
 * (`PilgrimagePackageManager.swift@7c200bf`, pilgrimage-stage spec P2 §2–§7,
 * P1 §3.4, §11). Download, Replace, Update and Remove, pinned to the
 * release the index named, refused mid-walk.
 *
 * **The main actor.** iOS runs every step of its own on the main actor and
 * leaves it only to fetch a file and to commit, so no two operations
 * interleave but at those awaits. Here [actor] stands in for it: each
 * operation holds it from entry through its last post-commit step, and
 * lets it go only around each file's fetch and the commit ([offActor]).
 * Replace's removal of the route it lets go, and Update's tail sweep,
 * tiles call and ledger reconcile, so run as part of the operation the
 * guard let in, with nothing else between them (P2 C-4); the races iOS
 * ships at its awaits are kept, Replace's busy race included (owner
 * decision 4, pilgrim-ios #119).
 *
 * **Owned work.** Every operation runs in [scope], the manager's own, and
 * hands back a [Deferred] the caller awaits, so the route page's
 * ViewModel going never cancels a download, as iOS's bare `Task {}` isn't
 * (P4 correction 2).
 *
 * **The guard** ([refusesForAWalk]) is checked on entry and again before
 * the commit, as iOS checks it; Android's clauses are wider (P2 A-1).
 *
 * UI process only: `:tracker` never builds it, and walks the per-walk copy
 * of a stage it staged at Start (owner decision 2). The temp set lives in
 * `noBackupFilesDir/pilgrimage-tmp/`, outside the Ways tree, and the launch
 * sweeps whatever a killed download left there (P2 A-6). The client keeps
 * no HTTP cache (P1 A1) and its redirects stay on the CDN (owner decision 6).
 *
 * Nothing is logged: not a URL, not a route, not a decode error, not a
 * refused commit.
 */
@Singleton
class PilgrimagePackageManager internal constructor(
    private val store: WayStore,
    private val ledgers: PilgrimageLedgerStore,
    private val client: OkHttpClient,
    /** The CDN every fetch goes to; a test's server stands in with the same paths. */
    private val cdn: HttpUrl,
    private val signals: PilgrimageWalkSignals,
    resolveTempRoot: () -> File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val tiles: PilgrimageTiles? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        store: WayStore,
        ledgers: PilgrimageLedgerStore,
        @PilgrimagePackageHttpClient client: OkHttpClient,
        signals: UiPilgrimageWalkSignals,
    ) : this(
        store = store,
        ledgers = ledgers,
        client = client,
        cdn = PilgrimageCatalogService.CDN_ORIGIN.toHttpUrl(),
        signals = signals,
        resolveTempRoot = { File(context.noBackupFilesDir, TEMP_DIRECTORY) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ioDispatcher = Dispatchers.IO,
    )

    /** What is on the phone: a route whose `route.json` and `release.txt` both read and pass. */
    data class Installed(val routeId: String, val release: String, val route: PilgrimageRoute)

    sealed interface Phase {
        data object Idle : Phase

        /** [total] counts `route.json` plus every stage file. */
        data class Downloading(val done: Int, val total: Int) : Phase

        /** Kept until the next download sets the phase again. */
        data class Failed(val error: PilgrimageError) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)

    /** iOS's `@Published phase`: `Downloading` from a download's entry through its commit. */
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /**
     * True from a download's entry to its end. A second download, and a
     * Remove, are refused while it is ("the download didn't finish"), and
     * [installed] leaves a Replace's marker to the swap that wrote it.
     */
    @Volatile var isDownloading = false
        private set

    /** The route page's busy state (`PilgrimageRouteView.swift:308-312@7c200bf`): a download, or a map save. */
    val isBusy: Boolean get() = phase.value is Phase.Downloading || tiles?.isSaving == true

    /** The commit's one write to the store, a seam so a test can fail it mid-loop and prove the rollback. */
    @Volatile internal var saveStage: (Way) -> Unit = store::save

    /** The whole package's ceiling on the bytes that land; injectable so a test need not serve 50 MiB. */
    @Volatile internal var maxPackageBytes: Long = PilgrimageCatalogService.MAX_PACKAGE_BYTES.toLong()

    private val tempRoot: File by lazy(resolveTempRoot)

    /** The download in flight's temp set, which the launch sweep spares. */
    @Volatile private var inFlightTemp: File? = null

    private val actor = Mutex()

    /**
     * iOS `download(entry:release:)` (`PilgrimagePackageManager.swift:149-210@7c200bf`, P2 §3).
     * The deferred fails with a [PilgrimageException]: `WALK_IN_PROGRESS`
     * on entry or before the commit, `INCOMPLETE` for a download already
     * in flight, a fetch, a cap or the 50 MiB total, `NOT_WALKABLE` for a
     * file the importer or the identity checks refuse, `DISK_FULL` for a
     * write the disk refused.
     */
    fun download(entry: PilgrimageCatalogEntry, release: String): Deferred<Unit> = owned { downloadOnActor(entry, release) }

    /**
     * iOS `replace(with:release:)` (`PilgrimagePackageManager.swift:226-251@7c200bf`, P2 §4):
     * the new route downloaded in full before the old one is touched. A
     * Replace of the route already installed is an Update.
     */
    fun replace(entry: PilgrimageCatalogEntry, release: String): Deferred<Unit> = owned { replaceOnActor(entry, release) }

    /** iOS `update(entry:release:)` (`PilgrimagePackageManager.swift:255-272@7c200bf`, P2 §5). */
    fun update(entry: PilgrimageCatalogEntry, release: String): Deferred<Unit> = owned { updateOnActor(entry, release) }

    /** iOS `remove(routeId:)` (`PilgrimagePackageManager.swift:274-284@7c200bf`, P2 §6). */
    fun remove(routeId: String): Deferred<Unit> = owned { removeOnActor(routeId) }

    /**
     * iOS `installed()` (`PilgrimagePackageManager.swift:89-108@7c200bf`, P2 §4):
     * the first route, in the file system's order, whose package reads and
     * passes. Not a pure read: with no download in flight it finishes a
     * Replace a kill interrupted, and deletes `replacing.txt` whatever it
     * found (pilgrim-ios #123, matched).
     */
    suspend fun installed(): Installed? = onActor { installedOnActor() }

    /**
     * [installed]'s answer with none of its writes, for a surface that only
     * wants the route's name (owner decision 11, P3 addition 8): a Replace
     * a kill interrupted is left for the launch and the route page to
     * finish, and the route it was letting go is passed over as [installed]
     * would have deleted it. iOS's prompt screen calls `installed()` itself
     * (pilgrim-ios #123, matched in the words, not the write).
     */
    suspend fun installedRoute(): Installed? = onActor {
        val found = store.pilgrimageRouteIds().mapNotNull(::readInstalled)
        val abandonedId = if (isDownloading || found.size < 2) null else replacingMarker()
        found.firstOrNull { it.routeId != abandonedId }
    }

    /**
     * The UI process's launch work, after the pending Honor steps (P2 §12,
     * C-12): [installed], which is iOS's launch call and so finishes an
     * interrupted Replace, then every temp set but the one in flight,
     * which iOS leaves to the OS's purge of `tmp/` (A-6). No repair of a
     * half install (owner decision 3, pilgrim-ios #119).
     */
    suspend fun runAtLaunch() {
        onActor {
            installedOnActor()
            sweepTempSets()
        }
    }

    private fun owned(operation: suspend () -> Unit): Deferred<Unit> =
        scope.async(ioDispatcher) { actor.withLock { operation() } }

    private suspend fun <T> onActor(block: suspend () -> T): T = withContext(ioDispatcher) { actor.withLock { block() } }

    /**
     * One of iOS's awaits off the main actor, from inside an operation that
     * holds [actor]: another operation's steps may run meanwhile. The actor
     * is taken back however [block] ends, so the operation's own unlock
     * always finds it held.
     */
    private suspend fun <T> offActor(block: suspend () -> T): T {
        actor.unlock()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { actor.lock() }
        }
    }

    // ---- Download ------------------------------------------------------------

    private suspend fun downloadOnActor(entry: PilgrimageCatalogEntry, release: String) {
        if (signals.refusesForAWalk()) throw refusal(PilgrimageError.WALK_IN_PROGRESS)
        if (isDownloading) throw refusal(PilgrimageError.INCOMPLETE)
        isDownloading = true
        try {
            // route.json is a whole round trip before the first stage lands;
            // without an early phase the route page isn't busy meanwhile.
            _phase.value = Phase.Downloading(done = 0, total = entry.stageCount + 1)
            val temp = File(tempRoot, "$TEMP_SET_PREFIX${UUID.randomUUID()}")
            inFlightTemp = temp
            try {
                transfer(entry, release, temp)
            } finally {
                temp.deleteRecursively()
                inFlightTemp = null
            }
        } finally {
            isDownloading = false
        }
    }

    /** P2 §3's steps 3–10: the temp set, `route.json`, each stage, the budget, the guard, the commit. */
    private suspend fun transfer(entry: PilgrimageCatalogEntry, release: String, temp: File) {
        val cap = maxPackageBytes
        val saveStage = saveStage
        val previousStageCount = installedStageCount(entry.id)
        try {
            Files.createDirectories(temp.toPath())
            val routeUrl = PilgrimageCatalogService.packageUrl(release, entry.id, ROUTE_FILE) ?: throw refusal(PilgrimageError.NOT_WALKABLE)
            val total = entry.stageCount + 1
            // Counted across every file: 200 stages each just under the 2 MiB cap would otherwise be 400 MiB.
            var packageBytes = 0L
            val fetched = offActor { stageRouteFile(entry, routeUrl, temp) }
            packageBytes += fetched.bytes
            checkBudget(packageBytes, cap)
            _phase.value = Phase.Downloading(done = 1, total = total)
            for (index in 0 until fetched.route.stageCount) {
                val stageUrl = PilgrimageCatalogService.packageUrl(release, entry.id, stageFileName(index))
                    ?: throw refusal(PilgrimageError.NOT_WALKABLE)
                val plan = StagePlan(entry.id, stageUrl, fetched.route.stageCount, fetched.route.stages[index])
                packageBytes += offActor { stageOneStage(plan, temp) }
                checkBudget(packageBytes, cap)
                _phase.value = Phase.Downloading(done = index + 2, total = total)
            }
            // A walk begun while the stages streamed: nothing of this package reaches the store.
            if (signals.refusesForAWalk()) throw refusal(PilgrimageError.WALK_IN_PROGRESS)
            val plan = CommitPlan(entry.id, release, fetched.route.stageCount, previousStageCount)
            offActor { commit(plan, temp, saveStage) }
            _phase.value = Phase.Idle
        } catch (e: CancellationException) {
            // Not a failure the walker needs to see; the temp set is still swept.
            _phase.value = Phase.Idle
            throw e
        } catch (e: Exception) {
            val failure = (e as? PilgrimageException)?.error ?: PilgrimageError.INCOMPLETE
            _phase.value = Phase.Failed(failure)
            throw refusal(failure)
        }
    }

    /**
     * iOS `stageRouteFile` (`PilgrimagePackageManager.swift:312-320@7c200bf`):
     * the route file must describe the route the catalog offered (P1 §11).
     * Written as it arrived; the commit puts these bytes down unchanged.
     */
    private suspend fun stageRouteFile(entry: PilgrimageCatalogEntry, routeUrl: HttpUrl, temp: File): FetchedRoute {
        val data = fetch(routeUrl, PilgrimageWayImporter.MAX_ROUTE_BYTES.toLong())
        val route = PilgrimageWayImporter.route(from = data)
        if (route.id != entry.id || route.stageCount != entry.stageCount || route.stages.size != entry.stageCount) {
            throw refusal(PilgrimageError.NOT_WALKABLE)
        }
        writeTemp(File(temp, ROUTE_FILE), data)
        return FetchedRoute(route, data.size)
    }

    /**
     * iOS `stageOneStage` (`PilgrimagePackageManager.swift:344-353@7c200bf`):
     * the importer's checks, then the stage against its row in `route.json`
     * by count and name, so the morning card and the ledger read one
     * package. The name is compared as Swift's `==` compares it, in NFC
     * (P1 A10). Written in the store's own encoding, so the commit decodes
     * and saves rather than parsing untrusted bytes again.
     *
     * @return the bytes the stage cost.
     */
    private suspend fun stageOneStage(plan: StagePlan, temp: File): Int {
        val index = plan.expected.index
        val data = fetch(plan.url, PilgrimageWayImporter.MAX_STAGE_BYTES.toLong())
        val way = PilgrimageWayImporter.way(from = data, routeId = plan.routeId, stageIndex = index)
        val stage = way.stage
        if (stage == null || stage.count != plan.stageCount || stage.name.swiftCompareTo(plan.expected.name) != 0) {
            throw refusal(PilgrimageError.NOT_WALKABLE)
        }
        writeTemp(File(temp, wayFileName(index)), WayJson.encode(way).toByteArray(Charsets.UTF_8))
        return data.size
    }

    /**
     * iOS `fetch(url:cap:session:)` (`PilgrimagePackageManager.swift:355-376@7c200bf`,
     * P1 §3.4): HTTP 200 only, the declared length checked before the body
     * (`<=` passes), then the bytes counted as they arrive (`>` refuses).
     * A status, a cap or a transport failure is `INCOMPLETE`, a full disk
     * `DISK_FULL`; the call is cancelled with the caller.
     */
    private suspend fun fetch(url: HttpUrl, cap: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(onCdn(url)))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(failureOf(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        Result.success(response.use { readCapped(it, cap) })
                    } catch (e: PilgrimageException) {
                        Result.failure(e)
                    } catch (e: Exception) {
                        Result.failure(failureOf(e))
                    }
                    continuation.resumeWith(result)
                }
            },
        )
    }

    /** The same path on this manager's CDN: the production CDN itself, or a test's server. */
    private fun onCdn(url: HttpUrl): HttpUrl = url.newBuilder().scheme(cdn.scheme).host(cdn.host).port(cdn.port).build()

    // ---- Commit ----------------------------------------------------------------

    /**
     * iOS `commit` (`PilgrimagePackageManager.swift:408-425@7c200bf`): the only
     * place a downloaded set becomes the installed route. Every stage in
     * index order, then `route.json` as it arrived, then `release.txt`, the
     * bare tag; [installed] keys on both files. A write that fails rolls back
     * through the larger of the two counts, not only the stages this commit
     * wrote, so a shrinking or same-size Update leaves nothing behind (P2 C-2).
     * The rollback runs to its end even if the operation is cancelled.
     */
    private suspend fun commit(plan: CommitPlan, temp: File, saveStage: (Way) -> Unit) {
        val routeFile = store.routeFile(plan.routeId) ?: throw refusal(PilgrimageError.NOT_WALKABLE)
        val releaseFile = store.releaseFile(plan.routeId) ?: throw refusal(PilgrimageError.NOT_WALKABLE)
        try {
            for (index in 0 until plan.stageCount) {
                saveStage(WayJson.decode(File(temp, wayFileName(index)).readText()))
            }
            store.writePilgrimageFile(routeFile, File(temp, ROUTE_FILE).readBytes())
            store.writePilgrimageFile(releaseFile, plan.release.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            withContext(NonCancellable) {
                // The commit's error is the one the walker hears; a rollback that also fails rides along.
                try {
                    retireStagesAndPackage(plan.routeId, maxOf(plan.previousStageCount, plan.stageCount))
                } catch (rollback: Exception) {
                    e.addSuppressed(rollback)
                }
            }
            if (e is PilgrimageException) throw e
            throw failureOf(e)
        }
    }

    /**
     * iOS `removeStagesAndPackage` and `rollBack`, which are one body
     * (`PilgrimagePackageManager.swift:293-298,432-437@7c200bf`): the stages,
     * `route.json` and `release.txt`. Never `ledger.json`, and never the
     * whole of a walked stage: `retireMany` keeps its `way.json`, replies and
     * link, and counts a live session's stage as walked (P2 A-1).
     */
    private suspend fun retireStagesAndPackage(routeId: String, stageCount: Int) {
        try {
            store.retireMany(stageIds(routeId, 0 until stageCount), signals.liveSessionWayIds())
        } finally {
            // The two files [installed] keys on go even when the live-session read
            // (an Android addition) fails, so a rolled-back route never reads as
            // installed; iOS's rollback can't fail.
            store.routeFile(routeId)?.delete()
            store.releaseFile(routeId)?.delete()
        }
    }

    // ---- Replace, update, remove ------------------------------------------------

    private suspend fun replaceOnActor(entry: PilgrimageCatalogEntry, release: String) {
        if (signals.refusesForAWalk()) throw refusal(PilgrimageError.WALK_IN_PROGRESS)
        // The route already held needs Update's tail sweep and reconcile, not a bare download.
        if (installedOnActor()?.routeId == entry.id) {
            updateOnActor(entry, release)
            return
        }
        val previous = installedOnActor()
        // Before a byte lands, so a kill anywhere in the swap names the route being let go.
        if (previous != null) markReplacing(previous.routeId)
        try {
            downloadOnActor(entry, release)
        } catch (e: Exception) {
            // A busy refusal too: iOS's race as shipped (owner decision 4, pilgrim-ios #119).
            clearReplacingMarker()
            throw e
        }
        if (previous != null && previous.routeId != entry.id) {
            retireStagesAndPackage(previous.routeId, previous.route.stageCount)
            tiles?.remove(previous.routeId)
        }
        clearReplacingMarker()
    }

    private suspend fun updateOnActor(entry: PilgrimageCatalogEntry, release: String) {
        if (signals.refusesForAWalk()) throw refusal(PilgrimageError.WALK_IN_PROGRESS)
        val previousStageCount = installedStageCount(entry.id)
        downloadOnActor(entry, release)
        val fresh = installedOnActor()?.takeIf { it.routeId == entry.id } ?: throw refusal(PilgrimageError.INCOMPLETE)
        val newCount = fresh.route.stageCount
        // A route that shrank leaves stage Ways no row reaches.
        store.retireMany(stageIds(entry.id, newCount until maxOf(previousStageCount, newCount)), signals.liveSessionWayIds())
        tiles?.removeRegions(entry.id, atOrAbove = newCount)
        try {
            ledgers.reconcile(entry.id, fresh.route.stages)
        } catch (e: IOException) {
            // iOS's ledger save is a `try?`: a reconcile that can't be written is dropped, as there.
        }
    }

    private suspend fun removeOnActor(routeId: String) {
        if (signals.refusesForAWalk()) throw refusal(PilgrimageError.WALK_IN_PROGRESS)
        // A Remove between two stages would be undone by the commit landing after it.
        if (isDownloading) throw refusal(PilgrimageError.INCOMPLETE)
        val stageCount = installedOnActor()?.takeIf { it.routeId == routeId }?.route?.stageCount
            ?: PilgrimageWayImporter.MAX_STAGE_COUNT
        retireStagesAndPackage(routeId, stageCount)
        tiles?.remove(routeId)
    }

    // ---- What is on the phone ---------------------------------------------------

    private suspend fun installedOnActor(): Installed? {
        val found = store.pilgrimageRouteIds().mapNotNullTo(ArrayList(), ::readInstalled)
        // A download in flight is the one time both packages are meant to be there.
        if (isDownloading) return found.firstOrNull()
        val abandonedId = replacingMarker() ?: return found.firstOrNull()
        if (found.size > 1) {
            val abandoned = found.firstOrNull { it.routeId == abandonedId }
            if (abandoned != null) {
                retireStagesAndPackage(abandoned.routeId, abandoned.route.stageCount)
                found.removeAll { it.routeId == abandonedId }
            }
        }
        clearReplacingMarker()
        return found.firstOrNull()
    }

    /** A folder counts only when `route.json` passes the importer and `release.txt` the tag rule, untrimmed. */
    private fun readInstalled(routeId: String): Installed? {
        val routeFile = store.routeFile(routeId) ?: return null
        val releaseFile = store.releaseFile(routeId) ?: return null
        return try {
            val route = PilgrimageWayImporter.route(from = routeFile.readBytes())
            val release = releaseFile.readText(Charsets.UTF_8)
            if (PilgrimageCatalogService.isValidRelease(release)) Installed(routeId, release, route) else null
        } catch (e: IOException) {
            null
        } catch (e: PilgrimageException) {
            null
        }
    }

    /** What this route already had before a download changes anything, so a rollback reaches every stage of it. */
    private suspend fun installedStageCount(routeId: String): Int =
        installedOnActor()?.takeIf { it.routeId == routeId }?.route?.stages?.size ?: 0

    /**
     * The route a cross-route Replace is letting go (`PilgrimagePackageManager.swift:110-132@7c200bf`),
     * in `replacing.txt` beside the route folders so it survives the removal of either package.
     */
    private fun replacingMarker(): String? = try {
        store.replacingFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { WayStore.isValidRouteId(it) }
    } catch (e: IOException) {
        null
    }

    private fun markReplacing(routeId: String) {
        try {
            store.writePilgrimageFile(store.replacingFile, routeId.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // iOS's `try?`: a marker that can't be written leaves a kill mid-swap with nothing to say which route won.
        }
    }

    private fun clearReplacingMarker() {
        store.replacingFile.delete()
    }

    /** Every temp set but the one in flight, which can be younger than the launch that sweeps. */
    private fun sweepTempSets(): Int {
        val inFlight = inFlightTemp
        return tempRoot.listFiles().orEmpty().count { it != inFlight && it.deleteRecursively() }
    }

    private class FetchedRoute(val route: PilgrimageRoute, val bytes: Int)

    /** [expected] is `route.json`'s row at this index, safe by position: the rows are exactly `0 until count`. */
    private class StagePlan(val routeId: String, val url: HttpUrl, val stageCount: Int, val expected: PilgrimageRouteStage)

    /** [previousStageCount] is what this route had installed before, so a rollback reaches past what it wrote. */
    private class CommitPlan(val routeId: String, val release: String, val stageCount: Int, val previousStageCount: Int)

    companion object {

        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 300L

        internal const val TEMP_DIRECTORY = "pilgrimage-tmp"
        internal const val TEMP_SET_PREFIX = "pilgrimage-"

        private const val ROUTE_FILE = "route.json"
        private const val READ_CHUNK_BYTES = 8_192L
        private const val HTTP_OK = 200

        /** iOS `stageFileName`: zero-padded from 00, widening to three digits from 100. */
        fun stageFileName(index: Int): String =
            String.format(Locale.ROOT, if (index < 100) "stage-%02d.json" else "stage-%03d.json", index)

        /** iOS `replaceConfirmation(routeName:)`, the "Replace?" alert's message. */
        fun replaceConfirmation(resources: Resources, routeName: String): String =
            resources.getString(R.string.pilgrimage_replace_confirmation, routeName)

        /** iOS `removeConfirmation(routeName:)`: the same promise, asked about the route being let go. */
        fun removeConfirmation(resources: Resources, routeName: String): String =
            resources.getString(R.string.pilgrimage_remove_confirmation, routeName)

        /**
         * iOS's ephemeral package session (`PilgrimagePackageManager.swift:58-63@7c200bf`,
         * P1 C2): a stalled request times out after 30 s and each file's
         * whole fetch after 300 s, a failed connection isn't retried, and
         * OkHttp keeps no cache unless one is set. A redirect is followed
         * only on [cdn]'s scheme, host and port.
         */
        fun httpClient(cdn: HttpUrl): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .stayingOnTheHost(cdn)
            .build()

        /** A plain GET, as iOS's `session.bytes(from:)` sends. */
        internal fun request(url: HttpUrl): Request = Request.Builder().url(url).build()

        private fun wayFileName(index: Int): String = "$index.way.json"

        private fun stageIds(routeId: String, indices: IntRange): List<String> = indices.map { WayStore.stageWayId(routeId, it) }

        /** iOS `checkBudget`: the bytes the phone actually paid, not the index's figure. */
        private fun checkBudget(bytes: Long, cap: Long) {
            if (bytes > cap) throw refusal(PilgrimageError.INCOMPLETE)
        }

        @VisibleForTesting
        internal fun readCapped(response: Response, cap: Long): ByteArray {
            if (response.code != HTTP_OK) throw refusal(PilgrimageError.INCOMPLETE)
            val body = response.body
            // Before draining: an oversized declared length mustn't cost a whole download first.
            if (body.contentLength() > cap) throw refusal(PilgrimageError.INCOMPLETE)
            val source = body.source()
            val buffer = Buffer()
            while (source.read(buffer, READ_CHUNK_BYTES) != -1L) {
                if (buffer.size > cap) throw refusal(PilgrimageError.INCOMPLETE)
            }
            return buffer.readByteArray()
        }

        private fun writeTemp(file: File, bytes: ByteArray) {
            try {
                file.writeBytes(bytes)
            } catch (e: IOException) {
                throw failureOf(e)
            }
        }

        /** iOS's `isDiskFull(error) ? .diskFull : .incomplete`, through `WayMediaDownloadWorker`'s reading of it. */
        private fun failureOf(error: Throwable): PilgrimageException =
            refusal(if (WayMediaDownloadWorker.isDiskFull(error)) PilgrimageError.DISK_FULL else PilgrimageError.INCOMPLETE)

        private fun refusal(error: PilgrimageError) = PilgrimageException(error)
    }
}
