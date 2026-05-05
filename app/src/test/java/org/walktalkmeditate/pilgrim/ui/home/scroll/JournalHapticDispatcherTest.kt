// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
