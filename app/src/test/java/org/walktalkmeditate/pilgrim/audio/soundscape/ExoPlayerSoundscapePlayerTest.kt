// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Smoke tests for [ExoPlayerSoundscapePlayer]. Per CLAUDE.md, the
 * PR must exercise the real ExoPlayer + AudioFocusRequest builder
 * paths under Robolectric — the builders perform runtime attribute
 * validation, which is where shipped bugs have lived historically
 * (Stage 2-F scheduler crash, Stage 5-B MediaPlayer attribute
 * ordering). ShadowAudioManager grants focus by default, so the
 * play path here does not simulate audio output — but it DOES
 * exercise builder chain + REPEAT_MODE_ONE + focus-request
 * construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExoPlayerSoundscapePlayerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var player: ExoPlayerSoundscapePlayer
    private lateinit var tempFile: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        player = ExoPlayerSoundscapePlayer(context = context, audioManager = audioManager)
        tempFile = File(context.cacheDir, "soundscape-test.aac").apply {
            writeBytes(ByteArray(256))
        }
    }

    @After fun tearDown() {
        player.release()
        runMainQueueUntilIdle()
        tempFile.delete()
    }

    private fun runMainQueueUntilIdle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun `play constructs ExoPlayer + REPEAT_MODE_ONE + focus without crashing`() {
        player.play(tempFile)
        runMainQueueUntilIdle()
        // ShadowAudioManager grants focus; Robolectric's media stub
        // may not transition to STATE_READY so accept either Playing
        // (listener fired) or Idle (no media progression). Either
        // way, no crash from builder validation = win.
        val state = player.state.value
        assertTrue(
            "expected Playing or Idle, got $state",
            state is SoundscapePlayer.State.Playing || state is SoundscapePlayer.State.Idle,
        )
    }

    @Test fun `play with missing file transitions to Error`() {
        val missing = File(context.cacheDir, "does-not-exist.aac")
        player.play(missing)
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Error)
    }

    @Test fun `play with zero-byte file transitions to Error`() {
        val empty = File(context.cacheDir, "empty.aac").apply { createNewFile() }
        player.play(empty)
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Error)
        empty.delete()
    }

    @Test fun `stop transitions to Idle`() {
        player.play(tempFile)
        runMainQueueUntilIdle()
        player.stop()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Idle)
    }

    @Test fun `release after play is safe`() {
        player.play(tempFile)
        runMainQueueUntilIdle()
        player.release()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Idle)
    }

    @Test fun `release is idempotent`() {
        player.release()
        runMainQueueUntilIdle()
        player.release()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Idle)
    }

    @Test fun `second play tears down first without crash`() {
        player.play(tempFile)
        runMainQueueUntilIdle()
        val second = File(context.cacheDir, "soundscape-test-2.aac").apply {
            writeBytes(ByteArray(256))
        }
        player.play(second)
        runMainQueueUntilIdle()
        val state = player.state.value
        assertFalse(
            "expected non-Error state after second play, got $state",
            state is SoundscapePlayer.State.Error,
        )
        second.delete()
    }

    @Test fun `stop after release does not crash`() {
        player.release()
        runMainQueueUntilIdle()
        player.stop()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Idle)
    }
}
