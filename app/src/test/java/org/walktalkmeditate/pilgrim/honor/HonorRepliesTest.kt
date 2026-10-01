// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * "reply here"'s filing (parity spec D §7, A §21, correction 3; shared spec
 * S4 §11, correction 18): into a listed Way's folder only, under the
 * voice's own `n`, the origin cleared first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorRepliesTest {

    private val base = File(ApplicationProvider.getApplicationContext<Application>().filesDir, "honor-replies-test")
    private val store = WayStore({ base })
    private val replies = HonorReplies(store)

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun `on a later honoring of an own walk the reply files under its voice's n`() {
        store.save(way(OWN_WAY_ID))
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(mapOf(2 to "recordings/w/reply.wav"), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `filing clears the origin`() {
        store.save(way(OWN_WAY_ID))
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertNull(replies.pending.value)
    }

    @Test
    fun `a later reply to the same voice replaces the earlier one`() {
        store.save(way(OWN_WAY_ID))
        store.setReply(OWN_WAY_ID, originN = 2, relativePath = "recordings/old/reply.wav")
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(mapOf(2 to "recordings/w/reply.wav"), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `on the first honoring of an own walk the reply is lost (pilgrim-ios #98, matched)`() {
        store.stage(LIVE_UUID, way(OWN_WAY_ID))
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(emptyMap<Int, String>() to null, store.replies(OWN_WAY_ID) to replies.pending.value)
    }

    @Test
    fun `a first honoring's last reply is lost though the finalize step listed the Way before it landed`() {
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")
        store.save(way(OWN_WAY_ID))

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(emptyMap<Int, String>(), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `on a share's first honoring the reply is kept`() {
        store.save(way(SHARE_WAY_ID))
        replies.arm(walkId = WALK, wayId = SHARE_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(mapOf(2 to "recordings/w/reply.wav"), store.replies(SHARE_WAY_ID))
    }

    @Test
    fun `another walk's recording leaves the origin armed`() {
        store.save(way(OWN_WAY_ID))
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.fileIfPending(recording("recordings/x/other.wav", walkId = WALK + 1))

        assertEquals("voice-2", replies.pending.value?.momentId)
    }

    @Test
    fun `a recorder that never started leaves nothing armed`() {
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.disarm(WALK)

        assertNull(replies.pending.value)
    }

    @Test
    fun `a discarded walk files no reply`() {
        store.save(way(OWN_WAY_ID))
        replies.arm(walkId = WALK, wayId = OWN_WAY_ID, momentId = "voice-2")

        replies.clear()
        replies.fileIfPending(recording("recordings/w/reply.wav"))

        assertEquals(emptyMap<Int, String>(), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `with nothing armed nothing is filed`() {
        store.save(way(OWN_WAY_ID))

        replies.fileIfPending(recording("recordings/w/plain.wav"))

        assertEquals(emptyMap<Int, String>(), store.replies(OWN_WAY_ID))
    }

    private fun recording(path: String, walkId: Long = WALK) = VoiceRecording(
        walkId = walkId,
        startTimestamp = 1_000L,
        endTimestamp = 2_000L,
        durationMillis = 1_000L,
        fileRelativePath = path,
    )

    private fun way(id: String) = Way(
        id = id,
        source = if (id == SHARE_WAY_ID) {
            WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234")
        } else {
            WaySource.OwnWalk(SOURCE_UUID)
        },
        title = "the long way",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = "UTC",
        expires = null,
        route = (0..3).map { WayPoint(lat = 0.0, lon = it * 0.001, alt = null, t = it * 60.0) },
        totalDistanceMeters = 333.0,
        theirActiveSeconds = 180.0,
        moments = listOf(
            WayMoment(
                id = "voice-2", frac = 0.5, at = null,
                kind = WayMomentKind.Voice(0.6, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v2.wav")),
            ),
        ),
        weather = null,
    )

    private companion object {
        const val WALK = 7L
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val LIVE_UUID = "7f3c2a10-9d4e-4b8a-8c1f-5e6d7a8b9c0d"
        const val OWN_WAY_ID = "walk:$SOURCE_UUID"
        const val SHARE_WAY_ID = "share:AbCdEf1234"
    }
}
