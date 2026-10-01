// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayLink
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way

/**
 * What the after-the-walk surfaces read of one walk's Honor (iOS's three
 * Way-store reads, `WalkSummaryView.swift:740-755@7c200bf`): the Way it
 * honored, the arrival numbers the link carries, and the Way's replies.
 * [way] is null when the Way is gone; [arrival] is null until the Honor
 * step has written the link, and for a walk that never arrived.
 */
data class HonorWalkRecord(
    val way: Way?,
    val arrival: WayArrival?,
    val replies: Map<Int, String>,
) {
    companion object {
        /** No link and no live session: iOS's only "Way missing" state (parity spec G §10). */
        val NONE = HonorWalkRecord(way = null, arrival = null, replies = emptyMap())
    }
}

/**
 * Reads a finished walk's [HonorWalkRecord] in the UI process. iOS writes
 * the link before any surface opens; here the summary can open while the
 * Honor step is still to run, or after it failed and waits for the next
 * launch (plan U17). Until then the walk's live session row names the
 * Way, so the surfaces render from it at once, with no arrival numbers:
 * those reach the record through the link. Once the step has run, the
 * link is the record, as on iOS.
 *
 * Files raise no invalidation, so [observe] re-reads when the session
 * row or the walk's marker changes: the step writes the link, then the
 * marker, then deletes the session row. Callers check the release flag.
 */
class HonorWalkRecords internal constructor(
    private val honorDao: HonorDao,
    private val wayStore: WayStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(honorDao: HonorDao, wayStore: WayStore) : this(honorDao, wayStore, Dispatchers.IO)

    suspend fun record(walkId: Long, walkUuid: String): HonorWalkRecord = withContext(ioDispatcher) {
        read(walkUuid, honorDao.getSession(walkId))
    }

    fun observe(walkId: Long, walkUuid: String): Flow<HonorWalkRecord> =
        combine(
            honorDao.observeSession(walkId).distinctUntilChanged(),
            honorDao.observeMarker(walkUuid).distinctUntilChanged(),
        ) { session, _ -> session }
            .map { session -> read(walkUuid, session) }
            .flowOn(ioDispatcher)
            .distinctUntilChanged()

    private fun read(walkUuid: String, session: HonorSessionEntity?): HonorWalkRecord {
        if (session != null) {
            return HonorWalkRecord(
                way = wayThePendingStepLinks(walkUuid, session),
                arrival = null,
                replies = wayStore.replies(session.wayId),
            )
        }
        val link = wayStore.wayLink(walkUuid) ?: return HonorWalkRecord.NONE
        return HonorWalkRecord(
            way = wayStore.load(link.wayId),
            arrival = link.arrival(),
            replies = wayStore.replies(link.wayId),
        )
    }

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
