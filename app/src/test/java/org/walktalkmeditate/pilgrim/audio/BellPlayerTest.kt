// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * Minimal composition smoke tests for [BellPlayer]. Actual audio
 * playback is not asserted — Robolectric's `MediaPlayer.create`
 * returns a stub that may or may not signal completion; real device
 * QA verifies the bell sounds and the ducking behavior.
 *
 * Robolectric's `ShadowMediaPlayer` *does* expose the last `setVolume`
 * call via [ShadowMediaPlayer.getLeftVolume] — the volume-scaling
 * tests below capture the player at construction time via
 * [ShadowMediaPlayer.setCreateListener] and read the recorded volume
 * after `play(...)` returns. The setAudioAttributes path is still
 * verified by Stage 5-B device QA, not by the shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BellPlayerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var player: BellPlayer
    private val createdShadows = mutableListOf<ShadowMediaPlayer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        // Default Robolectric behavior is to throw on prepare() for an
        // unregistered data source — which would route the bell-volume
        // tests into BellPlayer's catch block before setVolume runs.
        // A no-op MediaInfo provider lets prepare() succeed for any
        // data source so we can observe the volume the production code
        // applies. Real-device QA still validates the audio path.
        ShadowMediaPlayer.setMediaInfoProvider {
            ShadowMediaPlayer.MediaInfo()
        }
        ShadowMediaPlayer.setCreateListener { _: MediaPlayer, shadow: ShadowMediaPlayer ->
            createdShadows += shadow
        }
        player = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(),
        )
    }

    @After
    fun tearDown() {
        ShadowMediaPlayer.setCreateListener(null)
        ShadowMediaPlayer.resetStaticState()
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

    @Test fun `play with scale multiplies user bell volume`() {
        // Stage 11-B milestone overlay calls play(scale = 0.4f) so a
        // user with bellVolume=0.5 hears 0.2, but a muted user (0f)
        // still hears nothing. Captures the last setVolume call via
        // ShadowMediaPlayer and asserts the product.
        val scaledPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0.5f),
        )
        scaledPlayer.play(scale = 0.4f)

        val shadow = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0.2f, shadow.leftVolume, 0.001f)
        assertEquals(0.2f, shadow.rightVolume, 0.001f)
    }

    @Test fun `play with scale and zero bell volume stays silent`() {
        // A muted user (bellVolume=0) must remain silent regardless
        // of the milestone overlay's scale parameter.
        val mutedPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0f),
        )
        mutedPlayer.play(scale = 0.4f)

        val shadow = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0f, shadow.leftVolume, 0.001f)
        assertEquals(0f, shadow.rightVolume, 0.001f)
    }

    @Test fun `play without scale parameter uses user volume unchanged`() {
        // Backwards-compat: existing callers invoking play() (no
        // scale arg) get the user's bell-volume preference applied
        // verbatim — no implicit scaling.
        val unscaledPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0.7f),
        )
        unscaledPlayer.play()

        val shadow = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0.7f, shadow.leftVolume, 0.001f)
        assertEquals(0.7f, shadow.rightVolume, 0.001f)
    }

    @Test fun `play with NaN scale stays silent`() {
        // `Float.NaN.coerceIn(0f, 1f)` returns NaN — without an
        // explicit isNaN() guard, NaN would propagate into
        // `MediaPlayer.setVolume`. BellPlayer treats NaN as silence
        // so a future repo bug that persists NaN can't surface as
        // a runtime IllegalArgumentException or a silent stuck-at-
        // default playback.
        val nanPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0.7f),
        )
        nanPlayer.play(scale = Float.NaN)

        val shadow = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0f, shadow.leftVolume, 0.001f)
        assertEquals(0f, shadow.rightVolume, 0.001f)
    }

    @Test fun `play with out-of-range scale clamps to bounds`() {
        // Defensive coerceIn handles values outside [0, 1] from any
        // future caller. scale > 1 clamps to 1 (effective = user vol);
        // scale < 0 clamps to 0 (silent regardless of user vol).
        val clampedPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellVolume = 0.5f),
        )
        clampedPlayer.play(scale = 1.5f)

        val shadowHigh = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0.5f, shadowHigh.leftVolume, 0.001f)
        assertEquals(0.5f, shadowHigh.rightVolume, 0.001f)

        clampedPlayer.play(scale = -0.3f)
        val shadowLow = createdShadows.lastOrNull()
            ?: error("BellPlayer did not construct a MediaPlayer")
        assertEquals(0f, shadowLow.leftVolume, 0.001f)
        assertEquals(0f, shadowLow.rightVolume, 0.001f)
    }
}
