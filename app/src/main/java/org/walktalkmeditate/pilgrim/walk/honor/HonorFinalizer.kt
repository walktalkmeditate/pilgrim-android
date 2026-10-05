// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.util.Log
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkMarkerEntity
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.honor.WaySweeper

/** Where a walk's Honor step stands after [HonorFinalizer.finalize]. */
enum class HonorFinalizeOutcome {
    /** Nothing live is left: the step ran, ran before, or the walk never had Honor. */
    DONE,

    /** The walk is still on; its session owns the rows. */
    NOT_FINISHED,

    /** A write failed; the live rows stay, and [HonorFinalizer.runAtLaunch] retries. */
    PENDING,
}

/**
 * The Honor step after `finishWalkAtomic` has recorded how the walk ended
 * (iOS's walk-end save, `MainCoordinatorView.swift:94-121@7c200bf`, and
 * recovery's `rebindWay`, `WalkSessionGuard+Recovery.swift:133-145@7c200bf`):
 *
 * - **Clean finish:** the link with the arrival numbers, when arrival
 *   fired; then a stage's ledger record; then the staged own-walk Way
 *   listed, overwriting `way.json` and keeping the first `accepted.json`
 *   (parity spec correction 2), or a stage's staged copy discarded, never
 *   listed: a package Way is never saved at walk end. The link and the
 *   record are written even when no Way loads, as iOS links and records
 *   from the Way it holds after a failed save; the summary then reads "a
 *   way that has been removed".
 * - **Recovery:** a link only to a Way already listed, with no numbers
 *   even though the session row holds them (correction 4), and a stage's
 *   record only then, as iOS records only when the stage loads
 *   (pilgrimage-stage spec P2 C-17). An own walk's staging is left for
 *   the launch sweep; a stage's staged copy is discarded.
 *
 * A stage's record comes from the session row alone: the stage it took at
 * Start and the outcome its engine left there, dated the walk's end, so a
 * retry in either process writes the same bytes (P2 A-4, A-7; P3 §10.2).
 * Nothing is recorded for a walk that never anchored on its stage.
 *
 * Then the marker, and the live rows last, so a finished walk still
 * holding a live row is a step still to run, and a marker promises the
 * ledger entry the summary reads. Every sub-step writes the same content
 * when repeated, and both processes may run it for one walk at once. A
 * shared Way is re-saved at the end of every honoring on iOS
 * (`!way.source.isPackageOwned`), writing back the Way the walk read;
 * here nothing can change or remove a listed Way while its walk is on
 * (links are refused, Settings → Ways is hidden, and the sweep holds
 * it), so that save would write the same bytes and is left out.
 *
 * **A failed write is retried, not swallowed.** iOS's `try?` leaves such a
 * walk unlinked, or its stage unrecorded, for good. Here an I/O failure in
 * the link, the record or the promotion keeps the live rows, with the
 * arrival numbers and the outcome, until a later launch writes them: a
 * divergence the parity gate records, in the walker's favour (P2 A-2). A
 * link or a record that can never be written (an id the store refuses)
 * counts as done, so nothing retries forever.
 */
