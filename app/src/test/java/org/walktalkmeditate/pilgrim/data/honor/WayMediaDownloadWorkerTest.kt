// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The media worker against a real HTTP server (shared-walk spec S3 §2–§11):
 * iOS's paths, caps, ceilings, retry, disk full and deleted-Way guard
 * (`WayMediaDownloaderTests.swift@7c200bf`), plus the R6 additions: the
 * per-fetch path re-check, the cross-host redirect refusal, and the early
 * `Content-Length` refusal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayMediaDownloadWorkerTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var store: WayStore
    private val served = Collections.synchronizedList(mutableListOf<String>())
    private var respond: (RecordedRequest) -> MockResponse = { request -> body(bytesFor(request.path.orEmpty())) }
    private var openPartial: (File) -> RandomAccessFile = { file -> RandomAccessFile(file, "rw") }
    private val opened = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                served += request.path.orEmpty()
                return respond(request)
            }
        }
        server.start()
        store = WayStore({ File(folder.root, "Ways") }, syncDirectory = { true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun worker(): WayMediaDownloadWorker {
        val base = server.url("/")
        val transport = WayMediaTransport(
            baseUrl = base,
            client = WayMediaTransport.mediaHttpClient(base).newBuilder()
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build(),
            openPartial = { file ->
                opened += file.name
                openPartial(file)
            },
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                WayMediaDownloadWorker(appContext, workerParameters, store, transport)
        }
        return TestListenableWorkerBuilder<WayMediaDownloadWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(WayMediaDownloadWorker.KEY_WAY_ID to WAY_ID))
            .build()
    }

    private fun gather(): WayMediaReport? {
        val result = runBlocking { worker().doWork() }
        assertTrue("the worker never asks WorkManager to retry", result is ListenableWorker.Result.Success)
        return WayMediaReport.from((result as ListenableWorker.Result.Success).outputData)
    }

    private fun saveWay(vararg paths: String): Way {
        val moments = paths.mapIndexed { i, path ->
            val kind = if (path.startsWith("photos/")) {
                WayMomentKind.Photo(WayMedia.File(path))
            } else {
                WayMomentKind.Voice(endFrac = 0.1, duration = 3.0, kind = VoiceKind.SPOKEN, media = WayMedia.File(path))
            }
            WayMoment(id = "m-${i + 1}", frac = (i + 1) / 100.0, at = null, kind = kind)
        }
        val way = Way(
            id = WAY_ID,
            source = WaySource.Share(id = SHARE_ID, pageUrl = "https://walk.pilgrimapp.org/$SHARE_ID"),
            title = "Rúa do Franco → Obradoiro",
            departedAt = Instant.parse("2026-08-01T07:00:00Z"),
            tzIdentifier = null,
            expires = Instant.parse("2099-01-01T00:00:00Z"),
            route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
            totalDistanceMeters = 111.0,
            theirActiveSeconds = 60.0,
            moments = moments,
            weather = null,
        )
        store.save(way)
        return way
    }

    private fun landed(relative: String): File = File(folder.root, "Ways/$WAY_ID/media/$relative")

    private fun partial(relative: String): File = store.mediaPartialFile(WAY_ID, relative)!!

    private fun bytesFor(path: String): ByteArray = path.toByteArray()

    private fun body(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))

    // iOS `mediaFiles(for:)` and `remoteURL`: every file once, in moment order, from the walk host's path.
    @Test
    fun `a gather lands every file the Way names, once each, in moment order`() {
        saveWay("audio/1.m4a", "audio/2.m4a", "photos/1.jpg", "audio/1.m4a")

        val report = gather()!!

        assertEquals(
            listOf("/$SHARE_ID/audio/1.m4a", "/$SHARE_ID/audio/2.m4a", "/$SHARE_ID/photos/1.jpg"),
            served,
        )
        listOf("audio/1.m4a", "audio/2.m4a", "photos/1.jpg").forEach {
            assertArrayEquals(bytesFor("/$SHARE_ID/$it"), landed(it).readBytes())
        }
        assertEquals(WayMediaReport(accepted = 3, unfinished = 0, failures = emptyList(), diskFull = false), report)
        assertEquals(1.0, report.progress, 0.0)
    }

    @Test
    fun `files already here are counted as done and never fetched again`() {
        saveWay("audio/1.m4a", "audio/2.m4a")
        landed("audio/1.m4a").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))

        val report = gather()!!

        assertEquals(listOf("/$SHARE_ID/audio/2.m4a"), served)
        assertEquals(2, report.accepted)
    }

    // S3 §2, an R6 addition: the shape is checked before every fetch, not only on a relaunch rebuild.
    @Test
    fun `paths with an escaped slash, a double-escaped one, or a climb are refused before any write`() {
        val hostile = listOf("audio%2F..%2F..%2Fx.m4a", "audio%252F..%252Fx.m4a", "../../outside.m4a", "audio/../1.m4a")
        saveWay(*hostile.toTypedArray())

        val report = gather()!!

        assertEquals("no request leaves", 0, server.requestCount)
        assertTrue(opened.isEmpty())
        assertEquals(hostile, report.failures)
        assertEquals(setOf("way.json", "accepted.json"), File(folder.root, "Ways/$WAY_ID").list()!!.toSet())
        assertEquals(setOf("Ways"), folder.root.list()!!.toSet())
    }

    // iOS `testDownloadRefusesFilesBeyondThePerKindCeilings`.
    @Test
    fun `a thirteenth audio file is refused and never fetched`() {
        saveWay(*(1..13).map { "audio/$it.m4a" }.toTypedArray())

        val report = gather()!!

        assertEquals(12, server.requestCount)
        assertFalse(served.contains("/$SHARE_ID/audio/13.m4a"))
        assertEquals(listOf("audio/13.m4a"), report.failures)
        assertTrue((1..12).all { landed("audio/$it.m4a").isFile })
        assertEquals(12, report.accepted)
    }

    @Test
    fun `a twenty-first photo is refused while the audio files still go`() {
        saveWay(*((1..21).map { "photos/$it.jpg" } + "audio/1.m4a").toTypedArray())

        val report = gather()!!

        assertEquals(listOf("photos/21.jpg"), report.failures)
        assertTrue(landed("audio/1.m4a").isFile)
    }

    // S3 §3: strictly greater, counting the partial already on the phone.
    @Test
    fun `a resume that crosses the byte cap stops, with no retry`() {
        saveWay("photos/1.jpg")
        val have = WayMediaRules.PHOTO_BYTE_CAP - 2
        partial("photos/1.jpg").writeBytes(ByteArray(have.toInt()))
        respond = {
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $have-${have + 4}/${have + 5}")
                .setChunkedBody(Buffer().write(ByteArray(5) { 7 }), 1)
        }

        val report = gather()!!

        assertEquals("bytes=$have-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertEquals(1, server.requestCount)
        assertFalse(landed("photos/1.jpg").exists())
        assertFalse(partial("photos/1.jpg").exists())
        assertEquals(listOf("photos/1.jpg"), report.failures)
    }

    @Test
    fun `a resume that ends exactly at the cap lands`() {
        saveWay("photos/1.jpg")
        val have = WayMediaRules.PHOTO_BYTE_CAP - 3
        partial("photos/1.jpg").writeBytes(ByteArray(have.toInt()))
        respond = {
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $have-${have + 2}/${have + 3}")
                .setBody(Buffer().write(ByteArray(3) { 9 }))
        }

        val report = gather()!!

        assertEquals(WayMediaRules.PHOTO_BYTE_CAP, landed("photos/1.jpg").length())
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `the cap is enforced while the bytes arrive, with no length declared`() {
        saveWay("photos/1.jpg")
        respond = {
            MockResponse().setChunkedBody(Buffer().write(ByteArray(WayMediaRules.PHOTO_BYTE_CAP.toInt() + 1)), 64 * 1024)
        }

        val report = gather()!!

        assertEquals("a capped file isn't retried", 1, server.requestCount)
        assertFalse(landed("photos/1.jpg").exists())
        assertFalse(partial("photos/1.jpg").exists())
        assertEquals(listOf("photos/1.jpg"), report.failures)
    }

    @Test
    fun `a resumed partial reply appends to what is here`() {
        saveWay("audio/1.m4a")
        partial("audio/1.m4a").writeBytes("abc".toByteArray())
        respond = {
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def")
        }

        gather()

        assertEquals("bytes=3-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertEquals("abcdef", landed("audio/1.m4a").readText())
        assertFalse(partial("audio/1.m4a").exists())
    }

    @Test
    fun `a Range request answered with the whole file restarts it`() {
        saveWay("audio/1.m4a")
        partial("audio/1.m4a").writeBytes("junk".toByteArray())
        respond = { MockResponse().setBody("the whole voice") }

        gather()

        assertEquals("bytes=4-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertEquals("the whole voice", landed("audio/1.m4a").readText())
    }

    // The resume reads its length from the handle it appends through: an unlink meanwhile can't splice.
    @Test
    fun `a partial unlinked while its resume is in flight never lands as its tail alone`() {
        saveWay("audio/1.m4a")
        partial("audio/1.m4a").writeBytes("abc".toByteArray())
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        respond = { request ->
            ranges += request.getHeader("Range")
            if (request.getHeader("Range") != null) {
                // The sweep, or a media delete, takes the partial's name while the request is out.
                partial("audio/1.m4a").delete()
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def")
            } else {
                MockResponse().setBody("abcdef")
            }
        }

        val report = gather()!!

        assertEquals("the voice lands whole, from its one retry", "abcdef", landed("audio/1.m4a").readText())
        assertEquals(listOf("bytes=3-", null), ranges)
        assertTrue(report.failures.isEmpty())
    }

    // The launch's 24 h temp sweep runs in the process that runs the worker.
    @Test
    fun `the temp sweep leaves a partial a fetch holds, however old`() {
        saveWay("audio/1.m4a")
        partial("audio/1.m4a").apply {
            writeBytes("abc".toByteArray())
            setLastModified(0L)
        }
        var sweptMidFetch = -1
        respond = {
            sweptMidFetch = store.sweepTempFiles(olderThanMillis = Long.MAX_VALUE)
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def")
        }

        gather()

        assertEquals(0, sweptMidFetch)
        assertEquals("abcdef", landed("audio/1.m4a").readText())
        assertEquals(1, server.requestCount)
    }

    // S3 §2, an R6 addition: iOS follows a redirect to any host.
    @Test
    fun `a redirect to another host is refused before anything connects to it`() {
        ConnectionCountingServer().use { other ->
            saveWay("audio/1.m4a")
            respond = { MockResponse().setResponseCode(302).setHeader("Location", other.url("/elsewhere.m4a")) }

            val report = gather()!!

            assertEquals("no connection, let alone a request", 0, other.connectionsSoFar())
            assertEquals("refused, then its one retry", 2, server.requestCount)
            assertEquals(listOf("audio/1.m4a"), report.failures)
            assertFalse(landed("audio/1.m4a").exists())
        }
    }

    @Test
    fun `a redirect on the walk host is followed, its Range kept`() {
        saveWay("audio/1.m4a")
        partial("audio/1.m4a").writeBytes("abc".toByteArray())
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        respond = { request ->
            ranges += request.getHeader("Range")
            if (request.path!!.startsWith("/moved/")) {
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def")
            } else {
                MockResponse().setResponseCode(302).setHeader("Location", "/moved/1.m4a")
            }
        }

        gather()

        assertEquals("abcdef", landed("audio/1.m4a").readText())
        assertEquals(listOf("bytes=3-", "bytes=3-"), ranges)
    }

    // S3 §3, an R6 addition: the same bound as iOS's streaming cut, reached before any byte is read.
    @Test
    fun `a declared length over the cap is refused before reading`() {
        saveWay("photos/1.jpg")
        var written = 0L
        openPartial = { file ->
            object : RandomAccessFile(file, "rw") {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    written += len
                    super.write(b, off, len)
                }
            }
        }
        respond = { body(ByteArray(WayMediaRules.PHOTO_BYTE_CAP.toInt() + 1)) }

        val report = gather()!!

        assertEquals("no byte is written", 0L, written)
        assertFalse(partial("photos/1.jpg").exists())
        assertEquals("and it isn't retried", 1, server.requestCount)
        assertEquals(listOf("photos/1.jpg"), report.failures)
    }

    @Test
    fun `a failed file leaves no empty partial behind`() {
        saveWay("audio/1.m4a")
        respond = { MockResponse().setResponseCode(404) }

        gather()

        assertFalse(partial("audio/1.m4a").exists())
    }

    // S3 §7: one retry per file, immediate, then the failure.
    @Test
    fun `a failed file gets one immediate retry, which can land it`() {
        saveWay("audio/1.m4a")
        var calls = 0
        respond = { if (calls++ == 0) MockResponse().setResponseCode(500) else body("second time".toByteArray()) }

        val report = gather()!!

        assertEquals(2, server.requestCount)
        assertEquals("second time", landed("audio/1.m4a").readText())
        assertTrue(report.failures.isEmpty())
    }

    @Test
    fun `a file that fails its retry too is a failure, and the next file still goes`() {
        saveWay("audio/1.m4a", "audio/2.m4a")
        respond = { request ->
            if (request.path!!.endsWith("/audio/1.m4a")) MockResponse().setResponseCode(404) else body(bytesFor(request.path!!))
        }

        val report = gather()!!

        assertEquals(listOf("/$SHARE_ID/audio/1.m4a", "/$SHARE_ID/audio/1.m4a", "/$SHARE_ID/audio/2.m4a"), served)
        assertEquals(listOf("audio/1.m4a"), report.failures)
        assertTrue(landed("audio/2.m4a").isFile)
        assertEquals("failures count as done", 1.0, report.progress, 0.0)
    }

    @Test
    fun `any 2xx is accepted`() {
        saveWay("audio/1.m4a")
        respond = { MockResponse().setResponseCode(203).setBody("non-authoritative") }

        gather()

        assertEquals("non-authoritative", landed("audio/1.m4a").readText())
    }

    // S3 §8 and spec correction 12.
    @Test
    fun `disk full at file 3 is final for it alone, and the files that landed stay`() {
        saveWay("audio/1.m4a", "audio/2.m4a", "audio/3.m4a", "audio/4.m4a")
        openPartial = { file ->
            if (file.name.contains("audio-3")) {
                object : RandomAccessFile(file, "rw") {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        throw IOException("write failed: ENOSPC (No space left on device)")
                    }
                }
            } else {
                RandomAccessFile(file, "rw")
            }
        }

        val report = gather()!!

        assertTrue(report.diskFull)
        assertEquals(listOf("audio/3.m4a"), report.failures)
        assertTrue(landed("audio/1.m4a").isFile && landed("audio/2.m4a").isFile && landed("audio/4.m4a").isFile)
        assertFalse(landed("audio/3.m4a").exists())
        assertFalse("the interrupted file's bytes go", partial("audio/3.m4a").exists())
        assertEquals("disk full is never retried", 1, served.count { it.endsWith("/audio/3.m4a") })
    }

    // iOS `testDeliveryForADeletedWayNeitherLandsNorRecreatesTheFolder`.
    @Test
    fun `a Way deleted between its fetch and the rename gets nothing, and its folder isn't made again`() {
        saveWay("audio/1.m4a", "audio/2.m4a")
        openPartial = { file ->
            object : RandomAccessFile(file, "rw") {
                override fun close() {
                    super.close()
                    store.delete(WAY_ID)
                }
            }
        }

        val report = gather()

        assertNull("nothing is recorded against a Way that is gone", report)
        assertFalse(File(folder.root, "Ways/$WAY_ID").exists())
        assertEquals("the gather stops with the Way", 1, server.requestCount)
    }

    // A Settings delete runs on another thread than the worker: it waits for the partial's opening.
    @Test
    fun `a delete racing a partial's opening waits for it, then leaves no folder behind`() {
        saveWay("audio/1.m4a")
        lateinit var deleting: Thread
        var deleteWaited = false
        openPartial = { file ->
            deleting = Thread { store.delete(WAY_ID) }.apply { start() }
            deleting.join(RACE_WAIT_MILLIS)
            deleteWaited = deleting.isAlive
            RandomAccessFile(file, "rw")
        }

        gather()
        deleting.join(JOIN_BUDGET_MILLIS)

        assertTrue("the delete waits while the partial opens", deleteWaited)
        assertFalse(deleting.isAlive)
        assertFalse("no partial, no media, no folder", File(folder.root, "Ways/$WAY_ID").exists())
    }

    @Test
    fun `a Way swept whole before its gather runs fetches nothing`() {
        saveWay("audio/1.m4a")
        store.delete(WAY_ID)

        assertNull(gather())
        assertEquals(0, server.requestCount)
        assertFalse(File(folder.root, "Ways/$WAY_ID").exists())
    }

    @Test
    fun `progress reports count what is left over what was accepted`() {
        val report = WayMediaReport(accepted = 4, unfinished = 1, failures = listOf("audio/2.m4a"), diskFull = false)

        assertEquals(0.75, report.progress, 1e-9)
        assertEquals(report, WayMediaReport.from(report.toData()))
        assertNull(WayMediaReport.from(workDataOf()))
    }

    @Test
    fun `a full disk is told from its errno name, its text, or a cause that carries either`() {
        assertTrue(WayMediaDownloadWorker.isDiskFull(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(WayMediaDownloadWorker.isDiskFull(IOException("rename", IOException("No space left on device"))))
        assertFalse(WayMediaDownloadWorker.isDiskFull(IOException("Connection reset")))
    }

    @Test
    fun `a full database is a full disk too, as WorkManager's store reports one`() {
        assertTrue(WayMediaDownloadWorker.isDiskFull(IllegalStateException("enqueue", SQLiteFullException("database or disk is full"))))
        assertFalse(WayMediaDownloadWorker.isDiskFull(IllegalStateException("enqueue", SQLiteException("disk I/O error"))))
    }

    private companion object {
        const val SHARE_ID = "aaaaaaaaaa"
        const val WAY_ID = "share:$SHARE_ID"

        /** How long a delete that should be waiting is given to finish anyway: it never may. */
        const val RACE_WAIT_MILLIS = 300L
        const val JOIN_BUDGET_MILLIS = 30_000L
    }
}
