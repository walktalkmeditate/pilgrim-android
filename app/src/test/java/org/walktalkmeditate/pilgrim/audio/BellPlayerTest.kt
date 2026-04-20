// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Minimal composition smoke tests for [BellPlayer]. Actual audio
 * playback is not asserted — Robolectric's `MediaPlayer.create`
 * returns a stub that may or may not signal completion; real device
 * QA verifies the bell sounds and the ducking behavior.
 *
 * These tests confirm the code path doesn't throw for the operation
 * the production `MeditationBellObserver` invokes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BellPlayerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var player: BellPlayer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        player = BellPlayer(context = context, audioManager = audioManager)
    }

    @Test fun `play does not crash when Robolectric grants focus`() {
        // Robolectric's ShadowAudioManager grants focus requests by
        // default. MediaPlayer.create with a raw resource under
        // Robolectric may return null or a stub — either path is
        // exercised by BellPlayer and should not throw.
        player.play()
    }
}
