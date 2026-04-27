// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimThemeAppearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `light forces light colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(appearanceMode = AppearanceMode.Light) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(pilgrimLightColors().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `dark forces dark colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(appearanceMode = AppearanceMode.Dark) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(pilgrimDarkColors().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `system defers to isSystemInDarkTheme`() {
        // Robolectric's UiModeManager defaults to UI_MODE_NIGHT_NO, so
        // `isSystemInDarkTheme()` returns false. AppearanceMode.System
        // must therefore resolve to the light palette here. This locks
        // in the System branch's behavior so a future refactor can't
        // silently regress it (e.g., flipping the `when` arm to always
        // dark would only be caught by this test).
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(appearanceMode = AppearanceMode.System) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(pilgrimLightColors().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `light and dark resolve to different palettes`() {
        // Sanity: the test would silently pass if both palettes were
        // accidentally identical. Verifying they differ ensures the
        // assertions above mean what they say.
        assertNotEquals(pilgrimLightColors().parchment, pilgrimDarkColors().parchment)
    }
}
