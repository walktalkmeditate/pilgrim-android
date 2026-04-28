// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSoundsEnabledTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default value is true when no provider is supplied`() {
        var captured: Boolean? = null
        composeRule.setContent {
            captured = LocalSoundsEnabled.current
        }
        composeRule.runOnIdle {
            assertEquals(true, captured)
        }
    }

    @Test
    fun `provider value of false is read by descendants`() {
        var captured: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalSoundsEnabled provides false) {
                captured = LocalSoundsEnabled.current
            }
        }
        composeRule.runOnIdle {
            assertEquals(false, captured)
        }
    }

    @Test
    fun `provider value of true is read by descendants`() {
        var captured: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalSoundsEnabled provides true) {
                captured = LocalSoundsEnabled.current
            }
        }
        composeRule.runOnIdle {
            assertEquals(true, captured)
        }
    }
}
