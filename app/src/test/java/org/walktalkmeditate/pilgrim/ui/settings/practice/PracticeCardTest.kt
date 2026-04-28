// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.practice

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Render + interaction tests for [PracticeCard]. The composable is
 * stateless — every value is supplied by the parent (Chunk F wires
 * SettingsViewModel) — so each test passes literal flags + capturing
 * lambdas to assert the right callback fires with the right value.
 *
 * Toggle assertions resolve the M3 `Switch` via `isToggleable()`
 * semantics (the AtmosphereCardTest precedent) — the textual labels
 * sit in a sibling Column without a click target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp")
class PracticeCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderDefault(
        beginWithIntention: Boolean = false,
        onSetBeginWithIntention: (Boolean) -> Unit = {},
        celestialAwareness: Boolean = false,
        onSetCelestialAwareness: (Boolean) -> Unit = {},
        zodiacSystem: ZodiacSystem = ZodiacSystem.Tropical,
        onSetZodiacSystem: (ZodiacSystem) -> Unit = {},
        distanceUnits: UnitSystem = UnitSystem.Metric,
        onSetDistanceUnits: (UnitSystem) -> Unit = {},
        walkWithCollective: Boolean = false,
        onSetWalkWithCollective: (Boolean) -> Unit = {},
        walkReliquary: Boolean = false,
        onSetWalkReliquary: (Boolean) -> Unit = {},
        showPhotosDeniedNote: Boolean = false,
    ) {
        composeRule.setContent {
            PilgrimTheme {
                // The full PracticeCard (5 toggles + 2 pickers + caption +
                // optional zodiac picker + optional photos-denied note)
                // is taller than Robolectric's default content surface,
                // so the trailing reliquary toggle's Switch can land
                // outside the hit-test region and `performClick`
                // silently no-ops. Sizing the host to 400×1000dp matches
                // the VoiceGuidePickerScreenTest precedent and keeps
                // every interactive node within bounds.
                Box(Modifier.size(400.dp, 1000.dp)) {
                    PracticeCard(
                        beginWithIntention = beginWithIntention,
                        onSetBeginWithIntention = onSetBeginWithIntention,
                        celestialAwareness = celestialAwareness,
                        onSetCelestialAwareness = onSetCelestialAwareness,
                        zodiacSystem = zodiacSystem,
                        onSetZodiacSystem = onSetZodiacSystem,
                        distanceUnits = distanceUnits,
                        onSetDistanceUnits = onSetDistanceUnits,
                        walkWithCollective = walkWithCollective,
                        onSetWalkWithCollective = onSetWalkWithCollective,
                        walkReliquary = walkReliquary,
                        onSetWalkReliquary = onSetWalkReliquary,
                        showPhotosDeniedNote = showPhotosDeniedNote,
                    )
                }
            }
        }
    }

    @Test
    fun `renders header and all five toggle labels`() {
        renderDefault(celestialAwareness = true)
        composeRule.onNodeWithText("Practice").assertExists()
        composeRule.onNodeWithText("How you walk").assertExists()
        composeRule.onNodeWithText("Begin with intention").assertExists()
        composeRule.onNodeWithText("Celestial awareness").assertExists()
        composeRule.onNodeWithText("Units").assertExists()
        composeRule.onNodeWithText("Walk with the collective").assertExists()
        composeRule.onNodeWithText("Gather walk photos").assertExists()
    }

    @Test
    fun `tapping intention toggle fires setter with true`() {
        var lastValue: Boolean? = null
        renderDefault(
            beginWithIntention = false,
            onSetBeginWithIntention = { lastValue = it },
        )
        composeRule.onAllNodes(isToggleable())[INTENTION_TOGGLE_INDEX].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `tapping celestial toggle fires setter with true`() {
        var lastValue: Boolean? = null
        renderDefault(
            celestialAwareness = false,
            onSetCelestialAwareness = { lastValue = it },
        )
        composeRule.onAllNodes(isToggleable())[CELESTIAL_TOGGLE_INDEX].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `tapping collective toggle fires setter with true`() {
        var lastValue: Boolean? = null
        renderDefault(
            walkWithCollective = false,
            onSetWalkWithCollective = { lastValue = it },
        )
        composeRule.onAllNodes(isToggleable())[COLLECTIVE_TOGGLE_INDEX].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `tapping reliquary toggle fires setter with true`() {
        var lastValue: Boolean? = null
        renderDefault(
            walkReliquary = false,
            onSetWalkReliquary = { lastValue = it },
        )
        // Sanity-check: 4 toggleable Switches render in default
        // configuration (zodiac picker hidden, photos note hidden).
        // The reliquary Switch is the LAST toggle; without the
        // class-level `qualifiers = "w400dp-h1000dp"` Robolectric's
        // default 320×470 viewport clips it to zero height and
        // performClick silently no-ops.
        composeRule.onAllNodes(isToggleable()).assertCountEquals(4)
        composeRule.onAllNodes(isToggleable())[RELIQUARY_TOGGLE_INDEX].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `zodiac picker is hidden when celestial is OFF`() {
        renderDefault(celestialAwareness = false)
        composeRule.onNodeWithText("Zodiac system").assertDoesNotExist()
    }

    @Test
    fun `zodiac picker is shown when celestial is ON`() {
        renderDefault(celestialAwareness = true)
        composeRule.onNodeWithText("Zodiac system").assertExists()
        composeRule.onNodeWithText("Tropical").assertExists()
        composeRule.onNodeWithText("Sidereal").assertExists()
    }

    @Test
    fun `tapping a zodiac segment fires onSetZodiacSystem with the right value`() {
        var picked: ZodiacSystem? = null
        renderDefault(
            celestialAwareness = true,
            zodiacSystem = ZodiacSystem.Tropical,
            onSetZodiacSystem = { picked = it },
        )
        composeRule.onNodeWithText("Sidereal").performClick()
        composeRule.runOnIdle {
            assertEquals(ZodiacSystem.Sidereal, picked)
        }
    }

    @Test
    fun `units caption is metric when distanceUnits is Metric`() {
        renderDefault(distanceUnits = UnitSystem.Metric)
        composeRule.onNodeWithText("km · min/km · m · °C").assertExists()
    }

    @Test
    fun `units caption is imperial when distanceUnits is Imperial`() {
        renderDefault(distanceUnits = UnitSystem.Imperial)
        composeRule.onNodeWithText("mi · min/mi · ft · °F").assertExists()
    }

    @Test
    fun `tapping a units segment fires onSetDistanceUnits with the right value`() {
        var picked: UnitSystem? = null
        renderDefault(
            distanceUnits = UnitSystem.Metric,
            onSetDistanceUnits = { picked = it },
        )
        composeRule.onNodeWithText("Imperial").performClick()
        composeRule.runOnIdle {
            assertEquals(UnitSystem.Imperial, picked)
        }
    }

    @Test
    fun `units segment shows current selection`() {
        renderDefault(distanceUnits = UnitSystem.Imperial)
        composeRule.onNodeWithText("Imperial").assertIsSelected()
    }

    @Test
    fun `photos-denied note is hidden when showPhotosDeniedNote is false`() {
        renderDefault(showPhotosDeniedNote = false)
        composeRule.onAllNodesWithText(
            "Photo access was declined.",
            substring = true,
        ).assertCountEquals(0)
    }

    @Test
    fun `photos-denied note is shown when showPhotosDeniedNote is true`() {
        renderDefault(showPhotosDeniedNote = true)
        composeRule.onNodeWithText(
            "Photo access was declined.",
            substring = true,
        ).assertExists()
    }

    private companion object {
        // Toggleable nodes appear in the order PracticeCard renders
        // them: intention, celestial, collective, reliquary. Pickers
        // emit selectable (single-choice) semantics, not toggleable, so
        // they are not part of this index sequence.
        const val INTENTION_TOGGLE_INDEX = 0
        const val CELESTIAL_TOGGLE_INDEX = 1
        const val COLLECTIVE_TOGGLE_INDEX = 2
        const val RELIQUARY_TOGGLE_INDEX = 3
    }
}
