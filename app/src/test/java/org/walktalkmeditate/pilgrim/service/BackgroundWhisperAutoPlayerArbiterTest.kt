// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.Application
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.walk.AudibleWhisperPlayer
import org.walktalkmeditate.pilgrim.audio.walk.FakeUiAudioGates
import org.walktalkmeditate.pilgrim.audio.walk.FakeWayVoicePlayer
import org.walktalkmeditate.pilgrim.audio.walk.FakeWayVoiceSoundscape
import org.walktalkmeditate.pilgrim.audio.walk.RecordingVoiceListener
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGates
import org.walktalkmeditate.pilgrim.audio.walk.WalkAudioArbiter
import org.walktalkmeditate.pilgrim.audio.walk.audioLog
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.proximity.FakeGeoCacheService
import org.walktalkmeditate.pilgrim.data.proximity.FakeProximityDetectionService
import org.walktalkmeditate.pilgrim.data.proximity.ProximityEvent
import org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperManifestService

/**
 * `:tracker`'s whisper autoplay behind the walk audio arbiter: with the
 * release flag on it holds while a guide prompt sounds or a Way voice is
 * loaded, as every in-walk whisper on iOS waits in `AudioPriorityQueue`
 * (parity spec C §5); with it off nothing about it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundWhisperAutoPlayerArbiterTest {

    private val log = audioLog()
    private val proximity = FakeProximityDetectionService()
    private val geoCache = FakeGeoCacheService().apply { setWhispers(listOf(cachedWhisper("w1"))) }
    private val whispers = AudibleWhisperPlayer(log)
    private val voicePlayer = FakeWayVoicePlayer(log)
    private val ui = FakeUiAudioGates()

    private fun TestScope.arbiter() =
        WalkAudioArbiter(voicePlayer, FakeWayVoiceSoundscape(log), whispers, ui, backgroundScope)

    private fun TestScope.autoPlayer(honor: Boolean, arbiter: Provider<WalkAudioArbiter>) =
        BackgroundWhisperAutoPlayer(
            geoCacheService = geoCache,
            proximityService = proximity,
            whisperManifestService = FakeWhisperManifestService(),
            whisperPlayer = whispers,
            practicePreferences = FakePracticePreferencesRepository(),
            soundsPreferences = FakeSoundsPreferencesRepository(),
            currentTimeMillis = { 0L },
            sessionDispatcher = UnconfinedTestDispatcher(testScheduler),
            playWhisper = BackgroundWhisperAutoPlayer.whisperRoute(FixedReleaseFlags(honor), arbiter, whispers),
        )

    private fun cachedWhisper(id: String) = CachedWhisper(
        id = id,
        latitude = 37.0,
        longitude = -122.0,
        whisperId = "ws-$id",
        category = "presence",
        expiresAt = "2099-01-01T00:00:00Z",
    )

    private fun entered(cacheId: String) = ProximityEvent(
        target = ProximityTarget(
            id = ProximityTarget.whisperId(cacheId),
            latitude = 37.0,
            longitude = -122.0,
            radius = 42.0,
            type = ProximityTarget.Type.Whisper,
        ),
        distanceMeters = 10.0,
        direction = ProximityEvent.Direction.Entered,
    )

    @Test
    fun `a whisper entered while a Way voice plays waits, and plays once the voice's run ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val sut = autoPlayer(honor = true) { arbiter }
            sut.start(this, emptyFlow())
            arbiter.play(File("voice-1.wav"), 1f, RecordingVoiceListener())

            proximity.emit(entered("w1"))
            assertEquals(0, whispers.playCalls)

            voicePlayer.plays.single().listener.onEnded()
            arbiter.restoreAfterWayVoice()
            advanceTimeBy(WalkAudioArbiter.RUN_SETTLE_MILLIS + 1)
            runCurrent()

            assertEquals(1, whispers.playCalls)
            sut.stop()
        }

    @Test
    fun `a whisper entered during a guide prompt waits for it, and one parked when a prompt starts is dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            val arbiter = arbiter()
            val sut = autoPlayer(honor = true) { arbiter }
            sut.start(this, emptyFlow())

            ui.value.value = UiAudioGates(prompt = true)
            proximity.emit(entered("w1"))
            assertEquals(0, whispers.playCalls)
            ui.value.value = UiAudioGates()
            assertEquals(1, whispers.playCalls)

            arbiter.play(File("voice-1.wav"), 1f, RecordingVoiceListener())
            proximity.emit(entered("w1"))
            ui.value.value = UiAudioGates(prompt = true)
            ui.value.value = UiAudioGates()
            voicePlayer.plays.single().listener.onEnded()
            arbiter.restoreAfterWayVoice()
            advanceTimeBy(WalkAudioArbiter.RUN_SETTLE_MILLIS + 1)
            runCurrent()

            assertEquals(1, whispers.playCalls)
            sut.stop()
        }

    @Test
    fun `with the release flag off the arbiter is never built and the whisper plays at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val sut = autoPlayer(honor = false) { error("the arbiter must not be resolved with the flag off") }
            sut.start(this, emptyFlow())

            proximity.emit(entered("w1"))

            assertEquals(1, whispers.playCalls)
            sut.stop()
        }
}