@Singleton
class HonorFinalizer internal constructor(
    private val database: PilgrimDatabase,
    private val wayStore: WayStore,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
    /** Every ledger write goes through the store's two locks (P2 A-3, C-11). */
    private val ledgers: PilgrimageLedgerStore = PilgrimageLedgerStore(wayStore),
    /** The UI process's expiry sweep; never resolved in `:tracker`, which never runs [runAtLaunch]. */
    private val expirySweep: suspend () -> Unit = {},
    /**
     * The pilgrimage packages' launch work ([PilgrimagePackageManager.runAtLaunch]):
     * UI process only, as [expirySweep] is, and with the release flag off
     * the manager isn't even built (pilgrimage-stage spec P2 §12, gaps 9 and 14).
     */
    private val packageLaunchWork: suspend () -> Unit = {},
) {
    @Inject
    constructor(
        database: PilgrimDatabase,
        wayStore: WayStore,
        clock: Clock,
        ledgers: PilgrimageLedgerStore,
        waySweeper: Provider<WaySweeper>,
        releaseFlags: ReleaseFlags,
        packageManager: Provider<PilgrimagePackageManager>,
    ) : this(
        database,
        wayStore,
        clock,
        Dispatchers.IO,
        ledgers = ledgers,
        expirySweep = { waySweeper.get().sweep() },
        packageLaunchWork = { if (releaseFlags.honor) packageManager.get().runAtLaunch() },
    )

    /** The Honor step for one walk; throws only what Room throws, and cancellation. */
    suspend fun finalize(walkId: Long): HonorFinalizeOutcome = withContext(ioDispatcher) {
        val dao = database.honorDao()
        val session = dao.getSession(walkId) ?: return@withContext HonorFinalizeOutcome.DONE
        // A walk gone since the read took its live rows with it.
        val walk = database.walkDao().getById(walkId) ?: return@withContext HonorFinalizeOutcome.DONE
        val endedAt = walk.endTimestamp ?: return@withContext HonorFinalizeOutcome.NOT_FINISHED
        // Every finish path records a kind; a row without one had no clean finish to record.
        val kind = session.finishKind ?: HonorFinishKind.RECOVERED
        try {
            when (kind) {
                HonorFinishKind.CLEAN -> finalizeClean(walk.uuid, session, endedAt)
                HonorFinishKind.RECOVERED -> finalizeRecovered(walk.uuid, session, endedAt)
            }
        } catch (e: IOException) {
            Log.w(TAG, "walk $walkId: Honor step left for the next launch (${e::class.simpleName})")
            return@withContext HonorFinalizeOutcome.PENDING
        }
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid = walk.uuid, finishedAt = endedAt, finishKind = kind))
        dao.deleteLiveRows(walkId)
        HonorFinalizeOutcome.DONE
    }

    /**
     * At launch, after recovery has finished any walk its process lost:
     * retries every Honor step still pending, sweeps staging no walk needs
     * any more and the temp files killed writes left, runs the pilgrimage
     * packages' launch work, then the expiry sweep, which so sees every
     * link recovery and the retry could write (shared-walk spec correction
     * 10: iOS races its recovery, a dated R5 divergence). The package work
     * comes after the pending steps, so a step's ledger record lands
     * before anything retires a stage (P2 §12). Each later step runs
     * whether or not the ones before it failed, as iOS's run on every
     * launch: a Way whose Honor step is still pending is held by its live
     * session row either way. Never throws but for cancellation.
     */
    suspend fun runAtLaunch() {
        deferringFailure("launch Honor maintenance") {
            finalizePending()
            sweepStaging()
        }
        deferringFailure("launch pilgrimage packages", packageLaunchWork)
        deferringFailure("launch expiry sweep", expirySweep)
    }

    private suspend fun deferringFailure(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "$what deferred (${e::class.simpleName})")
        }
    }

    /** @return how many walks' steps completed; one walk's failure leaves the others to run. */
    suspend fun finalizePending(): Int {
        val pending = withContext(ioDispatcher) { database.honorDao().finishedWalkIdsWithLiveSessions() }
        return pending.count { walkId ->
            val outcome = try {
                finalize(walkId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "walk $walkId: Honor step deferred again (${e::class.simpleName})")
                HonorFinalizeOutcome.PENDING
            }
            outcome == HonorFinalizeOutcome.DONE
        }
    }

    /**
     * Removes staging whose walk is gone or finished with no step pending,
     * once untouched for [STAGING_GRACE_MILLIS]: the grace spans a slow
     * tracker start after Begin (the UI's 5 s wait never removes staging)
     * and keeps a recovered first honoring's build past its recovery. A
     * walk still on keeps its staging however old.
     *
     * @return how many staging folders went.
     */
    suspend fun sweepStaging(): Int = withContext(ioDispatcher) {
        val cutoff = clock.now() - STAGING_GRACE_MILLIS
        val dao = database.honorDao()
        var removed = 0
        for (folder in wayStore.listStagingFolders()) {
            if (folder.lastTouchedMillis >= cutoff) continue
            val walk = database.walkDao().getByUuid(folder.walkUuid)
            val stillNeeded = walk != null && (walk.endTimestamp == null || dao.getSession(walk.id) != null)
            if (stillNeeded) continue
            wayStore.discardStaged(folder.walkUuid)
            removed++
        }
        wayStore.sweepTempFiles(olderThanMillis = cutoff)
        removed
    }

    private fun finalizeClean(walkUuid: String, session: HonorSessionEntity, endedAt: Long) {
        val arrival = session.arrivalTheirSeconds?.let { theirs ->
            session.arrivalYourSeconds?.let { yours -> WayArrival(theirSeconds = theirs, yourSeconds = yours) }
        }
        link(walkUuid, session.wayId, arrival)
        recordStage(session, endedAt)
        when (session.sourceKind) {
            HonorSourceKind.OWN_WALK -> wayStore.promoteStaged(walkUuid)
            HonorSourceKind.PILGRIMAGE -> wayStore.discardStaged(walkUuid)
            HonorSourceKind.SHARE -> Unit
        }
    }

    private fun finalizeRecovered(walkUuid: String, session: HonorSessionEntity, endedAt: Long) {
        if (wayStore.load(session.wayId) != null) {
            link(walkUuid, session.wayId, arrival = null)
            recordStage(session, endedAt)
        }
        if (session.sourceKind == HonorSourceKind.PILGRIMAGE) wayStore.discardStaged(walkUuid)
    }

    /**
     * iOS `recordStageWalk` (`MainCoordinatorView.swift:292-295@7c200bf`):
     * the stage's entry in its route's ledger, under the ledger's locks.
     * Nothing for a walk that isn't a stage; the store writes nothing
     * without an outcome, and nothing for a route id it refuses.
     *
     * @throws IOException when the ledger's write fails.
     */
    private fun recordStage(session: HonorSessionEntity, endedAt: Long) {
        val stage = session.stageIdentity() ?: return
        ledgers.record(stage, session.stageOutcome(), at = Instant.ofEpochMilli(endedAt))
    }

    private fun link(walkUuid: String, wayId: String, arrival: WayArrival?) {
        try {
            wayStore.link(walkUuid, wayId, arrival)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "an Honor link the store refuses was skipped")
        }
    }

    internal companion object {
        private const val TAG = "HonorFinalizer"

        const val STAGING_GRACE_MILLIS = 24L * 60 * 60 * 1000
    }
}
