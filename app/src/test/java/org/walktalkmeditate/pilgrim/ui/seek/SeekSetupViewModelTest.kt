// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.seek.FakeSeekPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereStore
import org.walktalkmeditate.pilgrim.walk.BellTrigger
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore

/**
 * Ports the invariants of iOS `SeekSetupFlowTests.swift@c1745e8` onto
 * the Android stage machine, plus the Android-shaped GPS-lock hold
 * (virtual-time 20 s timeout, ≤50 m first-fix chain generation into
 * [SeekSessionStore]) and the hemisphere-corrected celestial tint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Harness --------------------------------------------------------

    private class FakeLocationSource : LocationSource {
        val fixes = MutableSharedFlow<LocationPoint>()
        override fun locationFlow(): Flow<LocationPoint> = fixes
        override suspend fun lastKnownLocation(): LocationPoint? = null
    }

    private class FakeHemisphereStore(initial: Hemisphere) : HemisphereStore {
        override val hemisphere: StateFlow<Hemisphere> = MutableStateFlow(initial)
        override suspend fun setOverride(hemisphere: Hemisphere) = Unit
    }

    private class FakeWalkController(
        initialState: WalkState = WalkState.Idle,
    ) : WalkController {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<WalkState> = mutableState
        override val bellTriggers: SharedFlow<BellTrigger> = MutableSharedFlow()
        override val liveSteps: StateFlow<Int?> = MutableStateFlow(null)
        override suspend fun startWalk(intention: String?, mode: WalkMode): Walk =
            error("not used by SeekSetupViewModel")
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

    private class Harness(
        val seekPrefs: FakeSeekPreferencesRepository = FakeSeekPreferencesRepository(),
        val practice: FakePracticePreferencesRepository = FakePracticePreferencesRepository(),
        hemisphere: Hemisphere = Hemisphere.Northern,
        val location: FakeLocationSource = FakeLocationSource(),
        val store: SeekSessionStore = SeekSessionStore(),
        val controller: FakeWalkController = FakeWalkController(),
        var nowMs: Long = ORDINARY_DAY_MS,
        hasPreciseLocation: Boolean = true,
    ) {
        var preciseLocation = hasPreciseLocation
        var breathCount = 0
        val vm = SeekSetupViewModel(
            seekPreferences = seekPrefs,
            practicePreferences = practice,
            hemisphereStore = FakeHemisphereStore(hemisphere),
            locationSource = location,
            sessionStore = store,
            walkController = controller,
            clock = Clock { nowMs },
            accuracyChecker = { preciseLocation },
            breathHaptic = { breathCount++ },
        )
    }

    private fun fix(accuracy: Float?): LocationPoint = LocationPoint(
        timestamp = 1L,
        latitude = 35.0116,
        longitude = 135.7681,
        horizontalAccuracyMeters = accuracy,
    )

    /** Walk h through accuracy + duration + intention into Transition. */
    private fun Harness.reachTransition(intention: String? = "find the river") {
        vm.beginSetup(WalkMode.Seek)
        vm.advanceDuration(30)
        vm.advanceIntentionSet(intention)
        assertEquals(SeekSetupStage.Transition, vm.stage.value)
    }

    // ---- Wander unchanged (SeekSetupFlowTests.swift:45-73) --------------

    @Test
    fun `wander is born ready and no stage ever engages`() = runTest(dispatcher) {
        val h = Harness()
        assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
        h.vm.beginSetup(WalkMode.Wander)
        assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
        h.vm.advanceDuration(30)
        assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
        assertNull(h.vm.durationMinutes)
        h.vm.advanceIntentionSet("anything")
        h.vm.advanceTransitionComplete()
        h.vm.cancelSetup()
        assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
    }

    @Test
    fun `wander duration advance does not touch preferences`() = runTest(dispatcher) {
        val h = Harness()
        h.vm.beginSetup(WalkMode.Wander)
        h.vm.advanceDuration(120)
        runCurrent()
        assertEquals(60, h.seekPrefs.lastDurationMinutes.value)
        assertFalse(h.seekPrefs.safetyShown.value)
    }

    @Test
    fun `wander never shows the safety caption`() = runTest(dispatcher) {
        val h = Harness()
        h.vm.beginSetup(WalkMode.Wander)
        assertFalse(h.vm.showsSafetyCaption)
    }

    // ---- Stage sequence (SeekSetupFlowTests.swift:77-107) ---------------

    @Test
    fun `seek requires duration then intention then transition then ready`() =
        runTest(dispatcher) {
            val h = Harness()
            h.vm.beginSetup(WalkMode.Seek)
            assertEquals(SeekSetupStage.DurationQuestion, h.vm.stage.value)

            h.vm.advanceIntentionSet("early")
            assertEquals(SeekSetupStage.DurationQuestion, h.vm.stage.value)
            h.vm.advanceTransitionComplete()
            assertEquals(SeekSetupStage.DurationQuestion, h.vm.stage.value)

            h.vm.advanceDuration(30)
            assertEquals(SeekSetupStage.Intention, h.vm.stage.value)

            h.vm.advanceTransitionComplete()
            assertEquals(SeekSetupStage.Intention, h.vm.stage.value)

            h.vm.advanceIntentionSet("find the river")
            assertEquals(SeekSetupStage.Transition, h.vm.stage.value)

            h.vm.advanceTransitionComplete()
            assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
        }

    @Test
    fun `duration cannot be set before accuracy resolves`() = runTest(dispatcher) {
        val h = Harness(hasPreciseLocation = false)
        h.vm.beginSetup(WalkMode.Seek)
        assertEquals(SeekSetupStage.VerifyingAccuracy, h.vm.stage.value)
        h.vm.advanceDuration(60)
        assertEquals(SeekSetupStage.VerifyingAccuracy, h.vm.stage.value)
        assertNull(h.vm.durationMinutes)
    }

    @Test
    fun `full ladder lands in ready with a generated chain in the session store`() =
        runTest(dispatcher) {
            val h = Harness()
            h.reachTransition()
            runCurrent()
            assertNull(h.store.pending.value)

            h.location.fixes.emit(fix(accuracy = 10f))
            runCurrent()

            val session = h.store.pending.value
            assertNotNull("chain must be locked on the first accurate fix", session)
            assertTrue(session!!.chain.clearings.isNotEmpty())
            assertEquals(30, session.durationMinutes)
            assertEquals(h.nowMs, session.seededAtEpochMillis)

            h.vm.advanceTransitionComplete()
            assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
            h.vm.cancelSetup()
            assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
            assertNotNull(h.store.pending.value)
        }

    // ---- Accuracy gate (SeekSetupFlowTests.swift:156-187) ---------------

    @Test
    fun `precise location skips the upgrade request`() = runTest(dispatcher) {
        val h = Harness(hasPreciseLocation = true)
        val requests = mutableListOf<Unit>()
        val job = launch { h.vm.accuracyUpgradeRequests.collect { requests += it } }
        runCurrent()
        h.vm.beginSetup(WalkMode.Seek)
        runCurrent()
        assertEquals(SeekSetupStage.DurationQuestion, h.vm.stage.value)
        assertEquals(0, requests.size)
        job.cancel()
    }

    @Test
    fun `coarse-only grant asks for the upgrade and proceeds when granted`() =
        runTest(dispatcher) {
            val h = Harness(hasPreciseLocation = false)
            val requests = mutableListOf<Unit>()
            val job = launch { h.vm.accuracyUpgradeRequests.collect { requests += it } }
            runCurrent()
            h.vm.beginSetup(WalkMode.Seek)
            runCurrent()
            assertEquals(SeekSetupStage.VerifyingAccuracy, h.vm.stage.value)
            assertEquals(1, requests.size)

            h.vm.onAccuracyResult(granted = true)
            assertEquals(SeekSetupStage.DurationQuestion, h.vm.stage.value)
            job.cancel()
        }

    @Test
    fun `accuracy declined cancels with AccuracyDeclined and never locks a chain`() =
        runTest(dispatcher) {
            val h = Harness(hasPreciseLocation = false)
            h.vm.beginSetup(WalkMode.Seek)
            h.vm.onAccuracyResult(granted = false)
            assertEquals(
                SeekSetupStage.Cancelled(SeekSetupCancelReason.AccuracyDeclined),
                h.vm.stage.value,
            )
            // A late fix (nothing armed) must not resurrect anything.
            h.location.fixes.emit(fix(accuracy = 5f))
            runCurrent()
            assertNull(h.store.pending.value)
        }

    @Test
    fun `cancelled seek does not resume on repeat begin`() = runTest(dispatcher) {
        val h = Harness(hasPreciseLocation = false)
        val requests = mutableListOf<Unit>()
        val job = launch { h.vm.accuracyUpgradeRequests.collect { requests += it } }
        runCurrent()
        h.vm.beginSetup(WalkMode.Seek)
        h.vm.onAccuracyResult(granted = false)
        h.vm.beginSetup(WalkMode.Seek)
        runCurrent()
        assertEquals(
            SeekSetupStage.Cancelled(SeekSetupCancelReason.AccuracyDeclined),
            h.vm.stage.value,
        )
        assertEquals(1, requests.size)
        job.cancel()
    }

    @Test
    fun `accuracy result arriving after a user cancel is dropped`() = runTest(dispatcher) {
        val h = Harness(hasPreciseLocation = false)
        h.vm.beginSetup(WalkMode.Seek)
        h.vm.cancelSetup()
        h.vm.onAccuracyResult(granted = true)
        assertEquals(
            SeekSetupStage.Cancelled(SeekSetupCancelReason.UserDismissed),
            h.vm.stage.value,
        )
    }

    // ---- User cancel (SeekSetupFlowTests.swift:191-217) -----------------

    @Test
    fun `duration sheet dismissed cancels with UserDismissed and stays cancelled`() =
        runTest(dispatcher) {
            val h = Harness()
            h.vm.beginSetup(WalkMode.Seek)
            h.vm.cancelSetup()
            assertEquals(
                SeekSetupStage.Cancelled(SeekSetupCancelReason.UserDismissed),
                h.vm.stage.value,
            )
            h.vm.advanceDuration(30)
            assertEquals(
                SeekSetupStage.Cancelled(SeekSetupCancelReason.UserDismissed),
                h.vm.stage.value,
            )
        }

    @Test
    fun `user cancel does not overwrite an accuracy decline`() = runTest(dispatcher) {
        val h = Harness(hasPreciseLocation = false)
        h.vm.beginSetup(WalkMode.Seek)
        h.vm.onAccuracyResult(granted = false)
        h.vm.cancelSetup()
        assertEquals(
            SeekSetupStage.Cancelled(SeekSetupCancelReason.AccuracyDeclined),
            h.vm.stage.value,
        )
    }

    @Test
    fun `cancel during transition kills the armed fix collection`() = runTest(dispatcher) {
        val h = Harness()
        h.reachTransition()
        runCurrent()
        h.vm.cancelSetup()
        assertEquals(
            SeekSetupStage.Cancelled(SeekSetupCancelReason.UserDismissed),
            h.vm.stage.value,
        )
        h.location.fixes.emit(fix(accuracy = 5f))
        runCurrent()
        assertNull("a fix after cancel must never lock a chain", h.store.pending.value)
    }

    // ---- GPS lock hold ---------------------------------------------------

    @Test
    fun `no accurate fix within 20s during transition cancels with GpsTimeout`() =
        runTest(dispatcher) {
            val h = Harness()
            h.reachTransition()
            runCurrent()

            advanceTimeBy(SeekSetupViewModel.GPS_LOCK_TIMEOUT_MS - 1)
            runCurrent()
            assertEquals(SeekSetupStage.Transition, h.vm.stage.value)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                SeekSetupStage.Cancelled(SeekSetupCancelReason.GpsTimeout),
                h.vm.stage.value,
            )
            assertNull(h.store.pending.value)

            // The timeout bumped the generation — a late accurate fix
            // must never lock a chain into a cancelled walk.
            h.location.fixes.emit(fix(accuracy = 5f))
            runCurrent()
            assertNull(h.store.pending.value)
        }

    @Test
    fun `timeout after ready stays silent and a late fix locks the chain quietly`() =
        runTest(dispatcher) {
            val h = Harness()
            h.reachTransition()
            runCurrent()
            h.vm.advanceTransitionComplete()
            assertEquals(SeekSetupStage.Ready, h.vm.stage.value)

            advanceTimeBy(SeekSetupViewModel.GPS_LOCK_TIMEOUT_MS)
            runCurrent()
            assertEquals("late timeout must stay silent", SeekSetupStage.Ready, h.vm.stage.value)
            assertNull(h.store.pending.value)

            advanceTimeBy(1_000)
            h.location.fixes.emit(fix(accuracy = 12f))
            runCurrent()
            assertEquals(SeekSetupStage.Ready, h.vm.stage.value)
            assertNotNull("fix at 21s must lock the chain silently", h.store.pending.value)
        }

    @Test
    fun `inaccurate and accuracy-less fixes never lock the chain`() = runTest(dispatcher) {
        val h = Harness()
        h.reachTransition()
        runCurrent()

        h.location.fixes.emit(fix(accuracy = 51f))
        h.location.fixes.emit(fix(accuracy = null))
        h.location.fixes.emit(fix(accuracy = -1f))
        runCurrent()
        assertNull(h.store.pending.value)

        // Exactly 50 m qualifies (iOS `hAcc <= 50`).
        h.location.fixes.emit(fix(accuracy = 50f))
        runCurrent()
        assertNotNull(h.store.pending.value)
    }

    @Test
    fun `chains from the same inputs differ across setups (entropy in the seed)`() =
        runTest(dispatcher) {
            // SeekSeed folds OS entropy in — a repeated question never
            // repeats a way. Two identical setups must not produce
            // identical clearings (probability of collision ~2^-64).
            val first = Harness()
            first.reachTransition()
            runCurrent()
            first.location.fixes.emit(fix(accuracy = 10f))
            runCurrent()

            val second = Harness()
            second.reachTransition()
            runCurrent()
            second.location.fixes.emit(fix(accuracy = 10f))
            runCurrent()

            val a = first.store.pending.value!!.chain
            val b = second.store.pending.value!!.chain
            assertFalse(a.clearings == b.clearings)
        }

    // ---- Duration persistence (SeekSetupFlowTests.swift:111-134) --------

    @Test
    fun `duration selection persists to preferences and flips safetyShown on Begin`() =
        runTest(dispatcher) {
            val h = Harness()
            h.vm.beginSetup(WalkMode.Seek)
            assertTrue(h.vm.showsSafetyCaption)

            h.vm.rememberDurationSelection(120)
            runCurrent()
            assertEquals(120, h.seekPrefs.lastDurationMinutes.value)
            assertFalse("tap-persist must not flip safetyShown", h.seekPrefs.safetyShown.value)

            h.vm.advanceDuration(120)
            runCurrent()
            assertEquals(120, h.vm.durationMinutes)
            assertEquals(120, h.seekPrefs.lastDurationMinutes.value)
            assertTrue(h.seekPrefs.safetyShown.value)
        }

    @Test
    fun `safety caption shows on the first seek only`() = runTest(dispatcher) {
        val prefs = FakeSeekPreferencesRepository()
        val first = Harness(seekPrefs = prefs)
        first.vm.beginSetup(WalkMode.Seek)
        assertTrue(first.vm.showsSafetyCaption)
        first.vm.advanceDuration(60)
        runCurrent()
        assertTrue(prefs.safetyShown.value)

        val second = Harness(seekPrefs = prefs)
        second.vm.beginSetup(WalkMode.Seek)
        assertFalse(second.vm.showsSafetyCaption)
    }

    @Test
    fun `preselection snaps deterministically to the closest preset`() {
        // Tie 45 → FIRST minimum wins (Swift min(by:) strict-< parity).
        assertEquals(30, preselectedSeekMinutes(45))
        assertEquals(60, preselectedSeekMinutes(50))
        assertEquals(180, preselectedSeekMinutes(500))
        assertEquals(30, preselectedSeekMinutes(0))
        assertEquals(60, preselectedSeekMinutes(60))
        assertEquals(180, preselectedSeekMinutes(180))
    }

    // ---- Celestial tint (spec B8, hemisphere-corrected D4) ---------------

    @Test
    fun `tint is null when celestial awareness is off even on a solstice`() =
        runTest(dispatcher) {
            val h = Harness(nowMs = WINTER_SOLSTICE_2025_MS)
            h.vm.beginSetup(WalkMode.Seek)
            assertNull(h.vm.tint)
        }

    @Test
    fun `winter solstice tints the northern seek indigo`() = runTest(dispatcher) {
        val h = Harness(
            practice = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true),
            nowMs = WINTER_SOLSTICE_2025_MS,
        )
        h.vm.beginSetup(WalkMode.Seek)
        assertEquals("#2377A4", h.vm.tint?.fogHex)
    }

    @Test
    fun `the same instant tints a southern seek summer-gold (hemisphere corrected)`() =
        runTest(dispatcher) {
            val h = Harness(
                practice = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true),
                hemisphere = Hemisphere.Southern,
                nowMs = WINTER_SOLSTICE_2025_MS,
            )
            h.vm.beginSetup(WalkMode.Seek)
            assertEquals("#C9A646", h.vm.tint?.fogHex)
        }

    @Test
    fun `tint is computed once at setup and rides the locked session`() =
        runTest(dispatcher) {
            val h = Harness(
                practice = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true),
                nowMs = WINTER_SOLSTICE_2025_MS,
            )
            h.vm.beginSetup(WalkMode.Seek)
            val tintAtSetup = h.vm.tint
            assertNotNull(tintAtSetup)

            // The sky moves on; the walk's tint does not.
            h.nowMs = ORDINARY_DAY_MS
            h.vm.beginSetup(WalkMode.Seek)
            assertEquals(tintAtSetup, h.vm.tint)

            h.vm.advanceDuration(30)
            h.vm.advanceIntentionSet(null)
            runCurrent()
            h.location.fixes.emit(fix(accuracy = 10f))
            runCurrent()
            assertEquals(tintAtSetup, h.store.pending.value?.tint)
        }

    // ---- Gateway haptic + disposal ---------------------------------------

    @Test
    fun `fireGatewayBreath reaches the haptic seam`() = runTest(dispatcher) {
        val h = Harness()
        h.vm.fireGatewayBreath()
        assertEquals(1, h.breathCount)
    }

    @Test
    fun `disposal clears an unconsumed pending session but keeps a live walk's`() =
        runTest(dispatcher) {
            val h = Harness()
            h.reachTransition()
            runCurrent()
            h.location.fixes.emit(fix(accuracy = 10f))
            runCurrent()
            assertNotNull(h.store.pending.value)

            // Walk in progress → the orchestrator still needs the session.
            h.controller.mutableState.value =
                WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L, mode = WalkMode.Seek))
            h.vm.clearPendingSessionIfUnconsumed()
            assertNotNull(h.store.pending.value)

            // Pre-walk back-out → nothing will ever consume it.
            h.controller.mutableState.value = WalkState.Idle
            h.vm.clearPendingSessionIfUnconsumed()
            assertNull(h.store.pending.value)
        }

    @Test
    fun `a fresh seek setup clears a stale pending session`() = runTest(dispatcher) {
        val store = SeekSessionStore()
        val first = Harness(store = store)
        first.reachTransition()
        runCurrent()
        first.location.fixes.emit(fix(accuracy = 10f))
        runCurrent()
        assertNotNull(store.pending.value)

        val second = Harness(store = store)
        second.vm.beginSetup(WalkMode.Seek)
        assertNull("stale session must not leak into a new setup", store.pending.value)
    }

    private companion object {
        /** 2025-12-21T12:00:00Z — inside the ±1.5° winter-solstice window. */
        const val WINTER_SOLSTICE_2025_MS = 1_766_318_400_000L

        /** 2026-02-20T12:00:00Z — no turning within ±1.5°, not a full moon. */
        const val ORDINARY_DAY_MS = 1_771_588_800_000L
    }
}
