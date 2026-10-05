// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.Manifest
import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace
import org.walktalkmeditate.pilgrim.honor.HonorReplies
import org.walktalkmeditate.pilgrim.honor.TheirSitting
import org.walktalkmeditate.pilgrim.walk.CountingStateFlow
import org.walktalkmeditate.pilgrim.walk.UiWalkController
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver
import org.walktalkmeditate.pilgrim.walk.WalkTrackingWatchdog
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix
import org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore

/**
 * Walking a pilgrimage stage, against the `:tracker` harness: what the
 * session row tells the ledger, what a finish and a recovery record, and
 * the walker's reply to the stage's closing line. Ports, names kept, iOS
 * `PilgrimageStageWalkTests.swift@7c200bf`'s outcome and ledger cases,
 * its `+Replies` and `+Recovery` extensions, and `VoiceRecordingDiscardTests.swift`
 * (pilgrimage-stage spec P2 §9, P3 §10, §13, §19). iOS's checkpoint is
 * Android's live session row, its coordinator the finalizer, and its view
 * model's recording sink the UI's [WalkLifecycleObserver] and [HonorReplies].
 *
 * Ported elsewhere: `testTheEngineReportsWhetherItEverAnchoredOnTheWay`
 * (`HonorEngineTest`, with its row twin in `HonorSessionTest`),
 * `testAStageWalksWithNoCompanionAndNoSoftTap` and `testAnOwnWalkWayKeepsItsCompanion`
 * (`BeginHonorWalkTest`), and the "nothing else" half of
 * `testWaterAheadBorrowsTheCaptionLineAndNothingElse` (`HonorSessionTest`),
 * `testTheStageLineStandsWhereADateWould`, `testTheFactsLineReadsInTheWalkersOwnUnit`,
 * `testTheWeatherLineIsSilentWithoutASnapshot` and `testTheOfflineNoteIsSaidOnceAndOnlyForAStage`
 * (`HonorOverviewViewModelTest`), and `testTheLocalNameFollowsAFixedOrderAndNeverEchoesTheLabel`
 * and `testThePlaceCopyChangesForAStage` (`WayMomentCopyTest`).
 * Left for the units that build their surfaces:
 * U39's `testTheArrivalCardForAStageNamesTheStageAndCarriesNoDelta`,
 * `testAPinDrawsAtItsOwnCoordinateWhileTheTriggerStaysOnTheLine`, the caption half of
 * `testWaterAheadBorrowsTheCaptionLineAndNothingElse`, and `+Replies`'
 * `testTheArrivalCardAppendsTheStagesClosingLine`; U40's
 * `testTheSummaryForAStageReadsKilometresAndNoCompanionDelta`,
 * `testTheSummaryKickerDropsTheirStepsForAStage`,
 * `testTheWaysListNeitherShowsAnInstalledStageNorTakesItWithTheRest`, and `+Replies`'
 * `testTheSummaryCarriesTheClosingOnlyWhenArrivalFired`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageStageWalkTest {

    @get:Rule val folder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var h: HonorHarness
    private val dao get() = h.db.honorDao()
    private val start = Instant.ofEpochSecond(1_000_000)

    /** The UI process's scopes, joined before the database closes (the closed-pool lesson). */
    private val uiScopes = mutableListOf<CoroutineScope>()
    private val recordingFolders = mutableListOf<File>()

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
    }

    @After
    fun tearDown() {
        runBlocking { uiScopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() } }
        h.close()
        recordingFolders.forEach { it.deleteRecursively() }
    }

    /**
     * iOS's `stageWay(index:)`: a 1 km stage east along the equator, so
     * every distance is arithmetic (0.000898° of longitude is 100 m), with
     * one waypoint at 0.3 that carries words, names, a sitting and a pin.
     */
    private fun stageWay(index: Int = 0): Way {
        val orisson = WayMoment(
            id = "wp-orisson",
            frac = 0.3,
            at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
            text = "A shepherd carried this Madonna up from Lourdes.",
            names = mapOf("eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson"),
            sitMinutes = 5,
            pin = WayCoordinate(lat = 0.0002, lon = 300.0 / 111_320),
        )
        return Way(
            id = WayStore.stageWayId("camino-frances", index),
            source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = index),
            title = "Saint-Jean-Pied-de-Port to Roncesvalles",
            departedAt = start,
            tzIdentifier = "Europe/Madrid",
            expires = null,
            route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.000898, alt = null, t = it * 60.0) },
            totalDistanceMeters = 1000.0,
            theirActiveSeconds = 600.0,
            moments = listOf(orisson),
            weather = null,
            marks = emptyList(),
            stage = WayStage(
                routeId = "camino-frances", index = index, count = 33,
                name = "Saint-Jean-Pied-de-Port to Roncesvalles", theme = "Initiation",
                narrative = "The Pyrenees are the first question the way asks.",
                closing = "You crossed a border on foot.",
                warnings = listOf("The Napoleon Route closes in winter."),
                distanceKm = 24.2, gainMeters = 1419.0, hours = WayStageHours(min = 7.0, max = 9.0), difficulty = "hard",
                start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 0.0, lon = 0.0)),
                end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 0.0, lon = 0.00898)),
            ),
        )
    }

    /**
     * A stage walk as Room holds it once its `:tracker` has stopped: the row
     * naming the stage [way] carries, as Start records it, and the engine's
     * last word ([outcome] null when it never anchored), as iOS's checkpoint
     * carries both; the copy staged at Begin. [endTimestamp] null for a walk
     * the OS killed, which only recovery finishes.
     */
    private suspend fun stageWalk(
        way: Way,
        outcome: HonorStageOutcome?,
        endTimestamp: Long? = null,
        finishKind: HonorFinishKind? = null,
    ): Walk {
        val uuid = UUID.randomUUID().toString()
        val id = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = start.toEpochMilli(), endTimestamp = endTimestamp))
        val stage = way.stage
        dao.insertSession(
            HonorSessionEntity(
                walkId = id,
                wayId = way.id,
                sourceKind = HonorSourceKind.PILGRIMAGE,
                voicesEnabled = false,
                softTapEnabled = false,
                phase = if (outcome?.arrived == true) HonorPhase.ARRIVED else HonorPhase.WALKING,
                startFrac = outcome?.let { 0.0 },
                progressFrac = outcome?.progressFrac ?: 0.0,
                gateGeneration = 1,
                finishKind = finishKind,
                stageRouteId = stage?.routeId,
                stageIndex = stage?.index,
                stageName = stage?.name,
                stageDistanceKm = stage?.distanceKm,
            ),
        )
        h.store.stage(uuid, way)
        return h.db.walkDao().getById(id)!!
    }

    private suspend fun finishedStageWalk(way: Way, outcome: HonorStageOutcome?): Walk =
        stageWalk(way, outcome, endTimestamp = start.toEpochMilli() + 3_600_000L, finishKind = HonorFinishKind.CLEAN)

    private suspend fun HonorSession.begin(walk: Walk, initialFix: LocationPoint? = null) =
        start(h.serviceScope, walk.id, h.controller.state, initialFix).also { awaitIdle() }

    private fun ledger(): PilgrimageLedger? = h.ledgers.load("camino-frances")

    // ---- iOS PilgrimageStageWalkTests: the outcome and the ledger ----

    @Test
    fun testTheOutcomeSurvivesTeardown() = runBlocking {
        val walk = h.startHonorWalk(stageWay())
        val session = h.newSession()
        session.begin(walk, fix(0.002694, h.clock.millis))

        session.stop()

        val outcome = dao.getSession(walk.id)!!.stageOutcome()
        assertNotNull("the engine is gone by the time the walk is saved", outcome)
        assertEquals(0.3, outcome!!.progressFrac, 0.02)
        assertFalse(outcome.arrived)
    }

    @Test
    fun testNoOutcomeWithoutAnAnchor() = runBlocking {
        val walk = h.startHonorWalk(stageWay())
        val session = h.newSession()
        session.begin(walk, fix(0.0, h.clock.millis, lat = 0.01))

        session.stop()

        assertNull(dao.getSession(walk.id)!!.stageOutcome())
    }

    @Test
    fun testTheCoordinatorWritesTheStageIntoTheRoutesLedger() = runBlocking {
        val walk = finishedStageWalk(stageWay(index = 4), HonorStageOutcome(progressFrac = 0.58, arrived = false))

        h.finalizer.finalize(walk.id)

        val entry = ledger()!!.stages["4"]
        assertEquals("Saint-Jean-Pied-de-Port to Roncesvalles", entry?.name)
        assertEquals(0.58, entry!!.stoppedAtFrac!!, 0.001)
        assertEquals(false, entry.completed)
        assertEquals("stages 0 to 3 are still unwalked", PilgrimageLedger.Next(0, null), ledger()!!.next(stageCount = 33))
    }

    @Test
    fun testNothingIsWrittenForAWayThatIsNotAStageOrAWalkThatNeverJoined() = runBlocking {
        val neverJoined = finishedStageWalk(stageWay(), outcome = null)
        h.finalizer.finalize(neverJoined.id)
        assertNull(ledger())

        val notAStage = finishedStageWalk(stageWay().copy(stage = null), HonorStageOutcome(progressFrac = 1.0, arrived = true))
        h.finalizer.finalize(notAStage.id)
        assertNull(ledger())
    }

    // ---- iOS +Replies: the reply to the stage's closing line ----

    @Test
    fun testTheReflectionIsFiledUnderTheReservedOrigin() {
        val stage = stageWay().stage!!
        val moment = HonorPersistence.stageReflectionMoment(stage)
        assertEquals(HonorPersistence.STAGE_REFLECTION_MOMENT_ID, moment.id)
        assertEquals(HonorPersistence.STAGE_REFLECTION_ORIGIN, voiceOriginIndex(moment.id))
        assertEquals(-1, HonorPersistence.STAGE_REFLECTION_ORIGIN)
        assertEquals("the reply is recorded at the stage's end place", stage.end.at, moment.at)

        val voice = WayMoment(
            id = "voice-3", frac = 0.5, at = null,
            kind = WayMomentKind.Voice(endFrac = 0.6, duration = 10.0, kind = VoiceKind.SPOKEN, media = WayMedia.File("audio/3.m4a")),
        )
        assertEquals("voice replies are unchanged", 3, voiceOriginIndex(voice.id))
        assertNull(
            voiceOriginIndex(WayMoment(id = "wp-orisson", frac = 0.3, at = null, kind = WayMomentKind.Waypoint("x", "mappin")).id),
        )
    }

    @Test
    fun testAReplyToTheReflectionRoundTrips() {
        val way = stageWay()
        h.store.save(way)
        val relativePath = "recordings/stage-reply-${UUID.randomUUID()}.wav"
        val recording = h.writeRecording(relativePath)
        val media = HonorMediaFiles({ h.filesRoot }, h.store)

        assertNull(media.stageReflectionReply(way))
        h.store.setReply(way.id, originN = HonorPersistence.STAGE_REFLECTION_ORIGIN, relativePath = relativePath)
        assertEquals(recording.canonicalFile, media.stageReflectionReply(way))
    }

    /**
     * The arrival card invites a reply just as the walker is about to press
     * stop, so the take is still open when the walk ends. It is saved by the
     * walk-end auto-stop, after the walk is torn down, and must still be filed.
     */
    @Test
    fun testAReplyStillRecordingWhenTheWalkEndsIsStillFiledUnderTheReflection() = runBlocking {
        val way = stageWay()
        h.store.save(way)
        val ui = RecordingUi()
        val walk = ui.startWalk(way)
        ui.openTake(walk)

        // "reply here" with a take already open adopts it (pilgrim-ios #99, matched).
        ui.replies.arm(walkId = walk.id, wayId = way.id, momentId = HonorPersistence.STAGE_REFLECTION_MOMENT_ID)
        assertEquals(HonorPersistence.STAGE_REFLECTION_MOMENT_ID, ui.replies.pending.value?.momentId)

        h.clock.millis += 5_000
        h.controller.finishWalk()
        ui.awaitFiled()

        val take = h.repository.voiceRecordingsFor(walk.id).single()
        assertEquals(mapOf(HonorPersistence.STAGE_REFLECTION_ORIGIN to take.fileRelativePath), h.store.replies(way.id))
        assertEquals(
            File(app.filesDir, take.fileRelativePath).canonicalFile,
            HonorMediaFiles({ app.filesDir }, h.store).stageReflectionReply(way),
        )
    }

    // The play path the arrival card's "your reply" takes (P3 §13.4 item 3, P5 §8.3).

    @Test
    fun `a reply to the closing line plays on a later walk of the stage, through the tracker's play-reply command`() =
        runBlocking {
            val way = stageWay()
            h.store.save(way)
            h.writeRecording("recordings/earlier/reflection.wav")
            h.store.setReply(way.id, HonorPersistence.STAGE_REFLECTION_ORIGIN, "recordings/earlier/reflection.wav")
            val walk = h.startHonorWalk(way)
            val ports = FakePorts()
            val session = h.newSession(ports)
            session.begin(walk, fix(0.0, h.clock.millis))

            session.command(seq = 1, command = HonorCommand.PlayReply(HonorPersistence.STAGE_REFLECTION_MOMENT_ID))
            session.awaitIdle()

            assertEquals(listOf("duck", "reply reflection"), ports.voiceCalls())
        }

    @Test
    fun `the reflection's id plays no reply on a Way that isn't a stage`() = runBlocking {
        val way = HonorHarness.way(moments = emptyList())
        h.store.save(way)
        h.writeRecording("recordings/earlier/reflection.wav")
        h.store.setReply(way.id, HonorPersistence.STAGE_REFLECTION_ORIGIN, "recordings/earlier/reflection.wav")
        val walk = h.startHonorWalk(way)
        val ports = FakePorts()
        val session = h.newSession(ports)
        session.begin(walk, fix(0.0, h.clock.millis))

        session.command(seq = 1, command = HonorCommand.PlayReply(HonorPersistence.STAGE_REFLECTION_MOMENT_ID))
        session.awaitIdle()

        assertEquals(emptyList<String>(), ports.voiceCalls())
    }

    // ---- iOS +Recovery: a stage walk the OS killed ----

    @Test
    fun testACheckpointCarriesTheWayAndTheEnginesLastWord() = runBlocking {
        val walk = h.startHonorWalk(stageWay())
        val session = h.newSession()
        session.begin(walk)
        assertNull("no fix yet, so no stage joined", dao.getSession(walk.id)!!.stageOutcome())

        h.clock.millis += 1_000
        session.onFix(fix(0.002694, h.clock.millis))
        session.awaitIdle()

        val row = dao.getSession(walk.id)!!
        assertEquals(WayStore.stageWayId(routeId = "camino-frances", stageIndex = 0), row.wayId)
        assertEquals(0.3, row.stageOutcome()!!.progressFrac, 0.02)
        assertEquals(false, row.stageOutcome()!!.arrived)
        session.stop()
    }

    @Test
    fun testAWanderWalksCheckpointCarriesNoWay() = runBlocking {
        val walk = h.controller.startWalk()

        assertNull(dao.getSession(walk.id))
    }

    @Test
    fun testACrashedStageWalkFindsItsWayAndItsLedgerAtNextLaunch() = runBlocking {
        val way = stageWay(index = 4)
        h.store.save(way)
        val walk = stageWalk(way, HonorStageOutcome(progressFrac = 0.58, arrived = false))

        assertEquals("the crashed walk must still be saved", walk.id, recoverAtNextLaunch())

        assertEquals(way.id, h.store.wayId(walk.uuid))
        val entry = ledger()!!.stages["4"]
        assertEquals("Saint-Jean-Pied-de-Port to Roncesvalles", entry?.name)
        assertEquals(0.58, entry!!.stoppedAtFrac!!, 0.001)
        assertEquals(false, entry.completed)
    }

    /**
     * A walk that crashed while the walker was still approaching earned no
     * ledger entry, but the Way is still theirs: the summary shows its stage
     * block either way.
     */
    @Test
    fun testACrashBeforeTheStageWasJoinedLinksTheWayAndWritesNoLedger() = runBlocking {
        val way = stageWay()
        h.store.save(way)
        val walk = stageWalk(way, outcome = null)

        assertNotNull(recoverAtNextLaunch())

        assertEquals(way.id, h.store.wayId(walk.uuid))
        assertNull("an approach is not a stage walked", ledger())
    }

    /** Android's walk from before the Way id: one whose Honor session row never landed. */
    @Test
    fun testACheckpointFromBeforeTheWayIdStillRecoversAsAPlainWalk() = runBlocking {
        h.store.save(stageWay())
        val uuid = UUID.randomUUID().toString()
        val walkId = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = start.toEpochMilli()))

        assertEquals("a walk crashed under the previous build is not thrown away", walkId, recoverAtNextLaunch())

        assertNull(h.store.wayLink(uuid))
        assertNull(ledger())
    }

    /** The UI's cold launch with both processes dead, as `PilgrimApp.onCreate` runs it: recovery, then the Honor maintenance. */
    private suspend fun recoverAtNextLaunch(): Long? {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { uiScopes += it }
        val ui = UiWalkController(
            repository = h.repository,
            actionPublisher = WalkActionPublisher(app),
            watchdog = WalkTrackingWatchdog(app),
            scope = scope,
            releaseFlags = FixedReleaseFlags(honor = true),
            unitsPreferences = FakeUnitsPreferencesRepository(UnitSystem.Metric),
        )
        val recovered = ui.recoverStaleWalks()
        h.finalizer.runAtLaunch()
        return recovered
    }

    // ---- iOS VoiceRecordingDiscardTests, on a stage with the reflection armed ----

    @Test
    fun test_discardRecording_releasesTheSessionAndTakesThePartialFile() = runBlocking {
        val way = stageWay()
        h.store.save(way)
        val ui = RecordingUi()
        val walk = ui.startWalk(way)
        val partial = ui.openTake(walk)
        ui.replies.arm(walkId = walk.id, wayId = way.id, momentId = HonorPersistence.STAGE_REFLECTION_MOMENT_ID)

        ui.discard()
        ui.awaitDeleted(partial)

        assertFalse(ui.recorder.isRecording.value)
        assertNull(ui.recorder.recordingStartedAtMillis)
        val requested = shadowOf(ui.audioManager).lastAudioFocusRequest
        assertNotNull(requested)
        assertSame(
            "the microphone is let go here, not at a commit that never comes",
            requested.audioFocusRequest,
            shadowOf(ui.audioManager).lastAbandonedAudioFocusRequest,
        )
        assertFalse("a discarded walk leaves no orphan audio on disk", Files.exists(partial))
        assertNull("nor an origin a later take could answer", ui.replies.pending.value)
        assertEquals(emptyMap<Int, String>(), h.store.replies(way.id))
    }

    @Test
    fun test_discardRecording_doesNothingWithNoRecordingOpen() = runBlocking {
        val way = stageWay()
        h.store.save(way)
        val ui = RecordingUi()
        assertTrue(ui.audioFocus.requestMediaPlayback())
        ui.startWalk(way)

        ui.discard()
        ui.awaitHandled()

        assertFalse(ui.recorder.isRecording.value)
        assertNull(
            "another consumer's session is not this one's to release",
            shadowOf(ui.audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    /**
     * The UI process's recording side as the app builds it: a recorder over
     * a fake microphone, the replies, and the lifecycle observer on the
     * walk's state, whose walk-end auto-stop saves and files the take.
     */
    private inner class RecordingUi {
        val audioManager: AudioManager = app.getSystemService(AudioManager::class.java)
        val audioFocus = AudioFocusCoordinator(audioManager)
        val recorder = VoiceRecorder(app, FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 })), audioFocus, h.clock)
        val replies = HonorReplies(h.store)
        private val walkState = CountingStateFlow(h.controller.state)
        private val scope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance).also { uiScopes += it }

        init {
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            WalkLifecycleObserver(
                walkState = walkState,
                scope = scope,
                voiceRecorder = recorder,
                repository = h.repository,
                orphanSweeper = OrphanRecordingSweeper(app, h.repository, FakeTranscriptionScheduler()),
                seekSessionStore = SeekSessionStore(),
                honorReplies = replies,
                theirSitting = TheirSitting(),
            )
            // The observer drops its first state; consumed, the next one is a transition.
            runBlocking { withTimeout(WAIT_MILLIS) { walkState.processed.first { it >= 1 } } }
        }

        /** Starts the walk and waits for the observer to see it on, so its end is a transition. */
        suspend fun startWalk(way: Way): Walk {
            val consumed = walkState.processed.value
            val walk = h.startHonorWalk(way)
            withTimeout(WAIT_MILLIS) { walkState.processed.first { it > consumed } }
            return walk
        }

        /** A take with audio in it, so its save is a recording and not an empty one. */
        fun openTake(walk: Walk): Path {
            val path = recorder.start(walkId = walk.id, walkUuid = walk.uuid).getOrThrow()
            recordingFolders += path.parent.toFile()
            awaitWallClock("the fake microphone's burst") { recorder.audioLevel.value > 0f }
            return path
        }

        private var consumedBeforeDiscard = 0

        /** `WalkViewModel.discardWalk`: the origin goes first, then the walk, whose end stops the take (iOS `cancel()`). */
        suspend fun discard() {
            consumedBeforeDiscard = walkState.processed.value
            replies.clear()
            h.controller.discardWalk()
        }

        fun awaitFiled() = awaitWallClock("the walk-end take's reply") { replies.filed.value == 1L }

        fun awaitDeleted(path: Path) = awaitWallClock("the partial take's removal") { !Files.exists(path) }

        /** The observer forks each end's handling into a child of its scope, beside its long-lived collector. */
        fun awaitHandled() {
            val job = checkNotNull(scope.coroutineContext[Job])
            awaitWallClock("the observer's handling of the discard") {
                walkState.processed.value > consumedBeforeDiscard && job.children.count() == 1
            }
        }
    }

    /** The observer and the recorder run on real threads, so these wait on the wall clock, with a failsafe. */
    private fun awaitWallClock(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue("$what never came", done())
    }

    private companion object {
        /** A failsafe, not a grace window: each wait returns the moment its condition holds. */
        const val WAIT_MILLIS = 30_000L
    }
}
