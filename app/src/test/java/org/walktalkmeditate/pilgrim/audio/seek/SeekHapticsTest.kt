// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.seek

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pattern-shape tests for [SeekHaptics] via Robolectric's
 * ShadowVibrator. Composition scales/delays are observable directly;
 * waveform amplitudes are not exposed by the shadow, so the
 * intensity→amplitude mapping is pinned through the pure
 * [SeekHaptics.Companion.amplitudeFor] function instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekHapticsTest {

    private lateinit var vibrator: Vibrator
    private lateinit var haptics: SeekHaptics

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        vibrator = context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        haptics = SeekHaptics(vibrator)
    }

    private fun supportTickPrimitive() {
        shadowOf(vibrator).setSupportedPrimitives(
            listOf(VibrationEffect.Composition.PRIMITIVE_TICK),
        )
    }

    /**
     * SDK ≥ 31 records composition vibrations as
     * `VibrationEffectSegment`s rather than the legacy
     * `primitiveEffects` list — merge both recording paths.
     */
    private fun recordedPrimitives() = shadowOf(vibrator).primitiveEffects!!
        .ifEmpty { shadowOf(vibrator).primitiveSegmentsInPrimitiveEffects }

    // ─── Tick (iOS playSeekTick: 0.3 + 0.25 × closeness) ─────────────

    @Test
    fun `tick fires one soft primitive at the intensity floor when far`() {
        supportTickPrimitive()

        haptics.tick(closeness = 0f)

        val primitives = recordedPrimitives()
        assertEquals(1, primitives.size)
        assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, primitives[0].id)
        assertEquals(0.3f, primitives[0].scale, 0.001f)
        assertEquals(0, primitives[0].delay)
    }

    @Test
    fun `tick scales intensity with closeness`() {
        supportTickPrimitive()

        haptics.tick(closeness = 1f)

        assertEquals(0.55f, recordedPrimitives()[0].scale, 0.001f)
    }

    @Test
    fun `tick clamps out-of-range closeness`() {
        supportTickPrimitive()

        haptics.tick(closeness = 7f)

        assertEquals(0.55f, recordedPrimitives()[0].scale, 0.001f)
    }

    @Test
    fun `tick falls back to a 30ms one-shot without primitive support`() {
        haptics.tick(closeness = 0.5f)

        assertTrue(shadowOf(vibrator).primitiveEffects!!.isEmpty())
        assertEquals(30L, shadowOf(vibrator).milliseconds)
    }

    // ─── Aligned (iOS playSeekAligned: two pulses 0.18 s apart) ──────

    @Test
    fun `aligned fires two soft primitives 180ms apart`() {
        supportTickPrimitive()

        haptics.aligned(closeness = 0f)

        val primitives = recordedPrimitives()
        assertEquals(2, primitives.size)
        assertEquals(0.4f, primitives[0].scale, 0.001f)
        assertEquals(0.4f, primitives[1].scale, 0.001f)
        assertEquals(0, primitives[0].delay)
        assertEquals(180, primitives[1].delay)
    }

    @Test
    fun `aligned scales both pulses with closeness`() {
        supportTickPrimitive()

        haptics.aligned(closeness = 1f)

        val primitives = recordedPrimitives()
        assertEquals(0.7f, primitives[0].scale, 0.001f)
        assertEquals(0.7f, primitives[1].scale, 0.001f)
    }

    @Test
    fun `aligned waveform fallback keeps the 180ms onset gap`() {
        haptics.aligned(closeness = 0.5f)

        // 30ms tap, 150ms rest, 30ms tap → onsets at 0 and 180ms.
        assertArrayEquals(longArrayOf(30L, 150L, 30L), shadowOf(vibrator).pattern)
    }

    // ─── Arrival (iOS playSeekArrival: 0/0.16/0.34 s, 0.4→0.55→0.7) ──

    @Test
    fun `arrival fires three rising primitives at iOS onsets`() {
        supportTickPrimitive()

        haptics.arrival()

        val primitives = recordedPrimitives()
        assertEquals(3, primitives.size)
        assertEquals(0.4f, primitives[0].scale, 0.001f)
        assertEquals(0.55f, primitives[1].scale, 0.001f)
        assertEquals(0.7f, primitives[2].scale, 0.001f)
        assertEquals(0, primitives[0].delay)
        assertEquals(160, primitives[1].delay)
        assertEquals(180, primitives[2].delay)
    }

    @Test
    fun `arrival waveform fallback keeps the iOS onsets`() {
        haptics.arrival()

        // Taps at onsets 0 / 160 / 340 ms.
        assertArrayEquals(
            longArrayOf(30L, 130L, 30L, 150L, 30L),
            shadowOf(vibrator).pattern,
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `taps use the waveform path on api 28`() {
        // No VibrationEffect.Composition before API 30 — the primitive
        // probe must not be reached (it would throw NoSuchMethodError).
        haptics.tick(closeness = 0.5f)
        assertEquals(30L, shadowOf(vibrator).milliseconds)

        haptics.arrival()
        assertArrayEquals(
            longArrayOf(30L, 130L, 30L, 150L, 30L),
            shadowOf(vibrator).pattern,
        )
    }

    // ─── Breath (iOS playSeekBreath: two 0.9 s continuous segments) ──

    @Test
    fun `breathIn plays two 900ms segments with amplitude control`() {
        shadowOf(vibrator).setHasAmplitudeControl(true)

        haptics.breathIn()

        assertArrayEquals(longArrayOf(900L, 900L), shadowOf(vibrator).pattern)
    }

    @Test
    fun `breathOut mirrors the swell shape`() {
        // Dormant vocabulary — iOS defines seekBreathOut with no
        // production call site at c1745e8; the pattern must still be
        // real so a future caller gets the falling swell.
        shadowOf(vibrator).setHasAmplitudeControl(true)

        haptics.breathOut()

        assertArrayEquals(longArrayOf(900L, 900L), shadowOf(vibrator).pattern)
    }

    @Test
    fun `breath falls back to a single soft tap without amplitude control`() {
        shadowOf(vibrator).setHasAmplitudeControl(false)

        haptics.breathIn()

        // NOT a 1.8s fixed-strength rattle — mirrors iOS's soft-impact
        // fallback. (SDK ≥ 31 records a one-shot as a single 30ms
        // segment in `pattern` too, so assert no 900ms segment exists
        // rather than a null pattern.)
        assertEquals(30L, shadowOf(vibrator).milliseconds)
        assertTrue(
            (shadowOf(vibrator).pattern ?: longArrayOf()).none { it == 900L },
        )
    }

    // ─── Foreground independence (deliberate Android divergence) ─────

    @Test
    fun `patterns fire with no foreground or lifecycle wiring`() {
        // iOS gates seek haptics on applicationState == .active because
        // backgrounded CoreHaptics is discarded. Android's Vibrator
        // works screen-off, and pocket walks are the primary seek use
        // case: SeekHaptics takes no lifecycle dependency, so firing
        // with zero foreground setup must vibrate.
        haptics.tick(closeness = 0.5f)

        assertEquals(30L, shadowOf(vibrator).milliseconds)
    }

    // ─── Pure mapping functions ───────────────────────────────────────

    @Test
    fun `amplitudeFor maps iOS intensities onto the vibrator range`() {
        assertEquals(38, SeekHaptics.amplitudeFor(0.15f))
        assertEquals(89, SeekHaptics.amplitudeFor(0.35f))
        assertEquals(102, SeekHaptics.amplitudeFor(0.4f))
        assertEquals(140, SeekHaptics.amplitudeFor(0.55f))
        assertEquals(179, SeekHaptics.amplitudeFor(0.7f))
        assertEquals(255, SeekHaptics.amplitudeFor(1f))
    }

    @Test
    fun `amplitudeFor never emits zero or overflows`() {
        assertEquals(1, SeekHaptics.amplitudeFor(0f))
        assertEquals(255, SeekHaptics.amplitudeFor(5f))
    }

    @Test
    fun `clampCloseness bounds the engine curve input`() {
        assertEquals(0f, SeekHaptics.clampCloseness(-1f), 0.0f)
        assertEquals(1f, SeekHaptics.clampCloseness(2f), 0.0f)
        assertEquals(0.5f, SeekHaptics.clampCloseness(0.5f), 0.0f)
        assertEquals(0f, SeekHaptics.clampCloseness(Float.NaN), 0.0f)
    }
}
