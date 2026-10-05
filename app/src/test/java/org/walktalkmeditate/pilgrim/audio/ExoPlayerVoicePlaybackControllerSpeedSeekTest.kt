// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Robolectric smoke tests for [ExoPlayerVoicePlaybackController]'s Stage 10-D
 * additions: speed (with 0.5..2.0 coercion), seek (with the C.TIME_UNSET crash
 * guard called out in the spec), and the position-tick StateFlow.
 *
 * No actual ExoPlayer playback is exercised — Robolectric's media stack is a
 * stub. We verify the StateFlow defaults and that pre-play seek/setSpeed calls
 * don't crash. The rate tests build the real player and read the rate it was
 * given; the files never exist, so nothing decodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExoPlayerVoicePlaybackControllerSpeedSeekTest {

    private lateinit var controller: ExoPlayerVoicePlaybackController
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val audioManager = context.getSystemService(AudioManager::class.java)
        controller = ExoPlayerVoicePlaybackController(
            context = context,
            audioFocus = AudioFocusCoordinator(audioManager),
            fileSystem = VoiceRecordingFileSystem(context),
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    @After
    fun tearDown() {
        controller.release()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `setPlaybackSpeed default is 1_0`() {
        assertEquals(1.0f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `setPlaybackSpeed updates StateFlow`() {
        controller.setPlaybackSpeed(1.5f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(1.5f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `setPlaybackSpeed coerces above 2_0 to 2_0`() {
        // The StateFlow stores the COERCED value (what's actually playing),
        // not the requested value, so observers reflect the real player rate.
        controller.setPlaybackSpeed(3.0f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(2.0f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `setPlaybackSpeed coerces below 0_5 to 0_5`() {
        controller.setPlaybackSpeed(0.1f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(0.5f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `seek with no media item is a safe no-op`() {
        // No recording loaded — the C.TIME_UNSET / null-currentMediaItem
        // guards must let this return without crashing.
        controller.seek(0.5f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    @Test
    fun `playbackPositionMillis default is 0`() {
        assertEquals(0L, controller.playbackPositionMillis.value)
    }

    // The house builder rule: the preview's real MediaItem, for a shared Way's `.m4a` as for a WAV.
    @Test
    fun `a file plays through a real MediaItem on its file URI`() {
        val file = java.io.File("/data/no_backup/Ways/share:Qoi4YmPHLN/media/audio/1.m4a")

        val item = ExoPlayerVoicePlaybackController.mediaItemFor(file)

        assertEquals(android.net.Uri.fromFile(file), item.localConfiguration!!.uri)
    }

    // An item with its own rate: the summary's stage reply, which iOS plays on a section-owned 1x player.

    private val rowFile get() = File(context.cacheDir, "row.wav")
    private val replyFile get() = File(context.cacheDir, "reply.m4a")

    @Test
    fun `the reply plays at its own 1x while the rows keep their 1_5x`() {
        controller.playFile(ROW_ID, rowFile)
        controller.setPlaybackSpeed(1.5f)
        controller.playFile(REPLY_ID, replyFile, rate = 1f)
        ShadowLooper.idleMainLooper()

        assertEquals(1.0f to 1.5f, controller.playerSpeed() to controller.playbackSpeed.value)
    }

    @Test
    fun `a row's speed tapped while the reply plays leaves the reply at 1x`() {
        controller.playFile(REPLY_ID, replyFile, rate = 1f)
        controller.setPlaybackSpeed(2.0f)
        ShadowLooper.idleMainLooper()

        assertEquals(1.0f to 2.0f, controller.playerSpeed() to controller.playbackSpeed.value)
    }

    @Test
    fun `a row after the reply plays at the rows' speed again`() {
        controller.playFile(ROW_ID, rowFile)
        controller.setPlaybackSpeed(1.5f)
        controller.playFile(REPLY_ID, replyFile, rate = 1f)
        controller.playFile(ROW_ID, rowFile)
        ShadowLooper.idleMainLooper()

        assertEquals(1.5f, controller.playerSpeed()!!, 0.001f)
    }

    private companion object {
        const val ROW_ID = 7L
        const val REPLY_ID = Long.MIN_VALUE
    }
}
