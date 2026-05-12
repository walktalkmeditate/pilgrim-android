// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimThemeAppearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Fixed date + hemisphere give the seasonal engine a deterministic
    // input so the expected colors are reproducible across CI runs.
    private val fixedDate = LocalDate.of(2025, 6, 21)
    private val fixedHemisphere = Hemisphere.Northern

    private fun expectedLight(): PilgrimColors =
        pilgrimSeasonalColors(pilgrimLightColors(), fixedDate, fixedHemisphere)

    private fun expectedDark(): PilgrimColors =
        pilgrimSeasonalColors(pilgrimDarkColors(), fixedDate, fixedHemisphere)

    @Test
    fun `light forces light colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(
                appearanceMode = AppearanceMode.Light,
                hemisphere = fixedHemisphere,
                today = fixedDate,
            ) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(expectedLight().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `dark forces dark colors`() {
        var captured: PilgrimColors? = null
        composeRule.setContent {
            PilgrimTheme(
                appearanceMode = AppearanceMode.Dark,
                hemisphere = fixedHemisphere,
                today = fixedDate,
            ) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(expectedDark().parchment, captured!!.parchment)
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
            PilgrimTheme(
                appearanceMode = AppearanceMode.System,
                hemisphere = fixedHemisphere,
                today = fixedDate,
            ) {
                val c = pilgrimColors
                SideEffect { captured = c }
            }
        }
        composeRule.runOnIdle {
            assertEquals(expectedLight().parchment, captured!!.parchment)
        }
    }

    @Test
    fun `light and dark resolve to different palettes`() {
        // Sanity: the test would silently pass if both palettes were
        // accidentally identical. Verifying they differ ensures the
        // assertions above mean what they say.
        assertNotEquals(expectedLight().parchment, expectedDark().parchment)
    }
}
