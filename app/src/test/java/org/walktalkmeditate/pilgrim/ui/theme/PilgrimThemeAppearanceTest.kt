// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    fun `back-stack screen recolors after appearance flip while off-screen`() {
        // Regression for the staticCompositionLocalOf -> compositionLocalOf
        // swap (manual-QA #7b). A screen sitting on the Navigation back
        // stack while the user flips Appearance kept its old-theme
        // composition slots and rendered the stale palette (e.g. WalkShare
        // stuck in Constellation indigo). With a static local, the change
        // only re-ran the currently-composing provider subtree; the
        // retained back-stack screen never saw the new colors. A non-static
        // compositionLocalOf invalidates every reader, so the popped-back
        // screen recolors.
        val constellationParchment = pilgrimConstellationOverride(
            pilgrimSeasonalColors(pilgrimDarkColors(), fixedDate, fixedHemisphere),
        ).parchment
        val lightParchment = expectedLight().parchment

        var mode by mutableStateOf(AppearanceMode.Constellation)
        var screenAParchment: androidx.compose.ui.graphics.Color? = null
        var nav: androidx.navigation.NavHostController? = null

        composeRule.setContent {
            PilgrimTheme(
                appearanceMode = mode,
                hemisphere = fixedHemisphere,
                today = fixedDate,
            ) {
                val controller = rememberNavController()
                SideEffect { nav = controller }
                NavHost(navController = controller, startDestination = "a") {
                    composable("a") {
                        val c = pilgrimColors
                        SideEffect { screenAParchment = c.parchment }
                        Text("A")
                    }
                    composable("b") {
                        Text("B")
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(
                "screen A should resolve constellation parchment before the flip",
                constellationParchment,
                screenAParchment,
            )
        }

        // Push B on top so A leaves the back stack: its composition is
        // retained but no longer actively recomposing.
        composeRule.runOnIdle { nav!!.navigate("b") }
        composeRule.runOnIdle { }

        // User flips Appearance while A is off-screen on the back stack.
        mode = AppearanceMode.Light
        composeRule.runOnIdle { }

        // Pop back to A. Under a static local A would still render the
        // stale Constellation parchment; under compositionLocalOf it
        // recolors to the post-flip Light value.
        composeRule.runOnIdle { nav!!.popBackStack() }
        composeRule.runOnIdle {
            assertEquals(
                "popped-back screen A must pick up the post-flip Light parchment, " +
                    "not the stale Constellation value",
                lightParchment,
                screenAParchment,
            )
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
