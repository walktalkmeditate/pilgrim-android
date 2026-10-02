// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.di.WayStoreModule
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind

/**
 * Port of iOS `WayStoreTests.swift@7c200bf`, less its sweep tests
 * (`sweepExpired` is [WayStoreSweepTest]'s; `retireMany` is Stage 21-2's),
 * plus Android's link files, staging, and media gathering. Robolectric only for the
 * module's `noBackupFilesDir` root; every other test runs on a temp folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.ofEpochSecond(2_000_000)
    private var clockMillis = now.toEpochMilli()
    private lateinit var dir: File
    private lateinit var store: WayStore

    @Before
    fun setUp() {
        dir = File(folder.root, "Ways")
        clockMillis = now.toEpochMilli()
        store = WayStore({ dir }, Clock { clockMillis })
    }

    private fun way(id: String, expires: Instant? = null, title: String = id) = Way(
        id = id,
        source = WaySource.Share(id = "abc", pageUrl = "https://walk.pilgrimapp.org/abc"),
        title = title,
        departedAt = now,
        tzIdentifier = null,
        expires = expires,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = emptyList(),
        weather = null,
    )

    private fun ownWay(walkUuid: String = SOURCE_WALK, title: String = "own") = Way(
        id = "walk:$walkUuid",
        source = WaySource.OwnWalk(walkUuid),
        title = title,
        departedAt = now,
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = listOf(WayPoint(42.88, -8.54, 260.0, 0.0), WayPoint(42.881, -8.54, null, 90.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 90.0,
        moments = listOf(
            WayMoment(
                id = "voice-1",
                frac = 0.5,
                at = null,
                kind = WayMomentKind.Voice(
                    endFrac = 0.6,
                    duration = 12.0,
                    kind = VoiceKind.SPOKEN,
                    media = WayMedia.Recording("recordings/$walkUuid/a.wav"),
                ),
                transcript = "the river bends here",
            ),
        ),
        weather = null,
    )

    // ---- iOS WayStoreTests ----

    @Test
    fun `save, load, and list a Way`() {
        store.save(way("share:aaaaaaaaaa"))

        assertEquals("share:aaaaaaaaaa", store.load("share:aaaaaaaaaa")?.title)
        assertEquals(listOf("share:aaaaaaaaaa"), store.list().map { it.id })
    }

    @Test
    fun `a media path that leaves the Way's media folder is refused`() {
        store.save(way("share:aaaaaaaaaa"))

        assertEquals(null, store.mediaFile("share:aaaaaaaaaa", "../../../databases/pilgrim.db"))
        assertEquals(null, store.mediaFile("share:aaaaaaaaaa", "../way.json"))
    }

    @Test
    fun `links and replies survive media deletion`() {
        store.save(way("share:aaaaaaaaaa"))
        val walk = UUID.randomUUID().toString()
        store.link(walk, "share:aaaaaaaaaa", WayArrival(theirSeconds = 600.0, yourSeconds = 540.0))
        store.setReply("share:aaaaaaaaaa", originN = 3, relativePath = "recordings/x/y.wav")
        val media = store.mediaFile("share:aaaaaaaaaa", "audio/1.m4a")!!
        media.parentFile!!.mkdirs()
        media.writeBytes(ByteArray(1024) { 1 })

        assertTrue(store.diskUsage("share:aaaaaaaaaa") >= 1024)
        assertTrue(store.hasMedia("share:aaaaaaaaaa"))
        store.deleteMedia("share:aaaaaaaaaa")

        assertFalse(store.hasMedia("share:aaaaaaaaaa"))
        assertEquals("share:aaaaaaaaaa", store.wayId(walk))
        assertEquals(WayLink("share:aaaaaaaaaa", theirSeconds = 600.0, yourSeconds = 540.0), store.wayLink(walk))
        assertEquals(mapOf(3 to "recordings/x/y.wav"), store.replies("share:aaaaaaaaaa"))
        assertEquals("share:aaaaaaaaaa", store.way(walk)?.id)
    }

    @Test
    fun `stray folder names are ignored and bad ids refused`() {
        File(dir, "..%2Fescape").mkdirs()

        assertTrue(store.list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.save(way("share:../x")) }
        assertFalse(WayStore.isValidId("index.json"))
        assertTrue(WayStore.isValidId("walk:${UUID.randomUUID()}"))
        assertTrue(WayStore.isValidId("walk:${UUID.randomUUID().toString().uppercase()}"))
    }

    @Test
    fun `read paths guard invalid ids without throwing`() {
        assertNull(store.load("index.json"))
        assertEquals(emptyMap<Int, String>(), store.replies("../x"))
        assertThrows(IllegalArgumentException::class.java) {
            store.link(UUID.randomUUID().toString(), "../x", arrival = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.setReply("../x", originN = 1, relativePath = "a")
        }
    }

    @Test
    fun `delete removes everything and every link to the Way`() {
        store.save(way("share:aaaaaaaaaa"))
        val walk = UUID.randomUUID().toString()
        store.link(walk, "share:aaaaaaaaaa", arrival = null)

        store.delete("share:aaaaaaaaaa")

        assertNull(store.load("share:aaaaaaaaaa"))
        assertNull(store.wayId(walk))
        assertEquals(0L, store.diskUsage(store.list()))
    }

    @Test
    fun `list returns the newest acceptance first`() {
        store.save(way("share:firstfirst"))
        clockMillis += 60_000
        store.save(way("share:secondsecd"))

        assertEquals(listOf("share:secondsecd", "share:firstfirst"), store.list().map { it.id })
    }

    @Test
    fun `re-saving a Way keeps its acceptedAt and overwrites way json`() {
        store.save(way("share:aaaaaaaaaa"))
        val firstAcceptedAt = store.acceptedAt("share:aaaaaaaaaa")
        clockMillis += 60_000

        store.save(way("share:aaaaaaaaaa", title = "updated"))

        assertEquals(firstAcceptedAt, store.acceptedAt("share:aaaaaaaaaa"))
        assertEquals(now, firstAcceptedAt)
        assertEquals("updated", store.load("share:aaaaaaaaaa")?.title)
    }

    // ---- ids: iOS's allow-list, before any path use ----

    @Test
    fun `the id allow-list accepts exactly iOS's three shapes`() {
        assertTrue(WayStore.isValidId("share:Ab0_-Ab0_-"))
        assertTrue(WayStore.isValidId("walk:" + "a".repeat(36)))
        assertTrue(WayStore.isValidId("pilgrimage:camino-frances:12"))
        assertTrue(WayStore.isValidId("pilgrimage:a:0"))
    }

    @Test
    fun `the id allow-list refuses traversal, wrong lengths, and unknown prefixes`() {
        listOf(
            "walk:../../../../../../../../../../../../x",
            "share:../abcdefg",
            "share:abcdefghi",
            "share:abcdefghijk",
            "walk:" + "a".repeat(35),
            "walk:" + "a".repeat(37),
            "walk:" + "g".repeat(36),
            "pilgrimage:Camino:1",
            "pilgrimage:camino:1234",
            "pilgrimage::1",
            "seek:abcdefghij",
            "Share:abcdefghij",
            "links",
            "staging",
            "",
            "share:abcdefghij\n",
            " share:abcdefghij",
        ).forEach { id -> assertFalse("'$id' must be refused", WayStore.isValidId(id)) }
    }

    @Test
    fun `a refused id touches no file`() {
        assertThrows(IllegalArgumentException::class.java) { store.save(way("share:../x")) }
        assertThrows(IllegalArgumentException::class.java) { store.mediaDirectory("../x") }
        store.delete("../x")
        store.deleteMedia("../x")

        assertFalse("nothing was created", dir.exists())
        assertFalse(File(folder.root, "x").exists())
        assertEquals(0L, store.diskUsage("../x"))
        assertFalse(store.hasMedia("../x"))
        assertNull(store.acceptedAt("../x"))
    }

    // ---- save overwrites, accepted.json once (parity spec correction 2) ----

    @Test
    fun `accepted json is written only when absent`() {
        store.save(way("share:aaaaaaaaaa"))
        val accepted = File(dir, "share:aaaaaaaaaa/accepted.json")
        val first = accepted.readText()
        clockMillis += 3_600_000

        store.save(way("share:aaaaaaaaaa"))

        assertEquals(first, accepted.readText())
        assertEquals("""{"acceptedAt":"1970-01-24T03:33:20Z"}""", first)
    }

    // ---- replies (parity spec correction 3) ----

    @Test
    fun `a second reply to the same voice replaces the first`() {
        store.save(ownWay())
        store.setReply(OWN_ID, originN = 1, relativePath = "recordings/a/1.wav")
        store.setReply(OWN_ID, originN = 2, relativePath = "recordings/a/2.wav")
        store.setReply(OWN_ID, originN = 1, relativePath = "recordings/b/1.wav")

        assertEquals(mapOf(1 to "recordings/b/1.wav", 2 to "recordings/a/2.wav"), store.replies(OWN_ID))
        assertEquals(
            """{"1":"recordings/b/1.wav","2":"recordings/a/2.wav"}""",
            File(dir, "$OWN_ID/replies.json").readText(),
        )
    }

    @Test
    fun `a reply with no listed Way folder fails and creates nothing, as on iOS`() {
        assertThrows(IOException::class.java) {
            store.setReply(OWN_ID, originN = 1, relativePath = "recordings/a/1.wav")
        }

        assertFalse(File(dir, OWN_ID).exists())
        assertEquals(emptyMap<Int, String>(), store.replies(OWN_ID))
    }

    @Test
    fun `a staged Way is never a home for replies`() {
        store.stage(WALK, ownWay())

        assertThrows(IOException::class.java) {
            store.setReply(OWN_ID, originN = 1, relativePath = "recordings/a/1.wav")
        }
        assertEquals(listOf("way.json"), File(dir, "staging/$WALK").list()!!.toList())
    }

    @Test
    fun `replies keys that are not integers are dropped`() {
        store.save(ownWay())
        File(dir, "$OWN_ID/replies.json").writeText("""{"1":"a","x":"b","-1":"c"}""")

        assertEquals(mapOf(1 to "a", -1 to "c"), store.replies(OWN_ID))
    }

    // ---- links: one file per walk, atomic ----

    @Test
    fun `a link round-trips with and without arrival numbers`() {
        val arrived = UUID.randomUUID().toString()
        val unfinished = UUID.randomUUID().toString().uppercase()
        store.link(arrived, OWN_ID, WayArrival(theirSeconds = 2400.0, yourSeconds = 2100.0))
        store.link(unfinished, OWN_ID, arrival = null)

        assertEquals(WayLink(OWN_ID, 2400.0, 2100.0), store.wayLink(arrived))
        assertEquals(WayLink(OWN_ID), store.wayLink(unfinished))
        assertEquals(
            """{"theirSeconds":2400.0,"wayId":"$OWN_ID","yourSeconds":2100.0}""",
            File(dir, "links/$arrived.json").readText(),
        )
        assertEquals("""{"wayId":"$OWN_ID"}""", File(dir, "links/$unfinished.json").readText())
    }

    @Test
    fun `a link file is named for the walk uuid verbatim`() {
        val imported = UUID.randomUUID().toString().uppercase()
        store.link(imported, OWN_ID, arrival = null)

        assertEquals(listOf("$imported.json"), File(dir, "links").list()!!.toList())
        assertEquals(OWN_ID, store.wayId(imported))
    }

    @Test
    fun `linking again overwrites only that walk's link`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        store.link(first, OWN_ID, arrival = null)
        store.link(second, "share:aaaaaaaaaa", arrival = null)

        store.link(first, OWN_ID, WayArrival(10.0, 9.0))

        assertEquals(WayLink(OWN_ID, 10.0, 9.0), store.wayLink(first))
        assertEquals(WayLink("share:aaaaaaaaaa"), store.wayLink(second))
    }

    @Test
    fun `a torn temp link file is ignored by readers and by delete`() {
        val walk = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        store.link(walk, OWN_ID, WayArrival(10.0, 9.0))
        val links = File(dir, "links")
        File(links, ".$walk.json.${UUID.randomUUID()}.tmp").writeText("""{"wayId":"share:bbbbbbbb""")
        File(links, ".$other.json.${UUID.randomUUID()}.tmp").writeText("""{"wayId":"$OWN_ID"}""")

        assertEquals(WayLink(OWN_ID, 10.0, 9.0), store.wayLink(walk))
        assertNull("a temp file is not a link", store.wayLink(other))

        store.delete(OWN_ID)

        assertNull(store.wayLink(walk))
        assertEquals("delete leaves temp files alone", 2, links.list()!!.size)
    }

    @Test
    fun `a corrupt committed link reads as absent and harms no other link`() {
        val torn = UUID.randomUUID().toString()
        val good = UUID.randomUUID().toString()
        store.link(good, OWN_ID, arrival = null)
        File(dir, "links").mkdirs()
        File(dir, "links/$torn.json").writeText("""{"wayId":""")

        assertNull(store.wayLink(torn))
        store.link(UUID.randomUUID().toString(), "share:aaaaaaaaaa", arrival = null)
        assertEquals(WayLink(OWN_ID), store.wayLink(good))
    }

    @Test
    fun `no write leaves a temp file behind`() {
        store.save(ownWay())
        store.setReply(OWN_ID, 1, "recordings/a/1.wav")
        store.link(WALK, OWN_ID, arrival = null)
        store.stage(UUID.randomUUID().toString(), ownWay())

        val leftovers = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList<File>(), leftovers)
    }

    @Test
    fun `link and staging refuse a walk uuid that could be a path`() {
        listOf("../../../../../../../../../../../../../x", "a".repeat(35), "a/b".padEnd(36, 'c')).forEach { uuid ->
            assertThrows(IllegalArgumentException::class.java) { store.link(uuid, OWN_ID, arrival = null) }
            assertThrows(IllegalArgumentException::class.java) { store.stage(uuid, ownWay()) }
            assertNull(store.wayLink(uuid))
            assertNull(store.staged(uuid))
        }
        assertFalse(dir.exists())
    }

    // ---- own walks ----

    @Test
    fun `an own-walk Way round-trips and has no media`() {
        val own = ownWay()
        store.save(own)

        assertEquals(own, store.load(OWN_ID))
        assertFalse(store.hasMedia(OWN_ID))
        assertEquals(
            File(dir, OWN_ID).listFiles()!!.filter { it.isFile }.sumOf(::allocatedBytesOf),
            store.diskUsage(OWN_ID),
        )
        assertEquals(setOf("way.json", "accepted.json"), File(dir, OWN_ID).list()!!.toSet())
    }

    // Owner decision 4: iOS's `totalFileAllocatedSize`, so a small file costs its block.
    @Test
    fun `a Way's size is the allocated bytes of its files, a download still gathering left out`() {
        val blocks = WayStore({ dir }, Clock { clockMillis }, syncDirectory = { true }, allocatedBytes = { 4_096L })
        blocks.save(way("share:aaaaaaaaaa"))
        File(dir, "share:aaaaaaaaaa/media/audio/1.m4a").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(10))
        blocks.mediaPartialFile("share:aaaaaaaaaa", "audio/2.m4a")!!.writeBytes(ByteArray(10))

        assertEquals("way.json, accepted.json, and one voice", 3 * 4_096L, blocks.diskUsage("share:aaaaaaaaaa"))
    }

    @Test
    fun `allocated size counts whole blocks, and a file with none its length`() {
        val file = folder.newFile("one-byte").apply { writeBytes(byteArrayOf(1)) }

        val allocated = allocatedBytesOf(file)

        assertTrue(allocated == 1L || (allocated >= 512 && allocated % 512 == 0L))
    }

    @Test
    fun `a gathering file sits in the Way's folder outside media, named for its path`() {
        store.save(way("share:aaaaaaaaaa"))

        val partial = store.mediaPartialFile("share:aaaaaaaaaa", "photos/12.jpg")!!

        assertEquals(File(dir, "share:aaaaaaaaaa"), partial.parentFile)
        assertTrue(partial.name.startsWith(".") && partial.name.endsWith(".tmp"))
        assertNull(store.mediaPartialFile("share:aaaaaaaaaa", "../../x.jpg"))
        partial.writeBytes(byteArrayOf(1))
        assertFalse("never media", store.hasMedia("share:aaaaaaaaaa"))
    }

    // iOS's deliver guard (`WayMediaDownloader.swift:270-296@7c200bf`).
    @Test
    fun `landing a file makes media's folders one at a time, and never a gone Way's folder`() {
        store.save(way("share:aaaaaaaaaa"))
        val partial = store.mediaPartialFile("share:aaaaaaaaaa", "audio/1.m4a")!!.apply { writeText("voice") }

        assertTrue(store.landMedia("share:aaaaaaaaaa", "audio/1.m4a", partial))
        assertEquals("voice", File(dir, "share:aaaaaaaaaa/media/audio/1.m4a").readText())
        assertFalse(partial.exists())

        val late = File(folder.root, "late.tmp").apply { writeText("late") }
        store.delete("share:aaaaaaaaaa")
        assertFalse(store.landMedia("share:aaaaaaaaaa", "audio/2.m4a", late))
        assertFalse(File(dir, "share:aaaaaaaaaa").exists())
        assertFalse(late.exists())
    }

    // A Settings delete and the media worker run on different threads of the UI process.
    @Test
    fun `a delete racing a landing waits for it, then takes the landed file too, leaving no orphan folder`() {
        lateinit var racing: WayStore
        lateinit var deleting: Thread
        var landing = false
        var deleteWaited = false
        racing = WayStore(
            { dir },
            Clock { clockMillis },
            // The landing's fsync runs after its rename, inside it: the delete starts right then.
            syncDirectory = {
                if (landing) {
                    landing = false
                    deleting = Thread { racing.delete("share:aaaaaaaaaa") }.apply { start() }
                    deleting.join(RACE_WAIT_MILLIS)
                    deleteWaited = deleting.isAlive
                }
                true
            },
        )
        racing.save(way("share:aaaaaaaaaa"))
        val partial = racing.mediaPartialFile("share:aaaaaaaaaa", "audio/1.m4a")!!.apply { writeText("voice") }
        landing = true

        assertTrue(racing.landMedia("share:aaaaaaaaaa", "audio/1.m4a", partial))
        deleting.join(JOIN_BUDGET_MILLIS)

        assertTrue("the delete waits for the landing", deleteWaited)
        assertFalse(File(dir, "share:aaaaaaaaaa").exists())
    }

    @Test
    fun `a partial is opened only for a Way that still loads, and the temp sweep leaves it while held`() {
        store.save(way("share:aaaaaaaaaa"))
        val partial = store.mediaPartialFile("share:aaaaaaaaaa", "audio/1.m4a")!!

        assertNotNull(store.holdMediaPartial("share:aaaaaaaaaa", partial) { it.writeText("voi") })
        partial.setLastModified(0L)
        assertEquals("held", 0, store.sweepTempFiles(olderThanMillis = Long.MAX_VALUE))
        store.releaseMediaPartial(partial)
        assertEquals("released", 1, store.sweepTempFiles(olderThanMillis = Long.MAX_VALUE))

        store.delete("share:aaaaaaaaaa")
        assertNull(store.holdMediaPartial("share:aaaaaaaaaa", partial) { error("never opened") })
        assertFalse(File(dir, "share:aaaaaaaaaa").exists())
    }

    @Test
    fun `a partial left empty goes with its hold, and one with bytes stays to be resumed`() {
        store.save(way("share:aaaaaaaaaa"))
        val empty = store.mediaPartialFile("share:aaaaaaaaaa", "audio/1.m4a")!!
        val started = store.mediaPartialFile("share:aaaaaaaaaa", "audio/2.m4a")!!

        store.holdMediaPartial("share:aaaaaaaaaa", empty) { it.createNewFile() }
        store.holdMediaPartial("share:aaaaaaaaaa", started) { it.writeText("voi") }
        store.releaseMediaPartial(empty)
        store.releaseMediaPartial(started)

        assertFalse(empty.exists())
        assertEquals("voi", started.readText())
    }

    @Test
    fun `list includes own-walk Ways and steps over the links and staging folders`() {
        store.link(WALK, OWN_ID, arrival = null)
        store.stage(WALK, ownWay(title = "staged"))
        assertTrue(store.list().isEmpty())

        store.save(ownWay())

        assertEquals(listOf(OWN_ID), store.list().map { it.id })
    }

    // ---- staging (Android) ----

    @Test
    fun `a staged Way is neither listed nor loadable until promoted`() {
        store.stage(WALK, ownWay())

        assertEquals(ownWay(), store.staged(WALK))
        assertNull(store.load(OWN_ID))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `promotion lists the staged Way and removes the staging`() {
        store.stage(WALK, ownWay())

        val promoted = store.promoteStaged(WALK)

        assertEquals(ownWay(), promoted)
        assertEquals(ownWay(), store.load(OWN_ID))
        assertEquals(now, store.acceptedAt(OWN_ID))
        assertNull(store.staged(WALK))
        assertTrue(store.listStaged().isEmpty())
    }

    @Test
    fun `promotion overwrites the listed Way and keeps the first acceptance`() {
        store.save(ownWay(title = "first honoring's build"))
        clockMillis += 86_400_000
        val repeat = UUID.randomUUID().toString()
        store.stage(repeat, ownWay(title = "this honoring's build"))

        store.promoteStaged(repeat)

        assertEquals("this honoring's build", store.load(OWN_ID)?.title)
        assertEquals(now, store.acceptedAt(OWN_ID))
    }

    @Test
    fun `promoting nothing staged changes nothing`() {
        store.save(ownWay(title = "listed"))

        assertNull(store.promoteStaged(WALK))
        assertEquals("listed", store.load(OWN_ID)?.title)
    }

    @Test
    fun `discard removes only that walk's staging`() {
        val other = UUID.randomUUID().toString()
        store.stage(WALK, ownWay())
        store.stage(other, ownWay())
        store.link(WALK, OWN_ID, arrival = null)
        store.save(ownWay())

        store.discardStaged(WALK)

        assertNull(store.staged(WALK))
        assertEquals(listOf(other), store.listStaged().map { it.walkUuid })
        assertEquals(OWN_ID, store.wayId(WALK))
        assertNotNull(store.load(OWN_ID))
    }

    @Test
    fun `listStaged reports when each Way was staged`() {
        store.stage(WALK, ownWay())
        val staged = File(dir, "staging/$WALK/way.json")
        staged.setLastModified(1_700_000_000_000L)
        File(dir, "staging/not-a-uuid").mkdirs()

        assertEquals(listOf(StagedWay(WALK, 1_700_000_000_000L)), store.listStaged())
    }

    // ---- where the store lives ----

    @Test
    fun `the store's root sits under noBackupFilesDir`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val provided = WayStoreModule.provideWayStore(context, Clock.System)

        assertEquals(File(context.noBackupFilesDir, "Ways"), provided.baseDirectory)
    }

    // ---- durability ----

    @Test
    fun `every committed write fsyncs the folder that holds it, so the rename survives a power loss`() {
        val synced = mutableListOf<File>()
        val syncing = WayStore({ dir }, Clock { clockMillis }, syncDirectory = { synced += it; true })

        syncing.link(WALK, OWN_ID, WayArrival(1.0, 2.0))
        syncing.save(way(OWN_ID))

        assertEquals(File(dir, "links"), synced.first())
        assertTrue(File(dir, OWN_ID) in synced)
    }

    @Test
    fun `the platform folder fsync is best effort and never throws`() {
        fsyncDirectoryBestEffort(folder.root)
        fsyncDirectoryBestEffort(File(folder.root, "missing"))
    }

    @Test
    fun `staging folders are listed whether or not their Way finished writing`() {
        store.stage(WALK, way(OWN_ID))
        val half = File(dir, "staging/0e8d6f8a-0000-4000-8000-000000000000").apply { mkdirs() }
        File(half, ".way.json.tmp").writeText("{")

        val folders = store.listStagingFolders().associateBy { it.walkUuid }

        assertTrue(folders.getValue(WALK).complete)
        assertFalse(folders.getValue(half.name).complete)
        assertEquals(listOf(WALK), store.listStaged().map { it.walkUuid })
    }

    @Test
    fun `building the store touches no file`() {
        var resolved = false
        val lazy = WayStore({ resolved = true; dir })

        assertFalse(resolved)
        lazy.list()
        assertTrue(resolved)
    }

    private companion object {
        const val SOURCE_WALK = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val OWN_ID = "walk:$SOURCE_WALK"
        const val WALK = "7b1a2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

        /** How long a delete that should be waiting is given to finish anyway: it never may. */
        const val RACE_WAIT_MILLIS = 300L
        const val JOIN_BUDGET_MILLIS = 30_000L
    }
}
