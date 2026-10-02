// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
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
import org.walktalkmeditate.pilgrim.walk.honor.HonorExternalGates

/**
 * The walk audio arbiter's rules, iOS's per-player checks (parity spec C
 * §2–§6), against fakes for the Way voice player, the soundscape, and the
 * whisper player. Every step runs inline on the test dispatcher; the run's
 * settle is virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkAudioArbiterTest {

    private val log = audioLog()
    private val player = FakeWayVoicePlayer(log)
    private val soundscape = FakeWayVoiceSoundscape(log)
    private val whispers = AudibleWhisperPlayer(log)
    private val gateSource = FakeUiAudioGates()
    private val ui = gateSource.value
    private val voiceA = File("voice-a.wav")
    private val voiceB = File("voice-b.wav")

    private fun TestScope.arbiter(scope: CoroutineScope = backgroundScope) =
        WalkAudioArbiter(player, soundscape, whispers, gateSource, scope)

    private fun TestScope.settleRun() {
        advanceTimeBy(WalkAudioArbiter.RUN_SETTLE_MILLIS + 1)
        runCurrent()
    }

    /** A natural end, then the session's restore, as its `voiceEnded` performs them. */
    private fun WalkAudioArbiter.endNaturally(play: FakeWayVoicePlayer.Play) {
        play.listener.onEnded()
        restoreAfterWayVoice()
    }

    // AE11 and the priority order

    @Test
    fun `a guide prompt sounding at a voice's spot parks the voice, which starts when the prompt ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            ui.value = UiAudioGates(prompt = true)
            log.clear()

            arbiter.play(voiceA, gain = 1f, listener = RecordingVoiceListener())
            assertEquals("a parked voice neither sounds nor ducks", emptyList<String>(), log)

            ui.value = UiAudioGates()
            assertEquals(listOf("stop whisper", "hold 0.15", "play voice-a 0.8"), log)
        }

    @Test
    fun `a prompt starting mid-voice pauses it first, keeps the soundscape ducked, and resumes it in place`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            log.clear()

            ui.value = UiAudioGates(prompt = true)
            assertEquals(listOf("stop whisper", "pause"), log)
            assertTrue("the voice hands its duck to the prompt", soundscape.held)

            log.clear()
            ui.value = UiAudioGates()
            assertEquals(
                "the guide restores, the voice takes the duck straight back, and resumes where it stopped",
                listOf("release 0.4", "hold 0.15", "resume"),
                log,
            )
            assertTrue(soundscape.held)
            assertEquals(1, player.plays.size)
        }

    @Test
    fun `a whisper arriving mid-voice waits, and plays once the run of voices ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.requestWhisper(whisper("w1"))
            assertEquals(emptyList<String>(), whispers.played)

            arbiter.endNaturally(player.plays.last())
            assertEquals("the run may still continue", emptyList<String>(), whispers.played)

            settleRun()
            assertEquals(listOf("w1"), whispers.played)
            assertFalse(soundscape.held)
        }

    @Test
    fun `a whisper held across a run of voices survives the next voice's start`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.requestWhisper(whisper("w1"))
            assertEquals("an unblocked whisper starts at once", listOf("w1"), whispers.played)
            whispers.audible.value = true

            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            assertFalse("starting a voice cuts the audible whisper", whispers.audible.value)
            arbiter.requestWhisper(whisper("w2"))

            arbiter.endNaturally(player.plays.last())
            arbiter.play(voiceB, 1f, RecordingVoiceListener())
            settleRun()
            assertEquals("voice B is now the one in flight", listOf("w1"), whispers.played)
            assertEquals("one duck for the whole run", 1, log.count { it.startsWith("hold") })

            arbiter.endNaturally(player.plays.last())
            settleRun()
            assertEquals(listOf("w1", "w2"), whispers.played)
            assertEquals(listOf("release 0.4"), log.filter { it.startsWith("release") })
        }

    @Test
    fun `a whisper parked when a prompt starts is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        arbiter.play(voiceA, 1f, RecordingVoiceListener())
        arbiter.requestWhisper(whisper("w1"))

        ui.value = UiAudioGates(prompt = true)
        ui.value = UiAudioGates()
        arbiter.stop()
        arbiter.restoreAfterWayVoice()
        settleRun()

        assertEquals(emptyList<String>(), whispers.played)
    }

    @Test
    fun `a whisper never plays over a guide prompt`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        arbiter.requestWhisper(whisper("w1"))
        whispers.audible.value = true

        ui.value = UiAudioGates(prompt = true)
        assertFalse("a prompt starting cuts the audible whisper", whispers.audible.value)
        arbiter.requestWhisper(whisper("w2"))
        assertEquals(listOf("w1"), whispers.played)

        ui.value = UiAudioGates()
        assertEquals("it plays once the prompt ends", listOf("w1", "w2"), whispers.played)
    }

    @Test
    fun `a whisper still downloading when a voice starts parks once it lands, and plays after the run`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            whispers.holdFetches = true
            arbiter.requestWhisper(whisper("w1"))

            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            whispers.landHeldFetches()
            assertEquals("iOS parks it on landing (WhisperPlayer.swift:142-156@7c200bf)", emptyList<String>(), whispers.played)

            arbiter.endNaturally(player.plays.last())
            settleRun()
            assertEquals(listOf("w1"), whispers.played)
        }

    @Test
    fun `a whisper still downloading when a prompt starts parks once it lands, and plays when the prompt ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            whispers.holdFetches = true
            arbiter.requestWhisper(whisper("w1"))

            ui.value = UiAudioGates(prompt = true)
            whispers.landHeldFetches()
            assertEquals(emptyList<String>(), whispers.played)

            ui.value = UiAudioGates()
            assertEquals(listOf("w1"), whispers.played)
        }

    @Test
    fun `when a prompt ends with a voice and a whisper parked, the voice goes first and the whisper keeps waiting`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.requestWhisper(whisper("w1"))

            ui.value = UiAudioGates()

            assertEquals(1, player.plays.size)
            assertEquals(emptyList<String>(), whispers.played)
        }

    // The duck

    @Test
    fun `the soundscape ducks as a voice starts, stays ducked through a pause, and restores after the run`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.pause()
            settleRun()
            assertTrue(soundscape.held)
            assertEquals(0.15f, soundscape.targetVolume, 0f)

            arbiter.stop()
            arbiter.restoreAfterWayVoice()
            assertTrue("the restore waits for the run to settle", soundscape.held)
            settleRun()
            assertEquals(
                listOf("hold 0.15", "play voice-a 0.8", "pause", "stop", "release 0.4"),
                log.filter { it != "stop whisper" },
            )
        }

    @Test
    fun `a soundscape started mid-voice is not ducked, and the voice's end restores the level from its start (iOS C-D4, pilgrim-ios #104)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            // What SoundscapePlayer.play does: the walker's level overwrites the duck.
            soundscape.startedAt(0.6f)

            arbiter.endNaturally(player.plays.last())
            settleRun()

            assertEquals("the stale level captured when the voice ducked", listOf("release 0.4"), log.filter { it.startsWith("release") })
        }

    // Guide hand-overs (iOS WayVoicePlayerTests B3)

    @Test
    fun `a voice that ends during the prompt leaves the guide to restore the level it inherited`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            ui.value = UiAudioGates(prompt = true)

            arbiter.stop()
            arbiter.restoreAfterWayVoice()
            settleRun()
            assertTrue("the guide owns the duck now", soundscape.held)

            ui.value = UiAudioGates()
            assertEquals(listOf("release 0.4"), log.filter { it.startsWith("release") })
            assertFalse(soundscape.held)
        }

    @Test
    fun `a pause during the prompt wins over the guide's hold`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        arbiter.play(voiceA, 1f, RecordingVoiceListener())
        ui.value = UiAudioGates(prompt = true)
        arbiter.pause()
        log.clear()

        ui.value = UiAudioGates()

        assertFalse("never resumed", "resume" in log)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `a voice paused before the prompt stays paused, and the prompt gives the soundscape back until it resumes`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.pause()
            ui.value = UiAudioGates(prompt = true)
            ui.value = UiAudioGates()
            assertFalse(player.isPlaying)
            assertFalse("iOS's guide restores on its way out", soundscape.held)

            log.clear()
            arbiter.resume()
            assertEquals(listOf("hold 0.15", "resume"), log)
        }

    @Test
    fun `a newer voice played during the prompt replaces the held one when the prompt ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val first = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, first)
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceB, 1f, RecordingVoiceListener())
            assertEquals(1, player.plays.size)

            ui.value = UiAudioGates()

            assertEquals(listOf("voice-a", "voice-b"), player.plays.map { it.file.nameWithoutExtension })
            player.plays.first().listener.onEnded()
            assertEquals("the replaced voice never reports", 0, first.ends)
        }

    // iOS defects, matched as shipped

    @Test
    fun `a voice parked behind a prompt starts when it ends, inside the sitting that paused it (iOS C-D1, pilgrim-ios #101)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceA, 1f, RecordingVoiceListener())

            // The engine's voicePause as the sitting closes its gate.
            arbiter.pause()
            ui.value = UiAudioGates()

            assertEquals(1, player.plays.size)
            assertTrue(player.isPlaying)
        }

    @Test
    fun `a recording that stops the prompt releases the parked voice before its gate closes (iOS C-D1 as corrected, pilgrim-ios #101)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            backgroundScope.launch { arbiter.gates.collect { if (it.recording) log += "gate recording" } }
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceA, 1f, RecordingVoiceListener())

            ui.value = UiAudioGates(prompt = false, recording = true)

            val play = log.indexOf("play voice-a 0.8")
            assertTrue(play >= 0)
            assertTrue("the voice starts, then the gate pauses it", play < log.indexOf("gate recording"))
        }

    @Test
    fun `a whisper parked at Finish plays after the walk's voice is torn down (iOS C-D2, pilgrim-ios #103)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.requestWhisper(whisper("w1"))

            // HonorSession.stop()'s teardown: the voice stops, the duck is given back.
            arbiter.stop()
            arbiter.restoreAfterWayVoice()
            settleRun()

            assertEquals(listOf("w1"), whispers.played)
        }

    @Test
    fun `a voice resumed during the prompt speaks over it and finishes unducked (iOS C-D3, pilgrim-ios #101)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            ui.value = UiAudioGates(prompt = true)
            arbiter.pause()
            log.clear()

            arbiter.resume()
            assertEquals("no duck while the guide owns it", listOf("resume"), log)
            assertTrue(player.isPlaying)

            ui.value = UiAudioGates()
            assertEquals(listOf("resume", "release 0.4"), log)
            assertTrue(player.isPlaying)
            assertFalse("nothing re-ducks", soundscape.held)
        }

    @Test
    fun `a gate reopening resumes a voice the walker paused (iOS C-D6, pilgrim-ios #101)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.pause()
            arbiter.pause()
            arbiter.resume()

            assertTrue(player.isPlaying)
        }

    @Test
    fun `a parked voice a newer one supersedes never plays and never reports (iOS C-D7, pilgrim-ios #106)`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val superseded = RecordingVoiceListener()
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceA, 1f, superseded)
            arbiter.play(voiceB, 1f, RecordingVoiceListener())

            ui.value = UiAudioGates()

            assertEquals(listOf("voice-b"), player.plays.map { it.file.nameWithoutExtension })
            assertEquals("the session counted it heard at hand-off and hears nothing more", 0, superseded.ends)
        }

    // Replies, volumes, failures, stale reports

    @Test
    fun `a reply mid-voice gives the voice up and plays alone at the full voice level`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val voice = RecordingVoiceListener()
            val reply = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, voice)
            arbiter.requestWhisper(whisper("w1"))
            log.clear()

            arbiter.playReply(File("reply.wav"), reply)
            assertEquals(listOf("stop", "stop whisper", "play reply 0.8"), log)
            settleRun()
            assertEquals("the reply holds whispers like a voice", emptyList<String>(), whispers.played)

            arbiter.endNaturally(player.plays.last())
            assertEquals(0, voice.ends)
            assertEquals(1, reply.finished)
        }

    @Test
    fun `an ambient voice plays at half the voice level`() = runTest(UnconfinedTestDispatcher()) {
        arbiter().play(voiceA, gain = 0.5f, listener = RecordingVoiceListener())

        assertEquals(0.4f, player.plays.single().volume, 0f)
    }

    @Test
    fun `a voice that fails reports failed, and gives the soundscape back at the session's word`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val session = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, session)

            player.plays.single().listener.onFailed(beforeSound = true)
            assertEquals(1, session.failed)
            arbiter.restoreAfterWayVoice()
            settleRun()

            assertFalse(soundscape.held)
        }

    @Test
    fun `a voice that won't start as it is handed over fails at hand-off, as iOS's start does`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val session = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, session)

            player.plays.single().listener.onFailed(beforeSound = true)

            assertEquals(1, session.failedAtHandOff)
        }

    @Test
    fun `a voice that breaks off mid-voice, or fails after waiting behind a prompt, fails as a mid-voice error does`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val midway = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, midway)
            player.plays.last().listener.onFailed(beforeSound = false)
            val waited = RecordingVoiceListener()
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceB, 1f, waited)
            ui.value = UiAudioGates()
            player.plays.last().listener.onFailed(beforeSound = true)

            assertEquals(listOf(1 to 0, 1 to 0), listOf(midway, waited).map { it.failed to it.failedAtHandOff })
        }

    @Test
    fun `a report from a play the player no longer holds is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        val first = RecordingVoiceListener()
        arbiter.play(voiceA, 1f, first)
        arbiter.play(voiceB, 1f, RecordingVoiceListener())

        player.plays.first().listener.onEnded()

        assertEquals(0, first.ends)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `a scrub on a parked voice does nothing, and a rate set meanwhile reaches the player`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            ui.value = UiAudioGates(prompt = true)
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            log.clear()

            arbiter.seek(0.5)
            arbiter.setRate(1.5f)

            assertEquals(listOf("rate 1.5"), log)
        }

    // The voice held still, for the session's clock (spec C §3.6–§3.7)

    @Test
    fun `a prompt holding a sounding voice reports it held, and its end reports it sounding again`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            val beforePrompt = arbiter.gates.value.wayVoiceHeld

            ui.value = UiAudioGates(prompt = true)
            val duringPrompt = arbiter.gates.value.wayVoiceHeld
            ui.value = UiAudioGates()

            assertEquals(listOf(false, true, false), listOf(beforePrompt, duringPrompt, arbiter.gates.value.wayVoiceHeld))
        }

    @Test
    fun `a voice parked behind a prompt is held until it starts`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        ui.value = UiAudioGates(prompt = true)
        arbiter.play(voiceA, 1f, RecordingVoiceListener())
        val parked = arbiter.gates.value.wayVoiceHeld

        ui.value = UiAudioGates()

        assertEquals(true to false, parked to arbiter.gates.value.wayVoiceHeld)
    }

    @Test
    fun `a voice the walker resumes over the prompt, or pauses during it, is no longer held`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            ui.value = UiAudioGates(prompt = true)

            arbiter.resume()
            val resumed = arbiter.gates.value.wayVoiceHeld
            arbiter.pause()

            assertEquals(false to false, resumed to arbiter.gates.value.wayVoiceHeld)
        }

    @Test
    fun `a call holds the voice until the player resumes it`() = runTest(UnconfinedTestDispatcher()) {
        val arbiter = arbiter()
        arbiter.play(voiceA, 1f, RecordingVoiceListener())
        val play = player.plays.single().listener

        player.isPlaying = false
        play.onHeld(true)
        val duringCall = arbiter.gates.value.wayVoiceHeld
        player.isPlaying = true
        play.onHeld(false)

        assertEquals(true to false, duringCall to arbiter.gates.value.wayVoiceHeld)
    }

    @Test
    fun `a hold reported by a play the player no longer holds changes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            arbiter.play(voiceA, 1f, RecordingVoiceListener())
            arbiter.play(voiceB, 1f, RecordingVoiceListener())

            player.plays.first().listener.onHeld(true)

            assertFalse(arbiter.gates.value.wayVoiceHeld)
        }

    @Test
    fun `the headphones going reach the session, and a prompt ending afterwards leaves the voice paused`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val session = RecordingVoiceListener()
            arbiter.play(voiceA, 1f, session)
            ui.value = UiAudioGates(prompt = true)

            player.plays.single().listener.onPausedForRoute()
            log.clear()
            ui.value = UiAudioGates()

            assertEquals(Triple(1, false, false), Triple(session.pausedForRoute, "resume" in log, arbiter.gates.value.wayVoiceHeld))
        }

    // The engine's outside gates

    @Test
    fun `the engine's outside gates carry the UI's recording and a whisper playing here`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            assertEquals(HonorExternalGates(), arbiter.gates.value)

            ui.value = UiAudioGates(recording = true)
            whispers.audible.value = true

            assertEquals(HonorExternalGates(recording = true, externalAudio = true), arbiter.gates.value)
        }
}
