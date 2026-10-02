// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The expiry sweep's rule (iOS `WayStoreTests.swift:38-77@7c200bf`,
 * shared-walk spec S3 §12 and AE3 as corrected): an expired share nobody
 * walked goes whole; an expired walked share keeps its line, its replies
 * and its links and loses only its media; own-walk Ways are never swept;
 * and a Way a live walk is honoring is left whole (spec correction 9).
 */
class WayStoreSweepTest {

    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.ofEpochSecond(2_000_000)
    private val past = now.minusSeconds(1)
    private val future = now.plusSeconds(86_400)
    private lateinit var store: WayStore

    @Before
    fun setUp() {
        store = WayStore({ File(folder.root, "Ways") }, Clock { now.toEpochMilli() }, syncDirectory = { true })
    }

    private fun share(id: String, expires: Instant?) = Way(
        id = id,
        source = WaySource.Share(id = id.removePrefix("share:"), pageUrl = "https://walk.pilgrimapp.org/x"),
        title = id,
        departedAt = now,
        tzIdentifier = null,
        expires = expires,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = emptyList(),
        weather = null,
    )

    private fun ownWay(): Way {
        val uuid = UUID.randomUUID().toString()
        return share("walk:$uuid", expires = null).copy(source = WaySource.OwnWalk(uuid))
    }

    private fun giveMedia(id: String) {
        File(folder.root, "Ways/$id/media/audio/1.m4a").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
    }

    // iOS `testSweepFollowsTheThreeRowTable`.
    @Test
    fun `the sweep follows iOS's three-row table`() {
        val own = ownWay()
        listOf(share(UNWALKED, past), share(WALKED, past), share(LIVE, future), own).forEach(store::save)
        store.link(UUID.randomUUID().toString(), WALKED, arrival = null)
        listOf(UNWALKED, WALKED, LIVE).forEach(::giveMedia)

        val swept = store.sweepExpired(now, held = emptySet())

        assertEquals(setOf(UNWALKED, WALKED), swept.toSet())
        assertNull("whole folder gone", store.load(UNWALKED))
        assertNotNull("way.json kept", store.load(WALKED))
        assertFalse("media gone", store.hasMedia(WALKED))
        assertTrue(store.hasMedia(LIVE))
        assertNotNull(store.load(own.id))
    }

    @Test
    fun `an expired share nobody walked leaves with its media, and nothing is left of its folder`() {
        store.save(share(UNWALKED, past))
        giveMedia(UNWALKED)
        val deletionsBefore = store.deletions.value

        store.sweepExpired(now, held = emptySet())

        assertFalse(File(folder.root, "Ways/$UNWALKED").exists())
        assertTrue(store.list().isEmpty())
        assertEquals("a surface holding its line re-reads", deletionsBefore + 1, store.deletions.value)
    }

    // AE3 as corrected: the summary keeps its title, ghost line, delta and replies.
    @Test
    fun `an expired walked share keeps its line, its delta and its replies, and loses its media`() {
        val way = share(WALKED, past)
        store.save(way)
        val walk = UUID.randomUUID().toString()
        store.link(walk, WALKED, WayArrival(theirSeconds = 600.0, yourSeconds = 540.0))
        store.setReply(WALKED, originN = 3, relativePath = "recordings/x/y.wav")
        giveMedia(WALKED)
        store.mediaPartialFile(WALKED, "audio/2.m4a")!!.writeBytes(byteArrayOf(2))

        store.sweepExpired(now, held = emptySet())

        assertEquals(way.route, store.way(walk)?.route)
        assertEquals(way.title, store.way(walk)?.title)
        assertEquals(WayLink(WALKED, 600.0, 540.0), store.wayLink(walk))
        assertEquals(mapOf(3 to "recordings/x/y.wav"), store.replies(WALKED))
        assertFalse(store.hasMedia(WALKED))
        assertFalse("a file still gathering goes with the media", store.mediaPartialFile(WALKED, "audio/2.m4a")!!.exists())
        assertNotNull(store.acceptedAt(WALKED))
    }

    @Test
    fun `a deleted walk's link still makes its share walked`() {
        store.save(share(WALKED, past))
        val walk = UUID.randomUUID().toString()
        store.link(walk, WALKED, arrival = null)
        giveMedia(WALKED)

        // No walk-removal path touches the links; the store never sees the walk row at all.
        store.sweepExpired(now, held = emptySet())

        assertNotNull(store.load(WALKED))
    }

    @Test
    fun `own-walk Ways are never swept`() {
        val own = ownWay()
        store.save(own)
        store.link(UUID.randomUUID().toString(), own.id, arrival = null)

        assertTrue(store.sweepExpired(now.plusSeconds(10L * 365 * 86_400), held = emptySet()).isEmpty())
        assertEquals(own, store.load(own.id))
    }

    // Spec correction 9: iOS never sweeps during a walk, so neither the folder nor the media of a held Way goes.
    @Test
    fun `a Way a live walk is honoring survives a mid-walk sweep with its media`() {
        listOf(share(UNWALKED, past), share(WALKED, past)).forEach(store::save)
        store.link(UUID.randomUUID().toString(), WALKED, arrival = null)
        giveMedia(UNWALKED)
        giveMedia(WALKED)

        val swept = store.sweepExpired(now, held = setOf(UNWALKED, WALKED))

        assertTrue(swept.isEmpty())
        assertTrue(store.hasMedia(UNWALKED))
        assertTrue(store.hasMedia(WALKED))
    }

    // `expires <= now`, the importer's boundary from the other side (S3 §12); `way.json` keeps whole seconds.
    @Test
    fun `a share expires at its expiry exactly, not a second before`() {
        store.save(share(UNWALKED, now))
        store.save(share(LIVE, now.plusSeconds(1)))

        assertEquals(listOf(UNWALKED), store.sweepExpired(now, held = emptySet()))
        assertNotNull(store.load(LIVE))
    }

    @Test
    fun `an expired walked share is touched again by every sweep, so each can cancel its gather`() {
        store.save(share(WALKED, past))
        store.link(UUID.randomUUID().toString(), WALKED, arrival = null)

        assertEquals(listOf(WALKED), store.sweepExpired(now, held = emptySet()))
        assertEquals(listOf(WALKED), store.sweepExpired(now, held = emptySet()))
    }

    private companion object {
        const val UNWALKED = "share:unwalkedXX"
        const val WALKED = "share:walkedXXXX"
        const val LIVE = "share:liveliveli"
    }
}
