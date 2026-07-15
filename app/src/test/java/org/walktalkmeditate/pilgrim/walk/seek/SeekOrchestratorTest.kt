// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.data.whisper.WhisperDefinition
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlaying
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekClearing
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngine
import org.walktalkmeditate.pilgrim.domain.seek.SeekEngineEvent
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.ui.walk.map.SeekFogRenderer
import org.walktalkmeditate.pilgrim.ui.walk.map.SeekFogStyle

/**
 * The wiring keystone's contract (iOS `ActiveWalkSeekTests.swift@c1745e8`
 * + the plan's U9 scenarios): boot on (Active seek + pending session),
 * the full event → senses/persistence routing order, the structural
 * restore-path filter, reroll ordinal continuity, whisper scheduling,
 * and teardown. Port spec:
 * docs/parity/2026-07-14-port-seek-orchestrator-u9.md.
 *
 * Robolectric + in-memory Room on the test scheduler's executor (the
 * WalkViewModelPlacementTest pattern) so arrival persistence runs
 * against real SQLite while the engine's pulse/stillness clocks stay on
 * virtual time. `runCurrent`/`advanceTimeBy` only — the engine's 5 s
 * stillness tick is a perpetual loop `advanceUntilIdle` can never drain
 * (Stage 5-E).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekOrchestratorTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository

    private val walkState = MutableStateFlow<WalkState>(WalkState.Idle)
    private val sessionStore = SeekSessionStore()
    private val fixes = MutableSharedFlow<LocationPoint>(extraBufferCapacity = 64)
    private val tiers = MutableSharedFlow<SeekPowerTier>(extraBufferCapacity = 4)
    private var nowMs = 1_000_000L
    private val clock = Clock { nowMs }
    private val ops = mutableListOf<String>()
    private val sound = SpySeekSound()
    private val playedWhispers = mutableListOf<WhisperDefinition>()
    private var revealWhisper: WhisperDefinition? = null
    private var whisperThrows = false
    private var soundsPrefs = FakeSoundsPreferencesRepository()
    private val locationSource = FakeSeekLocationSource()

    private val home = SeekPoint(latitude = 42.8782, longitude = -8.5448)

    private inner class SpySeekSound : SeekSoundPlaying {
        var prepareCount = 0
        val pings = mutableListOf<Pair<Boolean, Float>>()
        var bowlCount = 0
        var completionBowlCount = 0
        var stopCount = 0
        override fun prepare() {
            prepareCount++
            ops += "prepare"
        }
        override fun playPing(aligned: Boolean, closeness: Float) {
            pings += aligned to closeness
            ops += "ping"
        }
        override fun playBowl() {
            bowlCount++
            ops += "bowl"
        }
        override fun playCompletionBowl() {
            completionBowlCount++
            ops += "completionBowl"
        }
        override fun stop() {
            stopCount++
            ops += "soundStop"
        }
    }

    private inner class FakeSeekLocationSource : LocationSource {
        var lastKnown: LocationPoint? = null
        override fun locationFlow(): Flow<LocationPoint> = fixes
        override fun rawLocationFlow(): Flow<LocationPoint> = fixes
        override suspend fun lastKnownLocation(): LocationPoint? = lastKnown
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── Harness ─────────────────────────────────────────────────────

    private fun senses() = SeekSenses(
        soundPlayer = sound,
        arrivalHaptic = {
            // Persist-before-ritual probe: the marker snapshots what
            // Room ALREADY holds at haptic time (synchronous raw query
            // — the DAO executors would deadlock the test thread).
            ops += "haptic:arrival(events=${countSeekArrivalEvents()},waypoints=${countArrivalWaypoints()})"
        },
        breathInHaptic = { ops += "haptic:breathIn" },
        pickRevealWhisper = { revealWhisper },
        playWhisper = {
            if (whisperThrows) error("whisper playback exploded")
            playedWhispers += it
            ops += "whisper:${it.id}"
        },
    )

    private fun TestScope.startOrchestrator(): SeekOrchestrator {
        val orchestrator = SeekOrchestrator(
            walkState = walkState,
            scope = backgroundScope,
            sessionStore = sessionStore,
            repository = repository,
            locationSource = locationSource,
            powerTiers = tiers,
            senses = senses(),
            soundsPreferences = soundsPrefs,
            clock = clock,
            context = context,
        )
        orchestrator.start()
        runCurrent()
        return orchestrator
    }

    private fun chain(clearingCount: Int, spacingMeters: Double = 1_000.0): SeekChain =
        SeekChain(
            clearings = (1..clearingCount).map { index ->
                SeekClearing(
                    center = SeekChainGenerator.destination(
                        from = home,
                        bearingDegrees = 0.0,
                        distanceMeters = spacingMeters * index,
                    ),
                    radiusMeters = 50.0,
                )
            },
            budgetMeters = 5_000.0,
        )

    private fun pendingSession(chain: SeekChain, intention: String? = "find the river") =
        SeekPendingSession(
            chain = chain,
            durationMinutes = 30,
            tint = null,
            seededAtEpochMillis = nowMs,
            intention = intention,
        )

    private suspend fun TestScope.startSeekWalk(
        chain: SeekChain,
        intention: String? = "find the river",
    ): Long {
        val walk = repository.startWalk(startTimestamp = nowMs, intention = intention)
        runCurrent()
        sessionStore.set(pendingSession(chain, intention))
        walkState.value = WalkState.Active(
            WalkAccumulator(walkId = walk.id, startedAt = nowMs, mode = WalkMode.Seek),
        )
        runCurrent()
        return walk.id
    }

    private fun fix(at: SeekPoint, accuracy: Float? = 10f): LocationPoint = LocationPoint(
        timestamp = nowMs,
        latitude = at.latitude,
        longitude = at.longitude,
        horizontalAccuracyMeters = accuracy,
    )

    private suspend fun TestScope.emitFix(at: SeekPoint) {
        fixes.emit(fix(at))
        runCurrent()
    }

    private suspend fun TestScope.driveArrival(center: SeekPoint) {
        repeat(3) { emitFix(center) }
    }

    /**
     * BEGAN needs two good post-arrival fixes; COMPLETED needs the
     * stillness window (67.5–135 s with the ×1.5 multiplier) elapsed on
     * the injected clock at a 5 s virtual tick.
     */
    private suspend fun TestScope.driveStillnessReveal(center: SeekPoint) {
        emitFix(center)
        emitFix(center)
        nowMs += 135_000L
        advanceTimeBy(5_001)
        runCurrent()
    }

    private fun countSeekArrivalEvents(): Int =
        rawCount("SELECT COUNT(*) FROM walk_events WHERE event_type = 'SEEK_ARRIVAL'")

    private fun countArrivalWaypoints(): Int =
        rawCount("SELECT COUNT(*) FROM waypoints WHERE icon = '${SeekPersistence.ARRIVAL_WAYPOINT_ICON}'")

    private fun rawCount(sql: String): Int =
        db.query(sql, null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun activeFogCircle(state: SeekFogState?): SeekFogState.FogCircle? =
        state?.circles?.firstOrNull { !it.isHalo }

    private val stubWhisper = WhisperDefinition(
        id = "test-whisper",
        title = "Test",
        category = WhisperCategory.Presence,
        audioFileName = "test-whisper",
        durationSec = 1.0,
    )

    // ─── The full contract (plan U9 happy path + review B7) ──────────

    @Test
    fun `two clearing walk drives the full sensory contract in order`() = runTest(dispatcher) {
        revealWhisper = stubWhisper
        val chain = chain(2)
        val orchestrator = startOrchestrator()
        val walkId = startSeekWalk(chain)

        assertEquals("boot prepares the sonar channel once", 1, sound.prepareCount)
        assertNull("boot consumes the pending session", sessionStore.pending.value)

        // First fix arms the heartbeat; the fog shows the active
        // clearing at the thickest-for-distance bucket, no halos.
        emitFix(home)
        val distance = SeekChainGenerator.distance(home, chain.clearings[0].center)
        val fogBeforeArrival = orchestrator.fogState.value
        assertNotNull(fogBeforeArrival)
        assertEquals(1, fogBeforeArrival!!.circles.size)
        assertFalse(fogBeforeArrival.circles[0].isHalo)
        assertTrue(activeFogCircle(fogBeforeArrival)!!.opacityBucket >= 1)

        // Pulse: token starts at 1, ping carries the closeness curve.
        val interval = SeekEngine.pulseIntervalMillis(distance, SeekPowerTier.NORMAL)
        advanceTimeBy(interval + 1)
        runCurrent()
        assertEquals(1, orchestrator.pulse.value.token)
        assertEquals(1, sound.pings.size)
        assertEquals(
            SeekEngine.closeness(distance).toFloat(),
            sound.pings[0].second,
            0.0001f,
        )

        // Arrival: persistence commits BEFORE the ritual haptic — the
        // marker snapshots the rows visible at haptic time.
        driveArrival(chain.clearings[0].center)
        assertTrue(
            "arrival haptic must see the persisted event+waypoint: $ops",
            ops.contains("haptic:arrival(events=1,waypoints=1)"),
        )
        val firstWaypoints = repository.waypointsFor(walkId)
        runCurrent()
        assertEquals(1, firstWaypoints.size)
        assertEquals(SeekPersistence.ARRIVAL_WAYPOINT_ICON, firstWaypoints[0].icon)
        assertEquals("First clearing", firstWaypoints[0].label)
        // The dissolve IS the moment: active circle drops to bucket 0.
        assertEquals(0, activeFogCircle(orchestrator.fogState.value)!!.opacityBucket)

        // Stillness begins → breath-in haptic; window completes →
        // reveal: bowl, fog halo for the found clearing, whisper 2.5 s
        // after the bowl.
        driveStillnessReveal(chain.clearings[0].center)
        assertTrue(ops.contains("haptic:breathIn"))
        assertEquals(1, sound.bowlCount)
        assertTrue(playedWhispers.isEmpty())
        val fogAfterReveal = orchestrator.fogState.value!!
        assertEquals(2, fogAfterReveal.circles.size)
        assertTrue(fogAfterReveal.circles[0].isHalo)
        assertFalse(fogAfterReveal.circles[1].isHalo)
        advanceTimeBy(2_501)
        runCurrent()
        assertEquals(listOf("test-whisper"), playedWhispers.map { it.id })

        // Second clearing: arrive (ordinal 2), stillness, final reveal
        // → completion bowl, all halos, phase COMPLETE.
        driveArrival(chain.clearings[1].center)
        assertTrue(ops.contains("haptic:arrival(events=2,waypoints=2)"))
        driveStillnessReveal(chain.clearings[1].center)
        assertEquals("final reveal rings the completion bowl", 1, sound.completionBowlCount)
        assertEquals(1, sound.bowlCount)
        assertEquals(SeekEnginePhase.COMPLETE, orchestrator.enginePhase.value)
        val fogComplete = orchestrator.fogState.value!!
        assertEquals(2, fogComplete.circles.size)
        assertTrue(fogComplete.circles.all { it.isHalo })
        val labels = repository.waypointsFor(walkId).map { it.label }
        runCurrent()
        assertEquals(listOf("First clearing", "Second clearing"), labels)
        assertEquals(
            2,
            repository.eventsFor(walkId).count { it.eventType == WalkEventType.SEEK_ARRIVAL },
        )

        // The completed engine is inert — no more pulses ever.
        val pingsAtComplete = sound.pings.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(pingsAtComplete, sound.pings.size)

        // Walk end: teardown clears fog/pulse/phase and stops the sound.
        walkState.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, mode = WalkMode.Seek),
            endedAt = nowMs,
        )
        runCurrent()
        assertNull(orchestrator.fogState.value)
        assertEquals(SeekPulseVisual.NONE, orchestrator.pulse.value)
        assertNull(orchestrator.enginePhase.value)
        assertTrue(sound.stopCount >= 1)
    }

    @Test
    fun `arrival fog dissolves and reveal leaves a halo through the real renderer`() =
        runTest(dispatcher) {
            // Review B7: engine → orchestrator fog states → the REAL
            // SeekFogRenderer state machine against a fake style.
            val style = RecordingFogStyle()
            val renderer = SeekFogRenderer(style)
            val chain = chain(2)
            val orchestrator = startOrchestrator()
            startSeekWalk(chain)

            fun applyCurrent() = renderer.apply(
                orchestrator.fogState.value,
                orchestrator.pulse.value,
                reduceMotion = false,
            )

            emitFix(home)
            applyCurrent()
            val activeId = activeFogCircle(orchestrator.fogState.value)!!.id
            assertTrue("guiding installs the active fog circle", style.installed.contains(activeId))
            assertFalse(style.installedAsHalo(activeId))

            driveArrival(chain.clearings[0].center)
            applyCurrent()
            assertEquals(
                "arrival dissolves the active circle to 0 opacity",
                0.0,
                style.opacities[activeId]!!,
                0.0001,
            )

            driveStillnessReveal(chain.clearings[0].center)
            applyCurrent()
            assertTrue(
                "the found clearing keeps a persistent halo",
                style.installedAsHalo(activeId),
            )
            assertTrue(
                "the next clearing's fog appears",
                style.installed.contains(activeFogCircle(orchestrator.fogState.value)!!.id),
            )
        }

    // ─── Restore path + wander (plan edge cases) ─────────────────────

    @Test
    fun `active seek walk without a pending session never boots`() = runTest(dispatcher) {
        // Process-death shape: the walk restores Active with mode Seek
        // (SEEK_MODE event re-derivation), but the in-memory session
        // store is empty — the chain died with the old process.
        val orchestrator = startOrchestrator()
        walkState.value = WalkState.Active(
            WalkAccumulator(walkId = 7L, startedAt = nowMs, mode = WalkMode.Seek),
        )
        runCurrent()
        emitFix(home)
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(0, sound.prepareCount)
        assertEquals(0, sound.pings.size)
        assertNull(orchestrator.fogState.value)
        assertNull(orchestrator.enginePhase.value)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `wander walk lifecycle produces zero seek side effects`() = runTest(dispatcher) {
        val orchestrator = startOrchestrator()
        // Even a stale pending session must not boot a wander walk.
        sessionStore.set(pendingSession(chain(1)))
        walkState.value = WalkState.Active(
            WalkAccumulator(walkId = 3L, startedAt = nowMs, mode = WalkMode.Wander),
        )
        runCurrent()
        emitFix(home)
        advanceTimeBy(120_000)
        runCurrent()
        walkState.value = WalkState.Finished(
            WalkAccumulator(walkId = 3L, startedAt = 0L),
            endedAt = nowMs,
        )
        runCurrent()

        assertEquals(0, sound.prepareCount)
        assertNull(orchestrator.fogState.value)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `conflated terminal transition swaps sessions without cross-walk persistence`() =
        runTest(dispatcher) {
            // StateFlow conflation can elide walk A's Finished emission
            // under contention (the WalkLifecycleObserver-documented
            // race): the observer's next sight is walk B's Active with
            // B's pending session. The stale engine must die and B must
            // boot — arrivals may never persist to A.
            val chainA = chain(1)
            val orchestrator = startOrchestrator()
            val walkA = startSeekWalk(chainA)
            emitFix(home)
            assertEquals(1, sound.prepareCount)

            val chainB = chain(1, spacingMeters = 2_000.0)
            val walkB = repository.startWalk(startTimestamp = nowMs, intention = null)
            runCurrent()
            sessionStore.set(pendingSession(chainB, intention = null))
            walkState.value = WalkState.Active(
                WalkAccumulator(walkId = walkB.id, startedAt = nowMs, mode = WalkMode.Seek),
            )
            runCurrent()

            assertEquals("stale session torn down, new engine booted", 2, sound.prepareCount)
            assertNull(sessionStore.pending.value)
            assertEquals(SeekEnginePhase.GUIDING, orchestrator.enginePhase.value)

            emitFix(home)
            driveArrival(chainB.clearings[0].center)
            assertEquals(
                0,
                repository.eventsFor(walkA).count { it.eventType == WalkEventType.SEEK_ARRIVAL },
            )
            assertEquals(
                1,
                repository.eventsFor(walkB.id).count { it.eventType == WalkEventType.SEEK_ARRIVAL },
            )
        }

    // ─── Seek anew (R17 / ece26a7) ───────────────────────────────────

    @Test
    fun `seek anew emits an immediate pulse and keeps ordinal continuity via persisted icons`() =
        runTest(dispatcher) {
            val chain = chain(1)
            val orchestrator = startOrchestrator()
            val walkId = startSeekWalk(chain)
            emitFix(home)
            driveArrival(chain.clearings[0].center)
            assertEquals(1, countArrivalWaypoints())

            // Reroll from inside the unrevealed clearing: back to
            // guiding, immediate stale-distance pulse (the tangible
            // confirmation), replacement clearing replays index 0.
            val pingsBefore = sound.pings.size
            val tokenBefore = orchestrator.pulse.value.token
            orchestrator.seekAnewRequested()
            runCurrent()
            assertEquals("immediate feedback pulse", pingsBefore + 1, sound.pings.size)
            assertEquals(tokenBefore + 1, orchestrator.pulse.value.token)
            assertEquals(SeekEnginePhase.GUIDING, orchestrator.enginePhase.value)

            // The regenerated active clearing's center comes from the
            // public fog surface — arrive there; the ordinal counts
            // PERSISTED arrivals (2), not the replayed engine index (1).
            val rerolled = activeFogCircle(orchestrator.fogState.value)!!.center
            assertNotEquals(chain.clearings[0].center, rerolled)
            driveArrival(rerolled)
            val labels = repository.waypointsFor(walkId).map { it.label }
            runCurrent()
            assertEquals(listOf("First clearing", "Second clearing"), labels)
            assertTrue(ops.contains("haptic:arrival(events=2,waypoints=2)"))
        }

    @Test
    fun `seek anew after completion is a no-op`() = runTest(dispatcher) {
        val chain = chain(1)
        val orchestrator = startOrchestrator()
        startSeekWalk(chain)
        emitFix(home)
        driveArrival(chain.clearings[0].center)
        driveStillnessReveal(chain.clearings[0].center)
        assertEquals(SeekEnginePhase.COMPLETE, orchestrator.enginePhase.value)

        val pingsBefore = sound.pings.size
        orchestrator.seekAnewRequested()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(pingsBefore, sound.pings.size)
        assertEquals(SeekEnginePhase.COMPLETE, orchestrator.enginePhase.value)
    }

    @Test
    fun `pre-departure seek anew regenerates the pending chain silently`() = runTest(dispatcher) {
        val original = chain(2)
        val orchestrator = startOrchestrator()
        sessionStore.set(pendingSession(original))
        locationSource.lastKnown = fix(home)

        orchestrator.seekAnewRequested()
        runCurrent()

        val rerolled = sessionStore.pending.value
        assertNotNull("the pending session survives a pre-walk reroll", rerolled)
        assertEquals(original.clearings.size, rerolled!!.chain.clearings.size)
        assertNotEquals("the chain was regenerated", original.clearings, rerolled.chain.clearings)
        assertEquals("silent by design — no engine, no sonar", 0, sound.pings.size)
        assertTrue(ops.isEmpty())
    }

    // ─── Reveal whisper (R15) ────────────────────────────────────────

    @Test
    fun `revealed next plays the bowl then the whisper after the delay`() = runTest(dispatcher) {
        revealWhisper = stubWhisper
        val orchestrator = startOrchestrator()
        orchestrator.handleSeekEvent(SeekEngineEvent.RevealedNext(activeIndex = 1))
        runCurrent()

        assertEquals(1, sound.bowlCount)
        assertTrue("the whisper waits for the bowl to ring", playedWhispers.isEmpty())
        advanceTimeBy(2_501)
        runCurrent()
        assertEquals(listOf("test-whisper"), playedWhispers.map { it.id })
    }

    @Test
    fun `no downloaded whisper leaves the reveal bowl-only`() = runTest(dispatcher) {
        revealWhisper = null
        val orchestrator = startOrchestrator()
        orchestrator.handleSeekEvent(SeekEngineEvent.RevealedNext(activeIndex = 1))
        advanceTimeBy(2_501)
        runCurrent()

        assertEquals("the ritual proceeds without a whisper", 1, sound.bowlCount)
        assertTrue(playedWhispers.isEmpty())
    }

    @Test
    fun `master sounds off suppresses the reveal whisper`() = runTest(dispatcher) {
        soundsPrefs = FakeSoundsPreferencesRepository(initialSoundsEnabled = false)
        revealWhisper = stubWhisper
        val orchestrator = startOrchestrator()
        orchestrator.handleSeekEvent(SeekEngineEvent.RevealedNext(activeIndex = 1))
        advanceTimeBy(2_501)
        runCurrent()

        assertTrue("master Sounds off suppresses the reveal whisper", playedWhispers.isEmpty())
    }

    @Test
    fun `seek complete plays the completion bowl without a whisper`() = runTest(dispatcher) {
        revealWhisper = stubWhisper
        val orchestrator = startOrchestrator()
        orchestrator.handleSeekEvent(SeekEngineEvent.SeekComplete)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, sound.completionBowlCount)
        assertTrue("the final bowl closes the seeking quietly", playedWhispers.isEmpty())
    }

    // ─── Defensive routing (Stage 5-D) ───────────────────────────────

    @Test
    fun `a throwing whisper routee does not kill event routing`() = runTest(dispatcher) {
        revealWhisper = stubWhisper
        whisperThrows = true
        val chain = chain(2)
        val orchestrator = startOrchestrator()
        startSeekWalk(chain)
        emitFix(home)

        driveArrival(chain.clearings[0].center)
        driveStillnessReveal(chain.clearings[0].center)
        advanceTimeBy(2_501)
        runCurrent()
        assertTrue(playedWhispers.isEmpty())

        // The observer survives: the next clearing still routes pulses
        // and arrival persistence.
        emitFix(home)
        val distance = SeekChainGenerator.distance(home, chain.clearings[1].center)
        val pingsBefore = sound.pings.size
        advanceTimeBy(SeekEngine.pulseIntervalMillis(distance, SeekPowerTier.NORMAL) + 1)
        runCurrent()
        assertEquals(pingsBefore + 1, sound.pings.size)
        driveArrival(chain.clearings[1].center)
        assertEquals(2, countSeekArrivalEvents())
    }

    // ─── Teardown ────────────────────────────────────────────────────

    @Test
    fun `walk end tears down the engine, sound, fog, and pending whisper`() = runTest(dispatcher) {
        revealWhisper = stubWhisper
        val chain = chain(2)
        val orchestrator = startOrchestrator()
        val walkId = startSeekWalk(chain)
        emitFix(home)
        driveArrival(chain.clearings[0].center)
        driveStillnessReveal(chain.clearings[0].center)
        assertEquals(1, sound.bowlCount)

        // Finish BEFORE the 2.5 s whisper window: teardown cancels it.
        walkState.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, mode = WalkMode.Seek),
            endedAt = nowMs,
        )
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("teardown cancels the pending reveal whisper", playedWhispers.isEmpty())
        assertTrue(sound.stopCount >= 1)
        assertNull(orchestrator.fogState.value)
        assertEquals(SeekPulseVisual.NONE, orchestrator.pulse.value)

        // No seek events may flow after stop.
        val pingsAfterStop = sound.pings.size
        emitFix(home)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(pingsAfterStop, sound.pings.size)
    }

    // ─── Fake renderer style (B7) ────────────────────────────────────

    private class RecordingFogStyle : SeekFogStyle {
        val installed = mutableSetOf<String>()
        private val haloById = mutableMapOf<String, Boolean>()
        val opacities = mutableMapOf<String, Double>()

        fun installedAsHalo(id: String): Boolean = haloById[id] == true

        override fun isStyleLoaded(): Boolean = true
        override fun fogLayerExists(layerId: String): Boolean = layerId in installed
        override fun installFogCircle(
            circle: SeekFogState.FogCircle,
            tintHex: String?,
            transitionMillis: Long,
        ) {
            installed += circle.id
            haloById[circle.id] = circle.isHalo
            opacities[circle.id] = org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
                .opacity(circle.opacityBucket, circle.isHalo)
        }
        override fun setFogOpacity(layerId: String, opacity: Double) {
            opacities[layerId] = opacity
        }
        override fun removeFogCircle(layerId: String) {
            installed -= layerId
        }
        override fun firePulseRing(lightColorArgb: Int) = Unit
        override fun removePulseRing() = Unit
    }
}
