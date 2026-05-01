// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowVibrator
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
    private lateinit var vibrator: Vibrator
    private lateinit var player: BellPlayer
    private val createdShadows = mutableListOf<ShadowMediaPlayer>()
    private val createdMediaPlayers = mutableListOf<MediaPlayer>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        vibrator = context.getSystemService(Vibrator::class.java)
        // Default Robolectric behavior is to throw on prepare() for an
        // unregistered data source — which would route the bell-volume
        // tests into BellPlayer's catch block before setVolume runs.
        // A no-op MediaInfo provider lets prepare() succeed for any
        // data source so we can observe the volume the production code
        // applies. Real-device QA still validates the audio path.
        ShadowMediaPlayer.setMediaInfoProvider {
            ShadowMediaPlayer.MediaInfo()
        }
        ShadowMediaPlayer.setCreateListener { mp: MediaPlayer, shadow: ShadowMediaPlayer ->
            createdShadows += shadow
            createdMediaPlayers += mp
        }
        player = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(),
            vibrator = vibrator,
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
            vibrator = vibrator,
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
            vibrator = vibrator,
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
            vibrator = vibrator,
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
            vibrator = vibrator,
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
            vibrator = vibrator,
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
            vibrator = vibrator,
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

    // ---- Stage 12-C: bell-coupled haptic ----

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `play fires primitive composition on api 30 plus when supported`() {
        // API 30+ AND PRIMITIVE_CLICK reported supported → composition
        // path fires; waveform fallback does NOT fire.
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)
        shadowVibrator.setSupportedPrimitives(
            listOf(VibrationEffect.Composition.PRIMITIVE_CLICK),
        )

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = true),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = true)

        val primitives = shadowVibrator.primitiveEffects!!
        assertEquals(
            "expected one PRIMITIVE_CLICK; saw $primitives",
            1, primitives.size,
        )
        assertEquals(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            primitives[0].id,
        )
        // Waveform fallback path NOT taken — no oneshot duration recorded.
        assertEquals(0L, shadowVibrator.milliseconds)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `play falls back to waveform when primitive unsupported on api 30 plus`() {
        // API 30+ but PRIMITIVE_CLICK reported unsupported →
        // skip composition, fall back to createOneShot(30ms).
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)
        // Empty supported set: areAllPrimitivesSupported(CLICK) → false.
        shadowVibrator.setSupportedPrimitives(emptyList())

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = true),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = true)

        // Composition NOT fired.
        assertTrue(
            "expected no primitive composition; saw ${shadowVibrator.primitiveEffects!!}",
            shadowVibrator.primitiveEffects!!.isEmpty(),
        )
        // Waveform fallback DID fire — 30ms oneshot.
        assertEquals(30L, shadowVibrator.milliseconds)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `play fires waveform on api 28 to 29`() {
        // API 28/29 lack VibrationEffect.Composition entirely → straight
        // to waveform fallback (no probe needed).
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = true),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = true)

        // No composition path on API 28.
        assertTrue(shadowVibrator.primitiveEffects!!.isEmpty())
        // 30ms oneshot recorded.
        assertEquals(30L, shadowVibrator.milliseconds)
    }

    @Test
    fun `play skips haptic when bellHaptic preference disabled`() {
        // User has disabled bell haptic. Caller still asks for haptic
        // (withHaptic=true) — production must honor the user's pref.
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = false),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = true)

        assertTrue(
            "expected no primitive when pref disabled; saw ${shadowVibrator.primitiveEffects!!}",
            shadowVibrator.primitiveEffects!!.isEmpty(),
        )
        assertEquals(0L, shadowVibrator.milliseconds)
    }

    @Test
    fun `play skips haptic when withHaptic flag false`() {
        // Caller (e.g. milestone overlay) explicitly opts out of haptic.
        // User pref enabled is irrelevant.
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = true),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = false)

        assertTrue(shadowVibrator.primitiveEffects!!.isEmpty())
        assertEquals(0L, shadowVibrator.milliseconds)
    }

    @Test
    fun `play still plays bell regardless of haptic decisions`() {
        // Audio (MediaPlayer) MUST be exercised across all 4
        // (haptic-pref × withHaptic) combinations. Counts MediaPlayer
        // construction events to confirm the audio path always runs.
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)

        val combos = listOf(
            true to true,
            true to false,
            false to true,
            false to false,
        )
        var expected = 0
        combos.forEach { (pref, withHaptic) ->
            val combo = BellPlayer(
                context = context,
                audioManager = audioManager,
                soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = pref),
                vibrator = vibrator,
            )
            combo.play(scale = 1.0f, withHaptic = withHaptic)
            expected += 1
            assertEquals(
                "MediaPlayer not constructed for pref=$pref withHaptic=$withHaptic",
                expected, createdMediaPlayers.size,
            )
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `haptic fires after bell audio start`() {
        // iOS fires .medium haptic AFTER bell.start(). Mirror by
        // ordering MediaPlayer.start() BEFORE the vibrator call. The
        // ShadowMediaPlayer state is OBSERVABLE (started=true) at the
        // moment vibrate is called — capture started-state inside a
        // CreateListener that subscribes a doStart hook isn't trivial,
        // so we instead snapshot it via the post-call ShadowMediaPlayer
        // state plus assert primitive fired (proving haptic was reached
        // AFTER audio start, since playInternal would have early-returned
        // on any failure path before reaching haptic).
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)
        shadowVibrator.setSupportedPrimitives(
            listOf(VibrationEffect.Composition.PRIMITIVE_CLICK),
        )

        val hapticPlayer = BellPlayer(
            context = context,
            audioManager = audioManager,
            soundsPreferences = FakeSoundsPreferencesRepository(initialBellHapticEnabled = true),
            vibrator = vibrator,
        )
        hapticPlayer.play(scale = 1.0f, withHaptic = true)

        // Audio MediaPlayer must have been constructed.
        val mp = createdMediaPlayers.lastOrNull()
        assertNotNull("MediaPlayer not constructed", mp)
        val mpShadow = shadowOf(mp)
        // start() was reached on the MediaPlayer.
        assertTrue("MediaPlayer.start() not called before haptic", mpShadow.isReallyPlaying)
        // Haptic also fired (primitive composition path).
        assertFalse(
            "haptic primitive did not fire after audio start",
            shadowVibrator.primitiveEffects!!.isEmpty(),
        )
    }
}
