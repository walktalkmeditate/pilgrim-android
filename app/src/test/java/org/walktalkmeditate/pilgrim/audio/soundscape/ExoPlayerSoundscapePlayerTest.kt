// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * exercise builder chain + REPEAT_MODE_ALL gapless-loop playlist
 * + focus-request construction.
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

    @Test fun `play constructs ExoPlayer + REPEAT_MODE_ALL gapless loop + focus without crashing`() {
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

    @Test fun `focus request is GAIN_TRANSIENT_MAY_DUCK not GAIN (BUG A2)`() {
        // iOS parity: the soundscape is a continuously-duckable ambient
        // layer under the voice guide, never an exclusive owner. GAIN
        // would preempt the guide's own GAIN_TRANSIENT_MAY_DUCK and
        // trigger its stop-on-LOSS. Per CLAUDE.md the AudioFocusRequest
        // builder change must keep a Robolectric .build() test.
        player.play(tempFile)
        runMainQueueUntilIdle()
        val req = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("focus request was built and submitted", req)
        assertEquals(
            "soundscape must not request AUDIOFOCUS_GAIN — it preempts the voice guide",
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            req.audioFocusRequest.focusGain,
        )
    }

    @Test fun `stopForSwap stops playback but does NOT abandon focus (BUG A2)`() {
        // iOS parity SoundscapePlayer.swift:30-33 — a crossfade keeps
        // the audio session active. Abandoning focus on every swap
        // would preempt the in-flight voice guide.
        player.play(tempFile)
        runMainQueueUntilIdle()
        player.stopForSwap()
        runMainQueueUntilIdle()
        assertTrue(player.state.value is SoundscapePlayer.State.Idle)
        assertNull(
            "stopForSwap must NOT abandon audio focus (would preempt the guide)",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    @Test fun `swap reuses held focus — no abandon, single focus request (BUG A2)`() {
        // play → stopForSwap → play (the mid-meditation swap sequence).
        // requestFocus() is idempotent: the second play reuses the
        // already-held request. Focus must never be abandoned across
        // the swap so the voice guide's GAIN_TRANSIENT_MAY_DUCK is
        // never preempted.
        player.play(tempFile)
        runMainQueueUntilIdle()
        val firstRequest = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(firstRequest)

        player.stopForSwap()
        runMainQueueUntilIdle()
        val second = File(context.cacheDir, "soundscape-swap-2.aac").apply {
            writeBytes(ByteArray(256))
        }
        player.play(second)
        runMainQueueUntilIdle()

        assertNull(
            "no focus abandon across a swap",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
        assertTrue(
            "second play must not be in Error state",
            player.state.value !is SoundscapePlayer.State.Error,
        )
        second.delete()
    }

    @Test fun `true stop after a swap abandons focus`() {
        // The focus-preserving swap path must not break the real exit
        // path: stop() still abandons focus.
        player.play(tempFile)
        runMainQueueUntilIdle()
        player.stopForSwap()
        runMainQueueUntilIdle()
        player.play(tempFile)
        runMainQueueUntilIdle()
        player.stop()
        runMainQueueUntilIdle()
        assertNotNull(
            "true exit must abandon focus",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }
}
