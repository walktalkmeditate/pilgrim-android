// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayFileStamp
import org.walktalkmeditate.pilgrim.data.honor.WayLink
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.domain.honor.Way

/**
 * What the after-the-walk surfaces read of one walk's Honor (iOS's four
 * Way-store reads, `WalkSummaryView.swift:740-755@7c200bf`): the Way it
 * honored, the arrival numbers the link carries, the Way's replies, and,
 * for a stage, its route's ledger. [way] is null when the Way is gone;
 * [arrival] is null until the Honor step has written the link, and for a
 * walk that never arrived; [ledger] is null for a Way that isn't a stage
 * and for a route with no ledger yet.
 */
data class HonorWalkRecord(
    val way: Way?,
    val arrival: WayArrival?,
    val replies: Map<Int, String>,
    val ledger: PilgrimageLedger?,
) {
    companion object {
        /** No link and no live session: iOS's only "Way missing" state (parity spec G §10). */
        val NONE = HonorWalkRecord(way = null, arrival = null, replies = emptyMap(), ledger = null)
    }
}

/**
 * Reads a finished walk's [HonorWalkRecord] in the UI process. iOS writes
 * the link before any surface opens; here the summary can open while the
 * Honor step is still to run, or after it failed and waits for the next
 * launch (plan U17). Until then the walk's live session row names the
 * Way, so the surfaces render from it at once, with no arrival numbers:
 * those reach the record through the link. Once the step's marker has
 * landed, the link is the record, as on iOS, even while a session row
 * whose delete failed waits for the next launch's retry.
 *
 * Files raise no invalidation, so [observe] re-reads when the session
 * row or the walk's marker changes: the step writes the link, then a
 * stage's ledger record, then the marker, then deletes the session row.
 * So a stage's "X of Y km of the stage" reads the ledger as it stood
 * until the marker lands (an earlier walk's entry, or none), then this
 * walk's (pilgrimage-stage spec P5 §11.5, A3). Callers check the release
 * flag.
 */
class HonorWalkRecords internal constructor(
    private val honorDao: HonorDao,
    private val wayStore: WayStore,
    private val ledgers: PilgrimageLedgerStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(honorDao: HonorDao, wayStore: WayStore, ledgers: PilgrimageLedgerStore) :
        this(honorDao, wayStore, ledgers, Dispatchers.IO)

    /** Listed Ways [honoredWays] decoded, each kept until its `way.json` changes. */
    private val listedWays = ConcurrentHashMap<String, StampedWay>()

    /** Ticks when this process deletes a Way, whose line a seal then loses (owner decision 3). */
    val wayDeletions: Flow<Long> get() = wayStore.deletions

    suspend fun record(walkId: Long, walkUuid: String): HonorWalkRecord = withContext(ioDispatcher) {
        read(walkUuid, sessionUntilMarked(walkId, walkUuid))
    }

    fun observe(walkId: Long, walkUuid: String): Flow<HonorWalkRecord> =
        combine(
            honorDao.observeSession(walkId).distinctUntilChanged(),
            honorDao.observeMarker(walkUuid).distinctUntilChanged(),
        ) { session, marker -> session.takeIf { marker == null } }
            .map { session -> read(walkUuid, session) }
            .flowOn(ioDispatcher)
            .distinctUntilChanged()

    /**
     * The Way each of the finished [walks] honored, by walk id, for the
     * seals' Way lines: [record]'s rule without the replies, one query
     * for the live sessions of the whole set, and one `way.json` decode
     * per listed Way, which later calls reuse until the file changes.
     */
    suspend fun honoredWays(walks: Collection<Walk>): Map<Long, Way?> = withContext(ioDispatcher) {
        if (walks.isEmpty()) return@withContext emptyMap()
        val withLiveSession = honorDao.finishedWalkIdsWithLiveSessions().toHashSet()
        walks.associate { walk ->
            val session = if (walk.id in withLiveSession) sessionUntilMarked(walk.id, walk.uuid) else null
            val way = if (session != null) {
                wayThePendingStepLinks(walk.uuid, session)
            } else {
                wayStore.wayId(walk.uuid)?.let(::listedWay)
            }
            walk.id to way
        }
    }

    /** The live session row, until the step's marker says the link holds the record. */
    private suspend fun sessionUntilMarked(walkId: Long, walkUuid: String): HonorSessionEntity? =
        if (honorDao.getMarker(walkUuid) != null) null else honorDao.getSession(walkId)

    private fun listedWay(id: String): Way? {
        val stamp = wayStore.wayFileStamp(id)
        if (stamp == null) {
            listedWays.remove(id)
            return null
        }
        listedWays[id]?.takeIf { it.stamp == stamp }?.let { return it.way }
        val way = wayStore.load(id)
        listedWays[id] = StampedWay(stamp, way)
        return way
    }

    private class StampedWay(val stamp: WayFileStamp, val way: Way?)

    private fun read(walkUuid: String, session: HonorSessionEntity?): HonorWalkRecord {
        if (session != null) {
            val way = wayThePendingStepLinks(walkUuid, session)
            return HonorWalkRecord(
                way = way,
                arrival = null,
                replies = wayStore.replies(session.wayId),
                ledger = ledgerOf(way),
            )
        }
        val link = wayStore.wayLink(walkUuid) ?: return HonorWalkRecord.NONE
        val way = wayStore.load(link.wayId)
        return HonorWalkRecord(
            way = way,
            arrival = link.arrival(),
            replies = wayStore.replies(link.wayId),
            ledger = ledgerOf(way),
        )
    }

    /** iOS `way?.stage.flatMap { PilgrimageLedgerStore().load(routeId: $0.routeId) }`. */
    private fun ledgerOf(way: Way?): PilgrimageLedger? = way?.stage?.let { ledgers.load(it.routeId) }

    /**
     * As `HonorFinalizer` will link it: a clean finish of an own walk
     * lists its staged build over any earlier one; recovery, and any row
     * with no clean finish recorded, links only a Way already listed.
     */
    private fun wayThePendingStepLinks(walkUuid: String, session: HonorSessionEntity): Way? {
        val listsStagedBuild = session.finishKind == HonorFinishKind.CLEAN &&
            session.sourceKind == HonorSourceKind.OWN_WALK
        return if (listsStagedBuild) {
            wayStore.staged(walkUuid) ?: wayStore.load(session.wayId)
        } else {
            wayStore.load(session.wayId)
        }
    }

    private fun WayLink.arrival(): WayArrival? {
        val theirs = theirSeconds ?: return null
        val yours = yourSeconds ?: return null
        return WayArrival(theirSeconds = theirs, yourSeconds = yours)
    }
}
