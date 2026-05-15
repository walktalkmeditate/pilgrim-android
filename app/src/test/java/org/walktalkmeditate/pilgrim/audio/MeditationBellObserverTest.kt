// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.walk.BellTrigger

/**
 * Unit tests for [MeditationBellObserver] using a counting
 * [FakeBellPlayer] + `MutableSharedFlow<BellTrigger>`. Covers:
 *
 *  - Every trigger rings one bell when prefs allow it.
 *  - `null` bell-id (user picked "None") suppresses the corresponding trigger.
 *  - Master sounds toggle gates every trigger.
 *  - Late-subscriber semantics: the SharedFlow does not replay, so a
 *    cold-start subscription never hears past emissions (no spurious
 *    "welcome back" bells on resume).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeditationBellObserverTest {

    @Test fun `WalkStart trigger fires one bell`() = runTest {
        val s = newScenario()
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.WalkStart)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `WalkEnd trigger fires one bell`() = runTest {
        val s = newScenario()
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.WalkEnd)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `MeditationStart trigger fires one bell`() = runTest {
        val s = newScenario()
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.MeditationStart)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `MeditationEnd trigger fires one bell`() = runTest {
        val s = newScenario()
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.MeditationEnd)
        advanceUntilIdle()
        assertEquals(1, s.player.playCount)
        s.cancel()
    }

    @Test fun `meditation boundary bell requests haptic`() = runTest {
        val s = newScenario()
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.MeditationStart)
        advanceUntilIdle()
        assertEquals(listOf(true), s.player.hapticCalls)
        s.cancel()
    }

    @Test fun `master toggle off suppresses every trigger`() = runTest {
        val s = newScenario(
            soundsPreferences = FakeSoundsPreferencesRepository(initialSoundsEnabled = false),
        )
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.WalkStart)
        s.triggers.tryEmit(BellTrigger.MeditationStart)
        s.triggers.tryEmit(BellTrigger.MeditationEnd)
        s.triggers.tryEmit(BellTrigger.WalkEnd)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `WalkStart None suppresses bell`() = runTest {
        val s = newScenario(
            soundsPreferences = FakeSoundsPreferencesRepository(
                initialSoundsEnabled = true,
                initialWalkStartBellId = null,
            ),
        )
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.WalkStart)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `WalkEnd None suppresses bell`() = runTest {
        val s = newScenario(
            soundsPreferences = FakeSoundsPreferencesRepository(
                initialSoundsEnabled = true,
                initialWalkEndBellId = null,
            ),
        )
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.WalkEnd)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `MeditationStart None suppresses bell`() = runTest {
        val s = newScenario(
            soundsPreferences = FakeSoundsPreferencesRepository(
                initialSoundsEnabled = true,
                initialMeditationStartBellId = null,
            ),
        )
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.MeditationStart)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `MeditationEnd None suppresses bell`() = runTest {
        val s = newScenario(
            soundsPreferences = FakeSoundsPreferencesRepository(
                initialSoundsEnabled = true,
                initialMeditationEndBellId = null,
            ),
        )
        advanceUntilIdle()
        s.triggers.tryEmit(BellTrigger.MeditationEnd)
        advanceUntilIdle()
        assertEquals(0, s.player.playCount)
        s.cancel()
    }

    @Test fun `late subscription does NOT replay past triggers`() = runTest {
        // SharedFlow with replay=0 is the restore-path guarantee: an
        // observer instantiated AFTER an emission missed it. The
        // controller's restoreActiveWalk() writes directly to _state
        // and does NOT emit a trigger, but this test also pins the
        // SharedFlow's replay semantics in case someone bumps replay
        // > 0 in a future refactor.
        val triggers = MutableSharedFlow<BellTrigger>(replay = 0, extraBufferCapacity = 4)
        // Pre-emit BEFORE the observer subscribes.
        triggers.tryEmit(BellTrigger.MeditationStart)
        val player = FakeBellPlayer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        MeditationBellObserver(
            bellTriggers = triggers,
            bellPlayer = player,
            soundsPreferences = FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            scope = scope,
        )
        advanceUntilIdle()
        assertEquals(0, player.playCount)
        scope.coroutineContext[Job]?.cancel()
    }

    // ----- scaffolding ----------------------------------------------

    private class Scenario(
        val triggers: MutableSharedFlow<BellTrigger>,
        val player: FakeBellPlayer,
        val scope: CoroutineScope,
    ) {
        fun cancel() {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun TestScope.newScenario(
        soundsPreferences: SoundsPreferencesRepository =
            FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
    ): Scenario {
        val triggers = MutableSharedFlow<BellTrigger>(replay = 0, extraBufferCapacity = 4)
        val fakePlayer = FakeBellPlayer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        MeditationBellObserver(
            bellTriggers = triggers,
            bellPlayer = fakePlayer,
            soundsPreferences = soundsPreferences,
            scope = scope,
        )
        return Scenario(triggers, fakePlayer, scope)
    }
}

/**
 * Counts [play] calls and captures the [withHaptic] flag passed by the
 * call site. The 2-arg overload is what the observer uses; that's the
 * one to override directly to preserve the haptic-flag assertions.
 */
private class FakeBellPlayer : BellPlaying {
    var playCount = 0
    val hapticCalls = mutableListOf<Boolean>()

    override fun play() {
        playCount += 1
    }

    override fun play(scale: Float) {
        playCount += 1
    }

    override fun play(scale: Float, withHaptic: Boolean) {
        playCount += 1
        hapticCalls.add(withHaptic)
    }
}
