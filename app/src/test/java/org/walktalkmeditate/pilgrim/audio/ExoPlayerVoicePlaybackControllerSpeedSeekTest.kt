// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
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
 * don't crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExoPlayerVoicePlaybackControllerSpeedSeekTest {

    private lateinit var controller: ExoPlayerVoicePlaybackController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        controller = ExoPlayerVoicePlaybackController(
            context = context,
            audioFocus = AudioFocusCoordinator(audioManager),
            fileSystem = VoiceRecordingFileSystem(context),
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
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
}
