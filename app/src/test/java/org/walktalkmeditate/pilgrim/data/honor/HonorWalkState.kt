// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * Everything a walk with Honor can own, seeded for the delete-path tests:
 * a session row, a moment row, a card row, a staged own-walk Way, a link
 * file, and a marker. The source walk it honors is [SOURCE_WALK_UUID].
 */
internal class HonorWalkState(
    private val db: PilgrimDatabase,
    private val store: WayStore,
) {

    suspend fun seed(walkId: Long, walkUuid: String) {
        val dao = db.honorDao()
        dao.insertSession(
            HonorSessionEntity(
                walkId = walkId,
                wayId = WAY_ID,
                sourceKind = HonorSourceKind.OWN_WALK,
                voicesEnabled = true,
                softTapEnabled = false,
            ),
        )
        dao.upsertMomentState(HonorMomentStateEntity(walkId = walkId, momentId = "voice-1", reachedAt = 5_000L))
        // Unguarded, so a finished walk can be seeded with the rows a pending finalize would find.
        dao.insertCardStateIfAbsent(HonorCardStateEntity(walkId = walkId, momentId = "voice-1", touched = true))
        dao.insertMarker(HonorWalkMarkerEntity(walkUuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN))
        store.stage(walkUuid, way())
        store.link(walkUuid, WAY_ID, WayArrival(theirSeconds = 2_400.0, yourSeconds = 2_100.0))
    }

    /** The walk's live rows and staging are gone; its link file and marker stay. */
    suspend fun assertReleased(walkId: Long, walkUuid: String) {
        val dao = db.honorDao()
        assertNull("session row", dao.getSession(walkId))
        assertTrue("moment rows", dao.getMomentStates(walkId).isEmpty())
        assertTrue("card rows", dao.getCardStates(walkId).isEmpty())
        assertNull("staged Way", store.staged(walkUuid))
        assertKept(walkUuid)
    }

    suspend fun assertKept(walkUuid: String) {
        assertEquals("link file", WayLink(WAY_ID, 2_400.0, 2_100.0), store.wayLink(walkUuid))
        assertNotNull("marker", db.honorDao().getMarker(walkUuid))
    }

    suspend fun assertLive(walkId: Long, walkUuid: String) {
        val dao = db.honorDao()
        assertNotNull("session row", dao.getSession(walkId))
        assertEquals("moment rows", 1, dao.getMomentStates(walkId).size)
        assertEquals("card rows", 1, dao.getCardStates(walkId).size)
        assertNotNull("staged Way", store.staged(walkUuid))
        assertKept(walkUuid)
    }

    fun way() = Way(
        id = WAY_ID,
        source = WaySource.OwnWalk(SOURCE_WALK_UUID),
        title = "Morning loop",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = null,
        expires = null,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = emptyList(),
        weather = null,
    )

    companion object {
        const val SOURCE_WALK_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val WAY_ID = "walk:$SOURCE_WALK_UUID"
    }
}
