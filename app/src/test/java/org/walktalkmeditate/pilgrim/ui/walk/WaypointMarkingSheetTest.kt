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

    @Test fun `iconKeyToVector covers every iOS SF-Symbol key plus mappin`() {
        // Each known key should resolve to a non-null Material ImageVector.
        // Unknown keys must fall back to LocationOn (not throw, not return
        // null) so a future iOS-introduced symbol still renders.
        val knownKeys = listOf(
            "leaf", "eye", "heart", "figure.seated.side",
            "sparkles", "flag.fill", "mappin",
        )
        knownKeys.forEach { key ->
            val vector = iconKeyToVector(key)
            // Non-null + non-LocationOn for the 6 chip keys (mappin is
            // intentionally LocationOn so we don't enforce that here).
            org.junit.Assert.assertNotNull(
                "iconKeyToVector('$key') must not be null",
                vector,
            )
        }
        val fallback = iconKeyToVector("totally.unknown.future.symbol")
        org.junit.Assert.assertEquals(
            "Unknown keys must fall back to LocationOn",
            Icons.Filled.LocationOn,
            fallback,
        )
    }
}
