// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareRepairStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val store = ShareRepairStore(context, json)

    // The `preferencesDataStore` delegate caches its DataStore in memory
    // per classloader, NOT per Context instance — deleting the backing
    // file in @After does not reset that in-memory cache between @Test
    // methods running in the same JVM/Robolectric sandbox. A shared
    // literal walkUuid across tests (as this file originally had) leaks
    // state cross-test. JUnit4 constructs a fresh test-class instance
    // per @Test method, so a property initializer gives every test its
    // own unique key — mirrors CachedShareStoreTest's convention of a
    // distinct literal UUID per test, generalized here since this file
    // has many more scenarios than that one.
    private val walkUuid = UUID.randomUUID().toString()

    @After
    fun cleanup() {
        File(context.filesDir, "datastore/share_repair.preferences_pb").delete()
    }

    private fun photoSlot(
        n: Int,
        uri: String = "content://media/$n",
        ts: Long = 1_000L + n,
        status: SlotStatus = SlotStatus.PENDING,
    ) = RepairSlot(SlotKind.PHOTO, n, SlotIdentity.Photo(uri, ts), status)

    private fun audioSlot(
        n: Int,
        recordingUuid: String = "rec-$n",
        status: SlotStatus = SlotStatus.PENDING,
    ) = RepairSlot(SlotKind.AUDIO, n, SlotIdentity.Audio(recordingUuid), status)

    @Test
    fun `load returns null when no record exists`() = runBlocking {
        assertNull(store.load(walkUuid))
    }

    @Test
    fun `prePopulate writes all slots as pending and load round-trips them`() = runBlocking {
        val slots = listOf(photoSlot(1), photoSlot(2), audioSlot(1))
        val record = store.prePopulate(walkUuid, "share-1", slots)

        assertEquals("share-1", record.shareId)
        assertEquals(3, record.slots.size)
        assertTrue(record.slots.all { it.status == SlotStatus.PENDING })

        val loaded = requireNotNull(store.load(walkUuid))
        assertEquals(record.shareId, loaded.shareId)
        assertEquals(record.slots.toSet(), loaded.slots.toSet())
    }

    @Test
    fun `round-trip preserves full identity fields, not just kind and n`() = runBlocking {
        val slots = listOf(
            photoSlot(2, uri = "content://media/external/images/42", ts = 1_700_000_000L),
            audioSlot(1, recordingUuid = "9c9b1f2e-aaaa-bbbb-cccc-000000000001"),
        )
        store.prePopulate(walkUuid, "share-1", slots)

        val loaded = requireNotNull(store.load(walkUuid))
        val photo = loaded.slots.first { it.kind == SlotKind.PHOTO }
        val audio = loaded.slots.first { it.kind == SlotKind.AUDIO }
        assertEquals(SlotIdentity.Photo("content://media/external/images/42", 1_700_000_000L), photo.identity)
        assertEquals(SlotIdentity.Audio("9c9b1f2e-aaaa-bbbb-cccc-000000000001"), audio.identity)
    }

    @Test
    fun `markUploaded flips exactly one slot leaving siblings untouched`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1), photoSlot(2), audioSlot(1)))
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)

        val loaded = requireNotNull(store.load(walkUuid))
        assertEquals(SlotStatus.UPLOADED, loaded.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.status)
        assertEquals(SlotStatus.PENDING, loaded.slots.first { it.kind == SlotKind.PHOTO && it.n == 2 }.status)
        assertEquals(SlotStatus.PENDING, loaded.slots.first { it.kind == SlotKind.AUDIO && it.n == 1 }.status)
    }

    @Test
    fun `markUploaded on a walk with no record is a no-op`() = runBlocking {
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)
        assertNull(store.load(walkUuid))
    }

    @Test
    fun `clear removes the whole record`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1)))
        store.clear(walkUuid)
        assertNull(store.load(walkUuid))
    }

    @Test
    fun `prePopulate for the same shareId upserts — new slots add, unmentioned slots survive`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1), photoSlot(2)))
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)

        // A repair pass touching only photo/2 plus a brand new audio slot — photo/1 (already uploaded) must survive untouched even though this call doesn't mention it.
        val record = store.prePopulate(walkUuid, "share-1", listOf(photoSlot(2), audioSlot(1)))

        assertEquals(3, record.slots.size)
        assertEquals(SlotStatus.UPLOADED, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.status)
        assertEquals(SlotStatus.PENDING, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 2 }.status)
        assertEquals(SlotStatus.PENDING, record.slots.first { it.kind == SlotKind.AUDIO && it.n == 1 }.status)
    }

    @Test
    fun `prePopulate for the same shareId does not regress an already-uploaded slot back to pending`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1)))
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)

        val record = store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1)))

        assertEquals(SlotStatus.UPLOADED, record.slots.single().status)
    }

    @Test
    fun `prePopulate refuses a conflicting identity for an already-recorded slot`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1, uri = "content://original", ts = 1000L)))

        val record = store.prePopulate(
            walkUuid,
            "share-1",
            listOf(photoSlot(1, uri = "content://different", ts = 9999L)),
        )

        val slot = record.slots.single()
        assertEquals(
            "the ORIGINAL identity must survive — the conflicting proposal is refused, not written",
            SlotIdentity.Photo("content://original", 1000L),
            slot.identity,
        )
    }

    @Test
    fun `prePopulate with a different shareId replaces the whole record — stale-clearing`() = runBlocking {
        store.prePopulate(walkUuid, "share-old", listOf(photoSlot(1), audioSlot(1)))
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)

        val record = store.prePopulate(walkUuid, "share-new", listOf(audioSlot(1)))

        assertEquals("share-new", record.shareId)
        assertEquals(1, record.slots.size)
        assertEquals(SlotKind.AUDIO, record.slots.single().kind)
        assertEquals(
            "a fresh shareId must never inherit stale status/slots from an earlier attempt",
            SlotStatus.PENDING,
            record.slots.single().status,
        )
    }

    @Test
    fun `a fresh store instance over the same DataStore sees the same record — process-death simulation`() = runBlocking {
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1), photoSlot(2), audioSlot(1)))
        store.markUploaded(walkUuid, SlotKind.PHOTO, 1)

        // A brand new wrapper instance — nothing but the on-disk DataStore file connects it to `store`.
        val revivedStore = ShareRepairStore(context, json)
        val loaded = requireNotNull(revivedStore.load(walkUuid))

        assertEquals("share-1", loaded.shareId)
        assertEquals(SlotStatus.UPLOADED, loaded.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.status)
        assertEquals(SlotStatus.PENDING, loaded.slots.first { it.kind == SlotKind.PHOTO && it.n == 2 }.status)
        assertEquals(SlotStatus.PENDING, loaded.slots.first { it.kind == SlotKind.AUDIO && it.n == 1 }.status)
    }

    @Test
    fun `records for different walks are independent`() = runBlocking {
        val otherWalk = "ffffffff-0000-0000-0000-000000000000"
        store.prePopulate(walkUuid, "share-1", listOf(photoSlot(1)))
        assertNull(store.load(otherWalk))
    }

    @Test
    fun `walkUuidsWithRecords lists every walk holding a record and drops them on clear`() = runBlocking {
        // The keep-set OrphanSweeperWorker hands SharePrepStore.sweepOrphans:
        // a walk with un-landed slots is exactly the walk whose transcode
        // artifacts a repair pass may still need (port plan Decision 3).
        val other = UUID.randomUUID().toString()
        store.prePopulate(walkUuid, "share-a", listOf(audioSlot(1)))
        store.prePopulate(other, "share-b", listOf(photoSlot(1)))

        val uuids = store.walkUuidsWithRecords()
        assertTrue("$walkUuid missing from $uuids", walkUuid in uuids)
        assertTrue("$other missing from $uuids", other in uuids)

        store.clear(other)
        val afterClear = store.walkUuidsWithRecords()
        assertTrue(walkUuid in afterClear)
        assertTrue("a cleared record must leave the keep set", other !in afterClear)
    }

    @Test
    fun `sweepStale drops records whose walk is gone and returns only the live keep set`() = runBlocking {
        // Deleting a walk is the walker asking for its recordings to be
        // gone; a surviving record would keep the derived AAC copies
        // pinned against every future sweep instead.
        val deleted = UUID.randomUUID().toString()
        store.prePopulate(walkUuid, "share-live", listOf(audioSlot(1)))
        store.prePopulate(deleted, "share-gone", listOf(audioSlot(1)))

        val keep = store.sweepStale(liveWalkUuids = setOf(walkUuid))

        assertEquals(setOf(walkUuid), keep)
        assertNull("a record for a deleted walk must not survive the sweep", store.load(deleted))
        assertTrue("the live walk's record is untouched", store.load(walkUuid) != null)
    }
}
