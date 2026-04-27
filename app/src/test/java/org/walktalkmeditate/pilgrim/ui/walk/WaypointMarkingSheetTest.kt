// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaypointMarkingSheetTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `renders all six preset chips`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        listOf("Peaceful", "Beautiful", "Grateful", "Resting", "Inspired", "Arrived").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun `tapping a preset chip fires onMark with chip values`() {
        var captured: Pair<String, String>? = null
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { label, icon -> captured = label to icon },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Peaceful").performClick()
        assertEquals("Peaceful" to "leaf", captured)
    }

    @Test fun `Mark button disabled when custom text empty`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        composeRule.onNodeWithText("Mark").assertIsNotEnabled()
    }

    @Test fun `Mark button enabled and fires onMark with mappin icon when text typed`() {
        var captured: Pair<String, String>? = null
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { label, icon -> captured = label to icon },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Custom note").performTextInput("river")
        composeRule.onNodeWithText("Mark").assertIsEnabled().performClick()
        assertEquals("river" to "mappin", captured)
    }

    @Test fun `Cancel fires onDismiss without firing onMark`() {
        var marked = false
        var dismissed = false
        composeRule.setContent {
            WaypointMarkingSheet(
                onMark = { _, _ -> marked = true },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNode(hasText("Cancel") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
        assertEquals(false, marked)
    }

    @Test fun `custom text clamps at fifty chars`() {
        composeRule.setContent {
            WaypointMarkingSheet(onMark = { _, _ -> }, onDismiss = {})
        }
        val long = "a".repeat(60)
        composeRule.onNodeWithText("Custom note").performTextInput(long)
        composeRule.onNodeWithText("50/50").assertIsDisplayed()
    }

    @Test fun `iconKeyToVector returns distinct vectors for each chip key and falls back to LocationOn`() {
        // Each of the 6 chip keys must resolve to a DISTINCT Material
        // vector — proves the when-branches aren't accidentally collapsed
        // into the fallback. mappin and unknown both intentionally map to
        // LocationOn (mappin is the iOS canonical custom-text icon).
        val chipKeys = listOf("leaf", "eye", "heart", "figure.seated.side", "sparkles", "flag.fill")
        val resolved = chipKeys.map { iconKeyToVector(it) }
        assertEquals(
            "Each chip key must resolve to a distinct ImageVector (no collapsed branches)",
            chipKeys.size,
            resolved.toSet().size,
        )
        // None of the 6 chip keys should fall through to the LocationOn
        // fallback — that would mean the when-branch silently regressed.
        resolved.forEach { vector ->
            assertNotEquals(
                "Chip key resolved to LocationOn fallback (when-branch regressed)",
                Icons.Filled.LocationOn,
                vector,
            )
        }
        // mappin and unknown both go to LocationOn by design.
        assertEquals(Icons.Filled.LocationOn, iconKeyToVector("mappin"))
        assertEquals(Icons.Filled.LocationOn, iconKeyToVector("totally.unknown.future.symbol"))
    }
}
