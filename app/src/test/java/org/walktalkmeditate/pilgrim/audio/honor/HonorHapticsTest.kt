// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import android.app.Application
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.seek.SeekHaptics

/**
 * The Honor haptics (parity spec C §13, and the stage's water, pilgrimage-stage
 * spec P3 §8) through Robolectric's vibrator: each builds its production
 * `VibrationEffect`, the house platform-object rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorHapticsTest {

    private lateinit var vibrator: Vibrator
    private lateinit var haptics: HonorHaptics

    @Before
    fun setUp() {
        vibrator = ApplicationProvider.getApplicationContext<Application>().getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        haptics = HonorHaptics(vibrator, SeekHaptics(vibrator))
    }

    private fun supportPrimitives() = shadowOf(vibrator).setSupportedPrimitives(
        listOf(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK),
    )

    private fun recordedPrimitives() = shadowOf(vibrator).primitiveEffects!!
        .ifEmpty { shadowOf(vibrator).primitiveSegmentsInPrimitiveEffects }

    @Test
    fun `a reached moment is one light click, iOS's light impact`() {
        supportPrimitives()

        haptics.momentReached()

        val tap = recordedPrimitives().single()
        assertEquals(VibrationEffect.Composition.PRIMITIVE_CLICK, tap.id)
        assertEquals(HonorHaptics.LIGHT_SCALE, tap.scale, 0.001f)
    }

    @Test
    fun `the soft tap is one soft tick, iOS's soft impact`() {
        supportPrimitives()

        haptics.softTap()

        val tap = recordedPrimitives().single()
        assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, tap.id)
        assertEquals(HonorHaptics.SOFT_SCALE, tap.scale, 0.001f)
    }

    @Test
    fun `arrival is Seek's three rising taps`() {
        supportPrimitives()

        haptics.arrival()

        val taps = recordedPrimitives()
        assertEquals(listOf(0.4f, 0.55f, 0.7f), taps.map { it.scale })
        assertEquals(listOf(0, 160, 180), taps.map { it.delay })
    }

    @Test
    fun `water ahead is one soft tick at the whisper's intensity`() {
        supportPrimitives()

        haptics.waterAhead()

        val tap = recordedPrimitives().single()
        assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, tap.id)
        assertEquals(0.4f, tap.scale, 0.001f)
        assertEquals(0, tap.delay)
    }

    @Test
    fun `without primitives water ahead falls back to a 30 ms one-shot`() {
        haptics.waterAhead()

        assertTrue(shadowOf(vibrator).primitiveEffects!!.isEmpty())
        assertEquals(30L, shadowOf(vibrator).milliseconds)
    }

    @Test
    fun `without primitives a tap falls back to a 30 ms one-shot`() {
        haptics.momentReached()

        assertTrue(shadowOf(vibrator).primitiveEffects!!.isEmpty())
        assertEquals(HonorHaptics.TAP_MS, shadowOf(vibrator).milliseconds)
    }
}
