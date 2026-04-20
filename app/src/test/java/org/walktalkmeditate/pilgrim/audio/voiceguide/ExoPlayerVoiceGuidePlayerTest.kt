// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Smoke tests for [ExoPlayerVoiceGuidePlayer]. Per CLAUDE.md, the
 * PR must exercise the real ExoPlayer + AudioFocusRequest builder
 * paths under Robolectric — ShadowAudioManager grants focus by
 * default, so the play path doesn't simulate audio output, but it
 * DOES exercise the attributes/builder chain where runtime
 * validation lives (Stage 2-D lesson).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExoPlayerVoiceGuidePlayerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var player: ExoPlayerVoiceGuidePlayer
    private lateinit var tempFile: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        player = ExoPlayerVoiceGuidePlayer(context = context, audioManager = audioManager)
        tempFile = File(context.cacheDir, "voiceguide-test.aac").apply {
            writeBytes(ByteArray(128))
        }
    }

    @After fun tearDown() {
        player.release()
        tempFile.delete()
    }

    private fun runMainQueueUntilIdle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun `play constructs ExoPlayer + focus request without crashing`() {
        player.play(tempFile) { }
        runMainQueueUntilIdle()
        // If the builders validated + granted, state transitioned
        // past Idle. Robolectric may fire STATE_ENDED immediately for
        // a zero-media-duration stub, so accept either Playing or
        // post-completion Idle.
        val state = player.state.value
        assertTrue(
            "expected Playing or Idle, got $state",
            state is VoiceGuidePlayer.State.Playing || state is VoiceGuidePlayer.State.Idle,
        )
    }

    @Test fun `play with missing file transitions to Error and fires onFinished`() {
        val missing = File(context.cacheDir, "does-not-exist.aac")
        val fires = AtomicInteger(0)
        player.play(missing) { fires.incrementAndGet() }
        runMainQueueUntilIdle()
        assertTrue(player.state.value is VoiceGuidePlayer.State.Error)
        assertEquals(1, fires.get())
    }

    @Test fun `stop fires onFinished even without natural completion`() {
        val fires = AtomicInteger(0)
        player.play(tempFile) { fires.incrementAndGet() }
        runMainQueueUntilIdle()
        val natural = fires.get()
        player.stop()
        runMainQueueUntilIdle()
        // At minimum, onFinished must have fired once — either
        // naturally (if Robolectric ran through the stub) or from
        // stop(). Never twice.
        assertEquals(1, fires.get())
        // Post-stop state is Idle.
        assertTrue(player.state.value is VoiceGuidePlayer.State.Idle)
        // Acknowledge `natural` to avoid unused-var lint.
        assertTrue(natural in 0..1)
    }

    @Test fun `release after play is safe and fires completion once`() {
        val fires = AtomicInteger(0)
        player.play(tempFile) { fires.incrementAndGet() }
        runMainQueueUntilIdle()
        player.release()
        runMainQueueUntilIdle()
        assertEquals(1, fires.get())
        assertTrue(player.state.value is VoiceGuidePlayer.State.Idle)
    }

    @Test fun `second play tears down first and fires first onFinished once`() {
        val firstFires = AtomicInteger(0)
        val secondFires = AtomicInteger(0)
        player.play(tempFile) { firstFires.incrementAndGet() }
        runMainQueueUntilIdle()
        player.play(tempFile) { secondFires.incrementAndGet() }
        runMainQueueUntilIdle()
        assertEquals(1, firstFires.get())
        // second may or may not have fired depending on whether
        // Robolectric's stub completed it — what we want to guarantee
        // is that it fired AT MOST once.
        assertTrue(secondFires.get() in 0..1)
    }

    @Test fun `release is idempotent`() {
        player.release()
        runMainQueueUntilIdle()
        player.release()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is VoiceGuidePlayer.State.Idle)
    }
}
