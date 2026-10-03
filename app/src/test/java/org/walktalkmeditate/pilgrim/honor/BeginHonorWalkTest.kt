// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.walk.BellTrigger
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/** The walk screen's Start on an honor walk: the minted uuid, the staged own-walk or stage Way, and the start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BeginHonorWalkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mintedUuid = "11111111-2222-4333-8444-555555555555"
    private val sourceUuid = HonorHarness.SOURCE_UUID
    private val settings = HonorSettings(voicesEnabled = true, softTapEnabled = false)
    private var sourceId = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        h = HonorHarness(folder.root)
        sourceId = h.db.walkDao().insert(
            Walk(uuid = sourceUuid, startTimestamp = 1_000_000L, endTimestamp = 1_600_000L, intention = "the long way"),
        )
        (0..10).forEach { i ->
            h.db.routeDataSampleDao().insert(
                RouteDataSample(walkId = sourceId, timestamp = 1_000_000L + i * 60_000L, latitude = 0.0, longitude = i * 0.001),
            )
        }
        val path = "recordings/$sourceUuid/r1.wav"
        h.db.voiceRecordingDao().insert(
            VoiceRecording(
                walkId = sourceId,
                startTimestamp = 1_060_000L,
                endTimestamp = 1_090_000L,
                durationMillis = 30_000L,
                fileRelativePath = path,
                transcription = "the bridge where we stopped.",
            ),
        )
        File(context.filesDir, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(32) { 1 })
        }
    }

    @After
    fun tearDown() {
        h.close()
        File(context.filesDir, "recordings").deleteRecursively()
    }

    private fun begin(
        controller: WalkController,
        store: WayStore = h.store,
        honorEnabled: Boolean = true,
        begins: HonorBeginsInFlight = HonorBeginsInFlight(),
        awaitSessionRow: suspend (Long) -> Unit = {},
    ) = BeginHonorWalk(
        repository = h.repository,
        wayStore = store,
        walkController = controller,
        recordingFiles = VoiceRecordingFileSystem(context),
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        ioDispatcher = Dispatchers.IO,
        mintWalkUuid = { mintedUuid },
        zone = { ZoneId.of("UTC") },
        locale = { Locale.US },
        begins = begins,
        awaitSessionRow = awaitSessionRow,
    )

    private fun storedRequest() =
        BeginHonorWalk.Request(way = HonorWayChoice.Stored(SHARED_ID), intention = "for her", settings = settings)

    private val sharedWay = HonorHarness.way(moments = listOf(HonorHarness.waypoint(1, 0.003)), title = "Rúa do Franco → Obradoiro")
        .copy(
            id = SHARED_ID,
            source = WaySource.Share(id = "Qoi4YmPHLN", pageUrl = "https://walk.pilgrimapp.org/Qoi4YmPHLN"),
        )

    private fun request(sourceWalkId: Long = sourceId) =
        BeginHonorWalk.Request(way = HonorWayChoice.OwnWalk(sourceWalkId), intention = "for her", settings = settings)

    @Test
    fun `Start stages the own-walk Way under the minted uuid and starts the walk with it`() = runBlocking {
        val controller = RecordingController()

        val result = begin(controller)(request())

        assertTrue(result is BeginHonorWalk.Result.Started)
        val sent = controller.requests.single()
        assertEquals(WalkMode.Honor, sent.mode)
        assertEquals(mintedUuid, sent.walkUuid)
        assertEquals("walk:$sourceUuid", sent.honor!!.wayId)
        assertEquals(settings, sent.honor!!.settings)
        assertEquals("for her", sent.intention)
        val staged = h.store.staged(mintedUuid)!!
        assertEquals("walk:$sourceUuid", staged.id)
        assertEquals("the long way", staged.title)
        assertEquals(1, staged.moments.count { it.kind is WayMomentKind.Voice })
        assertNull("staged, not listed", h.store.load(staged.id))
    }

    @Test
    fun `through the tracker's controller the uuid keys the walk row, the staging, the link, and the marker`() = runBlocking {
        val result = begin(h.controller)(request()) as BeginHonorWalk.Result.Started

        assertEquals(mintedUuid, result.walk.uuid)
        assertEquals(mintedUuid, h.repository.getWalk(result.walk.id)!!.uuid)
        assertEquals(1, h.repository.eventsFor(result.walk.id).count { it.eventType == WalkEventType.HONOR_MODE })
        assertEquals("walk:$sourceUuid", h.db.honorDao().getSession(result.walk.id)!!.wayId)

        h.controller.finishWalk()

        assertEquals("walk:$sourceUuid", h.store.wayLink(mintedUuid)!!.wayId)
        assertEquals(HonorFinishKind.CLEAN, h.db.honorDao().getMarker(mintedUuid)!!.finishKind)
        assertNotNull(h.store.load("walk:$sourceUuid"))
    }

    // Shared-walk spec S4 §8.6: a shared Way's Begin goes through the same Start.
    @Test
    fun `a shared Way starts from the store, staged nowhere, and its walk links to it at the finish`() = runBlocking {
        h.store.save(sharedWay)
        val acceptedAt = h.store.acceptedAt(SHARED_ID)

        val result = begin(h.controller)(storedRequest()) as BeginHonorWalk.Result.Started

        assertEquals(mintedUuid, result.walk.uuid)
        assertNull("a share is listed, never staged", h.store.staged(mintedUuid))
        val session = h.db.honorDao().getSession(result.walk.id)!!
        assertEquals(SHARED_ID to HonorSourceKind.SHARE, session.wayId to session.sourceKind)
        assertEquals("for her", h.repository.getWalk(result.walk.id)!!.intention)

        h.controller.finishWalk()

        assertEquals(SHARED_ID, h.store.wayLink(mintedUuid)!!.wayId)
        assertEquals(HonorFinishKind.CLEAN, h.db.honorDao().getMarker(mintedUuid)!!.finishKind)
        assertEquals("still listed, its acceptance kept", acceptedAt, h.store.acceptedAt(SHARED_ID))
    }

    // Shared-walk spec correction 9: the expiry sweep leaves a Begin's Way whole until its session row exists.
    @Test
    fun `a shared Way is held from Start until its walk exists, and let go after`() = runBlocking {
        h.store.save(sharedWay)
        val begins = HonorBeginsInFlight()
        val controller = RecordingController(onStart = { heldDuringStart = begins.wayIds() })

        begin(controller, begins = begins)(storedRequest())

        assertEquals(setOf(SHARED_ID), heldDuringStart)
        assertTrue(begins.wayIds().isEmpty())
    }

    // `:tracker` writes the session row after the walk row the start waits for, in a transaction of its own.
    @Test
    fun `a shared Way stays held past its walk's start, until the walk's session row exists`() = runBlocking {
        h.store.save(sharedWay)
        val begins = HonorBeginsInFlight()
        val awaiting = CompletableDeferred<Long>()
        val sessionRow = CompletableDeferred<Unit>()
        val begin = begin(
            RecordingController(),
            begins = begins,
            awaitSessionRow = { walkId ->
                awaiting.complete(walkId)
                sessionRow.await()
            },
        )

        val result = async(Dispatchers.Default) { begin(storedRequest()) }
        assertEquals("the walk has started", 77L, withTimeout(WAIT_BUDGET_MILLIS) { awaiting.await() })
        assertEquals(setOf(SHARED_ID), begins.wayIds())
        sessionRow.complete(Unit)

        assertTrue(withTimeout(WAIT_BUDGET_MILLIS) { result.await() } is BeginHonorWalk.Result.Started)
        assertTrue(begins.wayIds().isEmpty())
    }

    @Test
    fun `through the tracker's controller the session row is there once the start returns, so the hold ends`() = runBlocking {
        h.store.save(sharedWay)
        val begins = HonorBeginsInFlight()
        val begin = begin(
            h.controller,
            begins = begins,
            awaitSessionRow = { walkId -> h.db.honorDao().observeSession(walkId).first { it != null } },
        )

        val result = withTimeout(WAIT_BUDGET_MILLIS) { begin(storedRequest()) } as BeginHonorWalk.Result.Started

        assertNotNull(h.db.honorDao().getSession(result.walk.id))
        assertTrue(begins.wayIds().isEmpty())
    }

    @Test
    fun `a refused start lets its Way go too`() = runBlocking {
        h.store.save(sharedWay)
        val begins = HonorBeginsInFlight()

        runCatching { begin(RecordingController(refuse = true), begins = begins)(storedRequest()) }

        assertTrue(begins.wayIds().isEmpty())
    }

    private var heldDuringStart: Set<String>? = null

    @Test
    fun `a shared Way gone from the store refuses the start as gone`() = runBlocking {
        val controller = RecordingController()

        assertEquals(
            BeginHonorWalk.Result.Refused(BeginHonorWalk.Refusal.SOURCE_MISSING),
            begin(controller)(storedRequest()),
        )
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `a shared Way's start sends its store id and the frozen settings`() = runBlocking {
        h.store.save(sharedWay)
        val controller = RecordingController()

        begin(controller)(storedRequest())

        val sent = controller.requests.single()
        assertEquals(WalkMode.Honor to mintedUuid, sent.mode to sent.walkUuid)
        assertEquals(SHARED_ID, sent.honor!!.wayId)
        assertEquals(settings, sent.honor!!.settings)
    }

    @Test
    fun `with the release flag off Begin refuses and touches nothing`() = runBlocking {
        val controller = RecordingController()

        val result = begin(controller, honorEnabled = false)(request())

        assertEquals(BeginHonorWalk.Result.Refused(BeginHonorWalk.Refusal.DISABLED), result)
        assertTrue(controller.requests.isEmpty())
        assertNull(h.store.staged(mintedUuid))
    }

    @Test
    fun `a source walk that is gone, or too short to follow, refuses before staging`() = runBlocking {
        val controller = RecordingController()
        val stub = h.db.walkDao().insert(Walk(uuid = "22222222-2222-4222-8222-222222222222", startTimestamp = 5L, endTimestamp = 9L))

        assertEquals(
            BeginHonorWalk.Result.Refused(BeginHonorWalk.Refusal.SOURCE_MISSING),
            begin(controller)(request(sourceWalkId = 999L)),
        )
        assertEquals(
            BeginHonorWalk.Result.Refused(BeginHonorWalk.Refusal.NOT_WALKABLE),
            begin(controller)(request(sourceWalkId = stub)),
        )
        assertTrue(controller.requests.isEmpty())
        assertNull(h.store.staged(mintedUuid))
    }

    @Test
    fun `a staging write that fails refuses the start`() = runBlocking {
        val blocked = File(folder.root, "blocked").apply { writeText("a file where the store's folder should be") }
        val controller = RecordingController()

        val result = begin(controller, store = WayStore({ File(blocked, "Ways") }))(request())

        assertEquals(BeginHonorWalk.Result.Refused(BeginHonorWalk.Refusal.STAGING_FAILED), result)
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `a start the chain refuses leaves the staging for the launch sweep`() = runBlocking {
        val controller = RecordingController(refuse = true)

        assertThrows(IllegalStateException::class.java) { runBlocking { begin(controller)(request()) } }

        assertNotNull(h.store.staged(mintedUuid))
    }

    // A pilgrimage stage (pilgrimage-stage spec P3 §9; owner decision 2)

    private fun stageRequest(settings: HonorSettings) =
        BeginHonorWalk.Request(way = HonorWayChoice.Stored(HonorHarness.STAGE_ID), intention = null, settings = settings)

    @Test
    fun `a stage's Start stages the stage under the minted uuid and leaves its package as it was`() = runBlocking {
        val stage = HonorHarness.stage()
        h.store.save(stage)

        begin(RecordingController())(stageRequest(settings))

        assertEquals(stage, h.store.staged(mintedUuid))
        assertEquals(stage, h.store.load(HonorHarness.STAGE_ID))
    }

    // iOS `testAStageWalksWithNoCompanionAndNoSoftTap` (`PilgrimageStageWalkTests.swift@7c200bf`).
    @Test
    fun `a stage walks with no soft tap, the preference on`() = runBlocking {
        h.store.save(HonorHarness.stage())
        val controller = RecordingController()

        begin(controller)(stageRequest(HonorSettings(voicesEnabled = true, softTapEnabled = true)))

        val sent = controller.requests.single().honor!!.settings
        assertEquals(HonorSettings(voicesEnabled = true, softTapEnabled = false), sent)
    }

    // iOS `testAnOwnWalkWayKeepsItsCompanion`, the control: no stage, so the preference stands.
    @Test
    fun `an own walk keeps the soft tap the preference set`() = runBlocking {
        val controller = RecordingController()

        begin(controller)(request().copy(settings = HonorSettings(voicesEnabled = true, softTapEnabled = true)))

        assertTrue(controller.requests.single().honor!!.settings.softTapEnabled)
    }

    @Test
    fun `the Sounds switch silences a Way's voices, and the soft tap stays off`() {
        assertFalse(HonorSettings.atStart(honorVoicesEnabled = true, soundsEnabled = false).voicesEnabled)
        assertFalse(HonorSettings.atStart(honorVoicesEnabled = false, soundsEnabled = true).voicesEnabled)
        assertEquals(
            HonorSettings(voicesEnabled = true, softTapEnabled = false),
            HonorSettings.atStart(honorVoicesEnabled = true, soundsEnabled = true),
        )
    }

    private companion object {
        const val SHARED_ID = "share:Qoi4YmPHLN"
        const val WAIT_BUDGET_MILLIS = 30_000L
    }

    /** The UI's side of the chain, stood in for: it records the request and answers as the tracker would. */
    private class RecordingController(
        private val refuse: Boolean = false,
        private val onStart: () -> Unit = {},
    ) : WalkController {
        val requests = mutableListOf<WalkStartRequest>()
        override val state: StateFlow<WalkState> = MutableStateFlow(WalkState.Idle)
        override val bellTriggers: SharedFlow<BellTrigger> = MutableSharedFlow<BellTrigger>().asSharedFlow()
        override val liveSteps: StateFlow<Int?> = MutableStateFlow(null)

        override suspend fun startWalk(request: WalkStartRequest): Walk {
            requests += request
            onStart()
            check(!refuse) { "tracker did not start walk within 5000 ms" }
            return Walk(id = 77L, uuid = request.walkUuid!!, startTimestamp = 1L)
        }

        override suspend fun startWalk(intention: String?, mode: WalkMode): Walk = error("unused")
        override suspend fun pauseWalk() = Unit
        override suspend fun resumeWalk() = Unit
        override suspend fun startMeditation() = Unit
        override suspend fun endMeditation(endMillis: Long?) = Unit
        override suspend fun finishWalk() = Unit
        override suspend fun discardWalk() = Unit
        override suspend fun recordLocation(point: LocationPoint) = Unit
        override suspend fun setIntention(text: String) = Unit
        override suspend fun recordWaypoint(label: String?, icon: String?) = Unit
        override suspend fun recoverStaleWalks(): Long? = null
        override suspend fun restoreActiveWalk(): Walk? = null
    }
}
