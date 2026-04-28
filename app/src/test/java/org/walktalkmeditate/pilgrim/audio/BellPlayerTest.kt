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
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * Minimal composition smoke tests for [BellPlayer]. Actual audio
 * playback is not asserted — Robolectric's `MediaPlayer.create`
 * returns a stub that may or may not signal completion; real device
 * QA verifies the bell sounds and the ducking behavior. Robolectric's
 * MediaPlayer stub also doesn't track `setVolume` calls, so we can
 * only assert the path doesn't throw — the production-vs-stub
 * divergence is the same shape as `setAudioAttributes` (Stage 5-B).
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
        player = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(),
        )
    }

    @Test fun `play does not crash when Robolectric grants focus`() {
        // Robolectric's ShadowAudioManager grants focus requests by
        // default. MediaPlayer.create with a raw resource under
        // Robolectric may return null or a stub — either path is
        // exercised by BellPlayer and should not throw.
        player.play()
    }

    @Test fun `play does not crash with custom bellVolume`() {
        // A non-default volume exercises the setVolume call path; the
        // Robolectric stub may not store the value but must not throw.
        val customPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0.25f),
        )
        customPlayer.play()
    }
}
