// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

/**
 * AE11 across the process line, as far as Robolectric reaches: the UI's
 * publisher builds the real gate intent, the service's own decoder reads
 * it into `:tracker`'s gate, and the arbiter holds the Way voice and the
 * whispers behind the guide. Each intent is handed over as the publisher
 * sends it, before the prompt's player would be asked to start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiAudioGateEndToEndTest {

    private val walkActions = WalkActionPublisher(ApplicationProvider.getApplicationContext<Application>())
    private val log = audioLog()
    private val player = FakeWayVoicePlayer(log)
    private val soundscape = FakeWayVoiceSoundscape(log)
    private val whispers = AudibleWhisperPlayer(log)
    private val recording = MutableStateFlow(false)
    private val voiceA = File("voice-a.wav")

    private class Tracker(val gate: UiAudioGate, val arbiter: WalkAudioArbiter)

    /** What the service's gate handler does with one intent from a live UI. */
    private fun deliver(gate: UiAudioGate, intent: Intent) {
        val action = WalkTrackingService.decideUiAudioGateAction(
            honorEnabled = true,
            redelivered = false,
            pipelineActive = true,
        )
        assertEquals(WalkTrackingService.UiAudioGateAction.Apply, action)
        gate.apply(WalkTrackingService.uiAudioGateSignalFromExtras(intent)!!)
    }

    /** A walk under way: the UI has answered `:tracker`'s hold, and nothing sounds yet. */
    private fun TestScope.walk(): Pair<UiAudioGatePublisher, Tracker> {
        val gate = UiAudioGate(backgroundScope, UiAudioGate.REFRESH_WAIT_MILLIS)
        val ui = UiAudioGatePublisher(
            releaseFlags = FixedReleaseFlags(honor = true),
            recording = recording,
            refreshes = flowOf(UiAudioGateRefresh(walkId = 1, gateGeneration = 1)),
            send = { kind, held, seq, token -> deliver(gate, walkActions.uiAudioGateIntent(kind, held, seq, token)) },
            bootNanos = { 0L },
            scope = backgroundScope,
        )
        ui.start()
        val arbiter = WalkAudioArbiter(player, soundscape, whispers, gate, backgroundScope)
        assertEquals(UiAudioGates(), gate.gates.value)
        log.clear()
        return ui to Tracker(gate, arbiter)
    }

    private fun TestScope.settleRun() {
        advanceTimeBy(WalkAudioArbiter.RUN_SETTLE_MILLIS + 1)
        runCurrent()
    }

    @Test
    fun `a guide prompt sounding at a voice's spot holds the voice, which starts when the prompt ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val (ui, tracker) = walk()

            ui.onPromptLevel(true)
            tracker.arbiter.play(voiceA, gain = 1f, listener = RecordingVoiceListener())
            assertEquals("the voice waits in silence", 0, player.plays.size)

            ui.onPromptLevel(false)
            assertEquals(listOf("voice-a"), player.plays.map { it.file.nameWithoutExtension })
        }

    @Test
    fun `a prompt starting mid-voice pauses it, keeps the soundscape ducked, and resumes it in place`() =
        runTest(UnconfinedTestDispatcher()) {
            val (ui, tracker) = walk()
            tracker.arbiter.play(voiceA, 1f, RecordingVoiceListener())
            log.clear()

            ui.onPromptLevel(true)
            assertEquals("paused before the prompt's first sound", listOf("stop whisper", "pause"), log)
            assertTrue(soundscape.held)

            log.clear()
            ui.onPromptLevel(false)
            assertEquals(listOf("release 0.4", "hold 0.15", "resume"), log)
            assertTrue(soundscape.held)
            assertEquals("resumed, not restarted", 1, player.plays.size)
        }

    @Test
    fun `a whisper pending when a prompt starts is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val (ui, tracker) = walk()
        tracker.arbiter.play(voiceA, 1f, RecordingVoiceListener())
        tracker.arbiter.requestWhisper(whisper("w1"))

        ui.onPromptLevel(true)
        ui.onPromptLevel(false)
        player.plays.last().listener.onEnded()
        tracker.arbiter.restoreAfterWayVoice()
        settleRun()

        assertEquals(emptyList<String>(), whispers.played)
    }

    @Test
    fun `a whisper never plays over a guide prompt`() = runTest(UnconfinedTestDispatcher()) {
        val (ui, tracker) = walk()
        tracker.arbiter.requestWhisper(whisper("w1"))
        whispers.audible.value = true

        ui.onPromptLevel(true)
        assertFalse("the prompt cuts the whisper sounding", whispers.audible.value)
        tracker.arbiter.requestWhisper(whisper("w2"))
        assertEquals(listOf("w1"), whispers.played)

        ui.onPromptLevel(false)
        assertEquals(listOf("w1", "w2"), whispers.played)
    }

    @Test
    fun `a take started over a prompt releases the parked voice before the recording gate closes`() =
        runTest(UnconfinedTestDispatcher()) {
            val (ui, tracker) = walk()
            ui.onPromptLevel(true)
            tracker.arbiter.play(voiceA, 1f, RecordingVoiceListener())

            recording.value = true
            assertFalse("the prompt holds everything meanwhile", tracker.arbiter.gates.value.recording)
            ui.onPromptLevel(false)

            assertEquals("the parked voice starts as the prompt ends", 1, player.plays.size)
            assertTrue("then the take's gate closes, and the session pauses it", tracker.arbiter.gates.value.recording)
        }

    @Test
    fun `with the release flag off the UI sends nothing and the tracker's gates stay as they were`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = UiAudioGate(backgroundScope, UiAudioGate.REFRESH_WAIT_MILLIS)
            gate.apply(ended(UiAudioGateKind.PROMPT, seq = 1))
            gate.apply(ended(UiAudioGateKind.RECORDING, seq = 1))
            val ui = UiAudioGatePublisher(
                releaseFlags = FixedReleaseFlags(honor = false),
                recording = recording,
                refreshes = flowOf(UiAudioGateRefresh(walkId = 1, gateGeneration = 1)),
                send = { kind, held, seq, token -> deliver(gate, walkActions.uiAudioGateIntent(kind, held, seq, token)) },
                bootNanos = { 0L },
                scope = backgroundScope,
            )
            ui.start()

            ui.onPromptLevel(true)
            recording.value = true

            assertEquals(UiAudioGates(), gate.gates.value)
        }
}
