// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowVibrator

/**
 * Stage 2-F lesson: every PR introducing `VibrationEffect.Composition.build()`
 * MUST exercise the real builder via Robolectric so runtime crashes
 * surface in unit tests, not on-device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class JournalHapticDispatcherTest {

    @Test
    fun `dispatch LightDot does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
    }

    @Test
    fun `dispatch HeavyDot does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.HeavyDot(0))
    }

    @Test
    fun `dispatch Milestone does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.Milestone(0))
    }

    @Test
    fun `dispatch None is no-op`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.None)
    }

    @Test
    fun `dispatch suppressed when sounds disabled`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { false },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
    }

    @Test
    fun `back-to-back dispatch within 50ms is throttled`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
        dispatcher.dispatch(HapticEvent.LightDot(1))
    }

    // ---- Gate/cairn vocabulary (U16) ----

    private fun shadowOfDefaultVibrator(context: Context): ShadowVibrator {
        val manager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return Shadow.extract(manager.defaultVibrator)
    }

    @Test
    fun `dispatch GateDot composes the milestone thump`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        shadow.setSupportedPrimitives(
            listOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            ),
        )
        val dispatcher = JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.GateDot(1))
        // A torii speaks the milestone thump regardless of size — the
        // exact Milestone composition.
        assertEquals(
            listOf(
                ShadowVibrator.PrimitiveEffect(
                    VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 0,
                ),
                ShadowVibrator.PrimitiveEffect(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f, 30,
                ),
            ),
            shadow.primitiveSegmentsInPrimitiveEffects,
        )
    }

    @Test
    fun `dispatch CairnDot composes the soft success double`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        shadow.setSupportedPrimitives(
            listOf(VibrationEffect.Composition.PRIMITIVE_CLICK),
        )
        val dispatcher = JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.CairnDot(2))
        assertEquals(
            listOf(
                ShadowVibrator.PrimitiveEffect(
                    VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 0,
                ),
                ShadowVibrator.PrimitiveEffect(
                    VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 120,
                ),
            ),
            shadow.primitiveSegmentsInPrimitiveEffects,
        )
    }

    @Test
    fun `dispatch GateDot and CairnDot fall back without primitive support`() {
        // Robolectric's vibrator reports no supported primitives by
        // default — exercises the one-shot fallback builder.
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.GateDot(0))
        dispatcher.dispatch(HapticEvent.CairnDot(1))
    }

    @Test
    fun `reduce motion suppresses the gate and cairn kinds`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            0f,
        )
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.GateDot(0))
        dispatcher.dispatch(HapticEvent.CairnDot(1))
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.isNullOrEmpty())
        assertEquals(0L, shadow.milliseconds)
    }
}
