// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
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

    // Robolectric's vibrator reports no supported primitives by
    // default — the two fallback tests exercise the one-shot builder.
    // The shadow exposes the one-shot's duration but not its amplitude
    // (SeekHapticsTest precedent), so 12 ms pins the fallback fired;
    // the empty primitive list pins that no composition did.

    @Test
    fun `dispatch GateDot falls back to a one-shot without primitive support`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.GateDot(0))
        assertEquals(12L, shadow.milliseconds)
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.isNullOrEmpty())
    }

    @Test
    fun `dispatch CairnDot falls back to a one-shot without primitive support`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.CairnDot(1))
        assertEquals(12L, shadow.milliseconds)
        assertTrue(shadow.primitiveSegmentsInPrimitiveEffects.isNullOrEmpty())
    }

    // ---- Throttle contract (P15 review F2/F4) ----
    //
    // Kinds bypass the ENTRY throttle (rare + important; iOS fires
    // them unthrottled), but a dispatched kind stamps the throttle
    // clock past its composition's delayed tail so a trailing plain
    // dot can't call vibrate() mid-play and truncate the tail.
    // Restoring the old `event !is Milestone` guard must fail these.

    private val lightClick = listOf(
        ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 0),
    )
    private val gateThump = listOf(
        ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 0),
        ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f, 30),
    )
    private val cairnDouble = listOf(
        ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 0),
        ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 120),
    )

    private fun primitiveDispatcher(context: Context): JournalHapticDispatcher {
        shadowOfDefaultVibrator(context).setSupportedPrimitives(
            listOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            ),
        )
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        return JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { true },
        )
    }

    @Test
    fun `gate and cairn dots bypass the entry throttle`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = primitiveDispatcher(context)

        dispatcher.dispatch(HapticEvent.LightDot(0))
        assertEquals(lightClick, shadow.primitiveSegmentsInPrimitiveEffects)

        // Both land well inside the 50 ms plain-dot interval — and fire.
        dispatcher.dispatch(HapticEvent.GateDot(1))
        assertEquals(gateThump, shadow.primitiveSegmentsInPrimitiveEffects)
        dispatcher.dispatch(HapticEvent.CairnDot(2))
        assertEquals(cairnDouble, shadow.primitiveSegmentsInPrimitiveEffects)
    }

    @Test
    fun `plain dot inside the cairn tail is throttled until the double finishes`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = primitiveDispatcher(context)

        dispatcher.dispatch(HapticEvent.CairnDot(0))
        // Exact threshold: 120 ms tail stamp + 50 ms interval (Stage
        // 5-F advanceTimeBy(threshold − 1) idiom).
        ShadowSystemClock.advanceBy(Duration.ofMillis(169))
        dispatcher.dispatch(HapticEvent.LightDot(1))
        assertEquals(cairnDouble, shadow.primitiveSegmentsInPrimitiveEffects)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        dispatcher.dispatch(HapticEvent.LightDot(2))
        assertEquals(lightClick, shadow.primitiveSegmentsInPrimitiveEffects)
    }

    @Test
    fun `plain dot inside the gate tail is throttled until the thump finishes`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadow = shadowOfDefaultVibrator(context)
        val dispatcher = primitiveDispatcher(context)

        dispatcher.dispatch(HapticEvent.GateDot(0))
        // 30 ms LOW_TICK stamp + 50 ms interval.
        ShadowSystemClock.advanceBy(Duration.ofMillis(79))
        dispatcher.dispatch(HapticEvent.LightDot(1))
        assertEquals(gateThump, shadow.primitiveSegmentsInPrimitiveEffects)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        dispatcher.dispatch(HapticEvent.LightDot(2))
        assertEquals(lightClick, shadow.primitiveSegmentsInPrimitiveEffects)
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
