// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.PROMPT
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.RECORDING
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.WHISPER
import org.walktalkmeditate.pilgrim.walk.honor.HonorExternalGates

/**
 * `:tracker`'s record of the UI's gates (plan U18, and the whisper gate of
 * plan U25): Binder death links, sequence ids, and the hold after a
 * pipeline start, until the UI answers or the fixed wait passes. The wait
 * is virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiAudioGateTest {

    private fun TestScope.gate() = UiAudioGate(backgroundScope, UiAudioGate.REFRESH_WAIT_MILLIS)

    /** A model the UI has already answered: every gate open. */
    private fun TestScope.answeredGate() = gate().apply {
        apply(ended(PROMPT, seq = 1))
        apply(ended(RECORDING, seq = 1))
        apply(ended(WHISPER, seq = 1))
    }

    @Test
    fun `a recording gate holds the Way's voices`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val log = audioLog()
        val arbiter = WalkAudioArbiter(
            FakeWayVoicePlayer(log),
            FakeWayVoiceSoundscape(log),
            AudibleWhisperPlayer(log),
            gate,
            backgroundScope,
        )

        gate.apply(started(RECORDING, seq = 2, token = FakeUiBinder()))

        assertEquals(UiAudioGates(prompt = false, recording = true), gate.gates.value)
        assertEquals(
            "the engine's recording gate closes, so no voice starts",
            HonorExternalGates(recording = true, externalAudio = false),
            arbiter.gates.value,
        )
    }

    @Test
    fun `an ended gate is cleared, and lets go of its Binder`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val token = FakeUiBinder()
        gate.apply(started(PROMPT, seq = 2, token = token))
        assertEquals(1, token.linked)

        gate.apply(ended(PROMPT, seq = 3))

        assertFalse(gate.gates.value.prompt)
        assertEquals(0, token.linked)
    }

    @Test
    fun `the UI process dying clears the gate`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val token = FakeUiBinder()
        gate.apply(started(RECORDING, seq = 2, token = token))
        assertTrue(gate.gates.value.recording)

        token.die()

        assertFalse(gate.gates.value.recording)
    }

    @Test
    fun `a replayed start whose Binder is already dead is cleared at link time`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val live = FakeUiBinder()
        gate.apply(started(PROMPT, seq = 2, token = live))

        gate.apply(started(PROMPT, seq = 3, token = FakeUiBinder(dead = true)))

        assertFalse(gate.gates.value.prompt)
        assertEquals("the gate no longer watches the earlier Binder", 0, live.linked)
    }

    @Test
    fun `a start with no Binder can't be watched, so it clears the gate`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()

        gate.apply(started(PROMPT, seq = 2, token = null))

        assertFalse(gate.gates.value.prompt)
    }

    @Test
    fun `a stale sequence id is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        gate.apply(started(RECORDING, seq = 50, token = FakeUiBinder()))

        gate.apply(ended(RECORDING, seq = 49))
        gate.apply(ended(RECORDING, seq = 50))
        assertTrue("an end numbered at or below the start changes nothing", gate.gates.value.recording)

        gate.apply(ended(RECORDING, seq = 51))
        assertFalse(gate.gates.value.recording)
    }

    @Test
    fun `a Binder that dies after a newer start replaced it changes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val first = FakeUiBinder()
        gate.apply(started(PROMPT, seq = 2, token = first))
        gate.apply(started(PROMPT, seq = 3, token = FakeUiBinder()))

        first.die()

        assertTrue(gate.gates.value.prompt)
    }

    @Test
    fun `a whisper the UI plays holds only the whisper gate, until it ends or its process dies`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = answeredGate()
            val token = FakeUiBinder()

            gate.apply(started(WHISPER, seq = 2, token = token))
            assertEquals(UiAudioGates(whisper = true), gate.gates.value)

            token.die()
            assertEquals(UiAudioGates(), gate.gates.value)

            gate.apply(started(WHISPER, seq = 3, token = FakeUiBinder()))
            gate.apply(ended(WHISPER, seq = 4))
            assertEquals(UiAudioGates(), gate.gates.value)
        }

    // The hold after a pipeline start, which the UI answers for the bumped generation

    @Test
    fun `a hold stays unanswered until the UI has answered every gate`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        assertFalse(gate.unanswered.value)

        gate.holdUntilRefreshed()
        assertTrue(gate.unanswered.value)
        assertFalse("an unknown whisper reads open: the sonar waits on the answer instead", gate.gates.value.whisper)

        gate.apply(ended(PROMPT, seq = 2))
        gate.apply(ended(RECORDING, seq = 2))
        assertTrue("the whisper gate is still unknown", gate.unanswered.value)

        gate.apply(started(WHISPER, seq = 2, token = FakeUiBinder()))
        assertFalse(gate.unanswered.value)
        assertTrue(gate.gates.value.whisper)
    }

    @Test
    fun `with no UI to answer, the hold is answered once the fixed wait passes`() = runTest {
        val gate = gate()
        runCurrent()

        advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS - 1)
        runCurrent()
        assertTrue(gate.unanswered.value)

        advanceTimeBy(2)
        runCurrent()
        assertFalse(gate.unanswered.value)
    }

    @Test
    fun `a fresh process holds both gates until the UI answers each`() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate()
        assertEquals(UiAudioGates(prompt = true, recording = true), gate.gates.value)

        gate.apply(ended(PROMPT, seq = 1))
        assertEquals(UiAudioGates(prompt = false, recording = true), gate.gates.value)

        gate.apply(started(RECORDING, seq = 2, token = FakeUiBinder()))
        assertEquals(UiAudioGates(prompt = false, recording = true), gate.gates.value)
    }

    @Test
    fun `a pipeline restart holds both gates until the UI re-sends them`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        val token = FakeUiBinder()
        gate.apply(started(RECORDING, seq = 2, token = token))

        gate.holdUntilRefreshed()
        assertEquals(UiAudioGates(prompt = true, recording = true), gate.gates.value)
        assertEquals("what the gate knew is stale, its Binder dropped", 0, token.linked)

        gate.apply(ended(PROMPT, seq = 3))
        gate.apply(ended(RECORDING, seq = 4))
        assertEquals(UiAudioGates(), gate.gates.value)
    }

    @Test
    fun `with no UI to answer, the gates open once the fixed wait passes`() = runTest {
        val gate = gate()
        runCurrent()

        advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS - 1)
        runCurrent()
        assertEquals(UiAudioGates(prompt = true, recording = true), gate.gates.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(UiAudioGates(), gate.gates.value)
    }

    @Test
    fun `the fixed wait leaves a gate the UI already answered as it said`() = runTest {
        val gate = gate()
        runCurrent()
        gate.apply(started(RECORDING, seq = 1, token = FakeUiBinder()))

        advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS + 1)
        runCurrent()

        assertEquals(UiAudioGates(prompt = false, recording = true), gate.gates.value)
    }

    @Test
    fun `a later restart starts the wait over`() = runTest {
        val gate = gate()
        runCurrent()
        advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS - 100)
        gate.holdUntilRefreshed()
        runCurrent()

        advanceTimeBy(200)
        runCurrent()
        assertTrue("the first wait no longer opens the second hold", gate.gates.value.prompt)

        advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS)
        runCurrent()
        assertEquals(UiAudioGates(), gate.gates.value)
    }

    // A take that starts behind a prompt

    @Test
    fun `a take that starts behind a prompt is reported with the prompt's end, in one value`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = answeredGate()
            val seen = mutableListOf<UiAudioGates>()
            backgroundScope.launch { gate.gates.collect { seen += it } }
            gate.apply(started(PROMPT, seq = 2, token = FakeUiBinder()))

            gate.apply(started(RECORDING, seq = 3, token = FakeUiBinder()))
            assertEquals(
                "the prompt already holds everything",
                UiAudioGates(prompt = true, recording = false),
                gate.gates.value,
            )

            gate.apply(ended(PROMPT, seq = 4))
            assertEquals(
                listOf(UiAudioGates(), UiAudioGates(prompt = true), UiAudioGates(recording = true)),
                seen,
            )
        }

    @Test
    fun `a take the UI re-sends during the hold stays held behind a re-sent prompt`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = gate()

            gate.apply(started(PROMPT, seq = 1, token = FakeUiBinder()))
            gate.apply(started(RECORDING, seq = 2, token = FakeUiBinder()))

            assertEquals(UiAudioGates(prompt = true, recording = true), gate.gates.value)
        }

    @Test
    fun `a take that ends behind the prompt is never reported`() = runTest(UnconfinedTestDispatcher()) {
        val gate = answeredGate()
        gate.apply(started(PROMPT, seq = 2, token = FakeUiBinder()))
        gate.apply(started(RECORDING, seq = 3, token = FakeUiBinder()))
        gate.apply(ended(RECORDING, seq = 4))

        gate.apply(ended(PROMPT, seq = 5))

        assertEquals(UiAudioGates(), gate.gates.value)
    }
}
