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
    private var heldPlaybackThread: android.os.HandlerThread? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        player = ExoPlayerSoundscapePlayer(context = context, audioManager = audioManager)
        tempFile = File(context.cacheDir, "soundscape-test.aac").apply {
            writeBytes(ByteArray(256))
        }
    }

    @After fun tearDown() {
        heldPlaybackThread?.let { shadowOf(it.looper).unPause() }
        player.release()
        runMainQueueUntilIdle()
        heldPlaybackThread?.quitSafely()
        tempFile.delete()
    }

    private fun runMainQueueUntilIdle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * The placeholder bytes aren't audio, and ExoPlayer's decode error
     * abandons focus whenever it lands. For the tests that assert focus is
     * kept, hold the playback thread paused so the bytes are never decoded.
     * Only for tests that never release the player mid-test: release waits
     * on the playback thread, which a paused looper never answers.
     */
    private fun holdPlayback() {
        val thread = android.os.HandlerThread("soundscape-test-playback").apply { start() }
        shadowOf(thread.looper).pause()
        player.playbackLooperForTest = thread.looper
        heldPlaybackThread = thread
    }

    /**
     * The placeholder bytes aren't audio, so ExoPlayer's playback thread
     * may report a decode error before or after any given drain. Only the
     * player's own synchronous refusals say anything about these tests.
     */
    private fun SoundscapePlayer.State.isRefusal(): Boolean =
        this is SoundscapePlayer.State.Error && reason in setOf("file missing", "audio focus denied")

    @Test fun `play constructs ExoPlayer + REPEAT_MODE_ALL gapless loop + focus without crashing`() {
        player.play(tempFile)
        runMainQueueUntilIdle()
        val state = player.state.value
        assertFalse("expected play to go ahead, got $state", state.isRefusal())
        // Pin the gapless-loop invariants the docstring promises:
        // 2-item playlist + REPEAT_MODE_ALL. A future refactor that
        // drops the duplicated MediaItem or flips back to
        // REPEAT_MODE_ONE — silently regressing the audible AAC loop
        // boundary — fails here instead of shipping.
        val snapshot = player.playbackInvariantSnapshot()
        assertNotNull("player should be constructed after play()", snapshot)
        assertEquals(
            "gapless loop requires playlist of two same-source MediaItems",
            2,
            snapshot!!.first,
        )
        assertEquals(
            "REPEAT_MODE_ALL pre-buffers next item; REPEAT_MODE_ONE re-seeks and exposes AAC padding",
            androidx.media3.common.Player.REPEAT_MODE_ALL,
            snapshot.second,
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
        assertFalse("expected the second play to go ahead, got $state", state.isRefusal())
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
        holdPlayback()
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
        holdPlayback()
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
