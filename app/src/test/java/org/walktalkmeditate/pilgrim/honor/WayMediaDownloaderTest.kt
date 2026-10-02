// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.data.honor.FakeWayMediaDownloadScheduler
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayMediaReport
import org.walktalkmeditate.pilgrim.data.honor.WayMediaWork
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The downloader's sets (iOS `WayMediaDownloaderTests.swift@7c200bf`, shared-
 * walk spec S3 §6): what `download` decides at once, what `cancel` clears,
 * the ceilings, `retry` while still gathering, and the work's reports
 * flowing into the overview's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WayMediaDownloaderTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val scheduler = FakeWayMediaDownloadScheduler()
    private lateinit var store: WayStore

    @Before
    fun setUp() {
        store = WayStore({ File(folder.root, "Ways") }, syncDirectory = { true })
    }

    private fun TestScope.downloader() = WayMediaDownloader(store, scheduler, backgroundScope, dispatcher)

    private fun voice(n: Int) = WayMoment(
        id = "voice-$n",
        frac = 0.1,
        at = null,
        kind = WayMomentKind.Voice(0.2, 1.0, VoiceKind.SPOKEN, WayMedia.File("audio/$n.m4a")),
    )

    private fun photo(n: Int) = WayMoment(id = "photo-$n", frac = 0.5, at = null, kind = WayMomentKind.Photo(WayMedia.File("photos/$n.jpg")))

    private fun way(id: String, moments: List<WayMoment>) = Way(
        id = id,
        source = WaySource.Share(id = id.removePrefix("share:"), pageUrl = "https://walk.pilgrimapp.org/x"),
        title = "t",
        departedAt = Instant.EPOCH,
        tzIdentifier = null,
        expires = null,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = moments,
        weather = null,
    ).also(store::save)

    private fun onDisk(wayId: String, relative: String) {
        File(folder.root, "Ways/$wayId/media/$relative").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
    }

    // iOS `testDownloadWithEverythingAlreadyOnDiskCompletesImmediately`.
    @Test
    fun `a Way whose every file is here completes at once and enqueues nothing`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:aaaaaaaaaa", listOf(voice(1), photo(1)))
        onDisk(way.id, "audio/1.m4a")
        onDisk(way.id, "photos/1.jpg")

        downloader.download(way)

        val sets = downloader.gathers.value
        assertEquals(1.0, sets.progress[way.id])
        assertFalse(way.id in sets.active)
        assertNull(sets.failures[way.id])
        assertTrue(scheduler.gathers.isEmpty())
        assertEquals(HonorImportState.Ready, sets.state(way.id))
    }

    // iOS `testCancelClearsEveryPerWayEntry`.
    @Test
    fun `cancel clears every entry the Way had, at once, and stops its work`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:bbbbbbbbbb", listOf(voice(1)))
        downloader.download(way)
        assertTrue(way.id in downloader.gathers.value.active)

        downloader.cancel(way.id)

        val sets = downloader.gathers.value
        assertFalse(way.id in sets.active)
        assertNull(sets.progress[way.id])
        assertNull(sets.failures[way.id])
        assertEquals(listOf(way.id), scheduler.cancels)
    }

    // iOS `testDownloadRefusesFilesBeyondThePerKindCeilings`.
    @Test
    fun `everything past 12 audio files and 20 photos is refused before anything is enqueued`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:eeeeeeeeee", (1..14).map(::voice) + (1..23).map(::photo))

        downloader.download(way)

        val refused = downloader.gathers.value.failures[way.id].orEmpty()
        assertEquals(5, refused.size)
        assertTrue("audio/14.m4a" in refused)
        assertTrue("photos/23.jpg" in refused)
        assertFalse("audio/1.m4a" in refused)
        assertTrue("the files inside the ceilings still go", way.id in downloader.gathers.value.active)
    }

    // iOS `testRetryRestartsEvenWhileTheWayIsStillActive`.
    @Test
    fun `retry restarts the gather even while the Way is still gathering`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:ffffffffff", listOf(voice(1)))
        downloader.download(way)

        downloader.retry(way)

        assertTrue(way.id in downloader.gathers.value.active)
        assertEquals(listOf(false, true), scheduler.gathers.map { it.replace })
        assertEquals(listOf(way.id), scheduler.cancels)
    }

    // S4 §8.4: "the line goes back to gathering … and Begin disables again", with no frame of an enabled Begin.
    @Test
    fun `try again holds the Way's line through its restart, never ready in between`() = runTest(dispatcher) {
        val seen = mutableListOf<HonorImportState>()
        lateinit var downloader: WayMediaDownloader
        val noting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                seen += downloader.gathers.value.state("share:ffffffffff")
                block.run()
            }
        }
        downloader = WayMediaDownloader(store, scheduler, backgroundScope, noting)
        val way = way("share:ffffffffff", listOf(voice(1), voice(2)))
        downloader.download(way)
        scheduler.report(way.id, WayMediaWork.State.SUCCEEDED, WayMediaReport(2, 0, listOf("audio/2.m4a"), diskFull = false))
        scheduler.onCancel = { seen += downloader.gathers.value.state(it) }
        seen.clear()

        downloader.retry(way)

        val missing = HonorImportState.MediaMissing(listOf("audio/2.m4a"))
        assertEquals("while the disk is read, then as the old work is stopped", listOf(missing, missing), seen)
        assertEquals(HonorImportState.Gathering(0.0), downloader.gathers.value.state(way.id))
    }

    @Test
    fun `a finished round stops following its work`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        downloader.download(way)
        val work = scheduler.work(way.id)
        assertEquals(1, work.subscriptionCount.value)

        scheduler.report(way.id, WayMediaWork.State.SUCCEEDED, WayMediaReport(1, 0, emptyList(), diskFull = false))

        assertEquals(0, work.subscriptionCount.value)
    }

    // A work KEEP kept, then pruned or gone: WorkManager answers null, and nothing more comes.
    @Test
    fun `a work WorkManager no longer has ends the round as missing, never gathering for good`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        downloader.download(way)

        scheduler.work(way.id).value = null

        assertFalse(way.id in downloader.gathers.value.active)
        assertEquals(HonorImportState.MediaMissing(listOf("audio/1.m4a")), downloader.gathers.value.state(way.id))
    }

    // iOS's download can't fail; WorkManager's enqueue can, and must not take the app down.
    @Test
    fun `a gather WorkManager can't take on a full phone shows the disk-full line`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        scheduler.failGather = IllegalStateException("enqueue failed", IOException("write failed: ENOSPC (No space left on device)"))

        downloader.download(way)

        assertEquals(HonorImportState.Failed(WayError.DISK_FULL), downloader.gathers.value.state(way.id))
        assertFalse("Begin is enabled", way.id in downloader.gathers.value.active)
    }

    @Test
    fun `a gather WorkManager can't take for any other reason reads some voices didn't arrive`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        scheduler.failGather = IllegalStateException("the work database is broken")

        downloader.download(way)

        assertEquals(HonorImportState.MediaMissing(listOf("audio/1.m4a")), downloader.gathers.value.state(way.id))

        scheduler.failGather = null
        downloader.retry(way)
        assertEquals("and try again can still gather", HonorImportState.Gathering(0.0), downloader.gathers.value.state(way.id))
    }

    @Test
    fun `a second download while the Way gathers enqueues nothing`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:ffffffffff", listOf(voice(1)))

        downloader.download(way)
        downloader.download(way)

        assertEquals(1, scheduler.gathers.size)
    }

    @Test
    fun `progress is seeded from the files already here, and moves with the work's reports`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:cccccccccc", listOf(voice(1), voice(2), voice(3), voice(4)))
        onDisk(way.id, "audio/1.m4a")

        downloader.download(way)
        assertEquals(HonorImportState.Gathering(0.25), downloader.gathers.value.state(way.id))

        scheduler.report(way.id, WayMediaWork.State.RUNNING, WayMediaReport(4, unfinished = 1, failures = listOf("audio/2.m4a"), diskFull = false))
        assertEquals(HonorImportState.Gathering(0.75), downloader.gathers.value.state(way.id))

        scheduler.report(way.id, WayMediaWork.State.SUCCEEDED, WayMediaReport(4, unfinished = 0, failures = listOf("audio/2.m4a"), diskFull = false))
        assertEquals(HonorImportState.MediaMissing(listOf("audio/2.m4a")), downloader.gathers.value.state(way.id))
    }

    @Test
    fun `disk full wins the moment it happens, while the other files still go`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1), voice(2), voice(3)))
        downloader.download(way)

        scheduler.report(way.id, WayMediaWork.State.RUNNING, WayMediaReport(3, unfinished = 2, failures = listOf("audio/1.m4a"), diskFull = true))

        assertTrue(way.id in downloader.gathers.value.active)
        assertEquals(HonorImportState.Failed(WayError.DISK_FULL), downloader.gathers.value.state(way.id))
    }

    @Test
    fun `a gather that lands everything ends ready`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        downloader.download(way)

        scheduler.report(way.id, WayMediaWork.State.SUCCEEDED, WayMediaReport(1, unfinished = 0, failures = emptyList(), diskFull = false))

        assertEquals(HonorImportState.Ready, downloader.gathers.value.state(way.id))
        assertFalse(way.id in downloader.gathers.value.active)
    }

    @Test
    fun `a work that fails outright leaves what it was gathering missing`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        downloader.download(way)

        scheduler.report(way.id, WayMediaWork.State.FAILED, report = null)

        assertEquals(HonorImportState.MediaMissing(listOf("audio/1.m4a")), downloader.gathers.value.state(way.id))
    }

    @Test
    fun `a cancelled round's late reports change nothing`() = runTest(dispatcher) {
        val downloader = downloader()
        val way = way("share:dddddddddd", listOf(voice(1)))
        downloader.download(way)
        val first = scheduler.work(way.id)
        downloader.cancel(way.id)

        first.value = WayMediaWork(WayMediaWork.State.SUCCEEDED, WayMediaReport(1, 0, listOf("audio/1.m4a"), diskFull = true))

        assertEquals(HonorImportState.Ready, downloader.gathers.value.state(way.id))
    }

    @Test
    fun `an own walk's Way downloads nothing`() = runTest(dispatcher) {
        val downloader = downloader()
        val own = way("walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50", listOf(voice(1)))
            .copy(source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"))

        downloader.download(own)

        assertTrue(scheduler.gathers.isEmpty())
        assertEquals(WayGathers(), downloader.gathers.value)
    }
}
